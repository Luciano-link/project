package com.luciano.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Function Calling / Tool Use 服务:管理工具注册表,并驱动「模型 → 执行工具 → 回填结果 → 再问模型」的调用循环。
 *
 * <p>工作流程(模型驱动的工具调用循环):
 * <pre>
 * 用户问题 → LLM(带工具清单)──┬─ 直接回答(无需工具)→ 结束
 *                           └─ 发起 tool_calls(name + arguments)
 *                                  ↓ 应用侧执行真实函数(注册表分发)
 *                              把「工具结果」作为 role=tool 消息回填
 *                                  ↓ 再次调用 LLM
 *                              模型综合工具结果生成最终回答 → 结束
 * </pre>
 *
 * <p><b>多步链式调用</b>:后一步的参数依赖前一步的工具返回值时,模型会在下一轮
 * 基于已回填的真实结果发起新调用,天然支持链式流程(如:查气温 → 换算单位)。
 *
 * <p>工具通过 {@link #registerTool(Tool)} 注册,签名用 JSON Schema 描述(见 {@link Tool#parameters()}),
 * 模型据此生成合法参数;执行异常会被捕获并作为工具结果回填,不会中断整个循环。
 */
@Component
public class FunctionCallService {

    private static final Logger log = LoggerFactory.getLogger(FunctionCallService.class);

    /** 工具调用最大轮数,防止模型陷入死循环。 */
    private static final int MAX_ROUNDS = 5;

    private final DashScopeClient dashScopeClient;
    private final WeatherClient weatherClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 工具注册表:name → 工具实现。 */
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    @Value("${weather.default-city:南京}")
    private String defaultCity;

    public FunctionCallService(DashScopeClient dashScopeClient, WeatherClient weatherClient) {
        this.dashScopeClient = dashScopeClient;
        this.weatherClient = weatherClient;
        registerDefaults();
    }

    /**
     * 工具接口:每个工具声明 名称/说明/入参 Schema,并提供真实执行逻辑。
     */
    public interface Tool {
        /** 函数名,模型据此发起调用,须唯一。 */
        String name();

        /** 函数作用说明,写清楚"什么时候该用、返回什么"能显著提升模型调用准确率。 */
        String description();

        /** JSON Schema 描述的入参结构(parameters 节点)。 */
        ObjectNode parameters();

        /**
         * 执行工具。
         *
         * @param argumentsJson 模型按 JSON Schema 生成的参数字符串(如 {@code {"city":"北京"}})
         * @return 工具结果文本(会回填给模型)
         * @throws Exception 执行失败时抛出,外层会捕获并转为「工具执行失败:…」结果回填
         */
        String execute(String argumentsJson) throws Exception;
    }

    /**
     * 注册自定义工具(可随时扩展)。
     */
    public void registerTool(Tool tool) {
        if (tool == null || tool.name() == null || tool.name().isBlank()) {
            throw new IllegalArgumentException("工具名不能为空");
        }
        if (tools.put(tool.name(), tool) != null) {
            log.warn("工具 {} 已存在,已覆盖", tool.name());
        }
        log.info("已注册工具: {}", tool.name());
    }

    /**
     * 注册内置工具。子类可覆写此方法(如测试时替换为桩工具,避免外部依赖)。
     */
    protected void registerDefaults() {
        registerTool(new TimeTool());
        registerTool(new WeatherTool());
        registerTool(new CalculateTool());
        registerTool(new UnitConvertTool());
    }

    /**
     * 工具清单:遍历注册表,输出 OpenAI 兼容的 tools 数组。
     */
    public List<DashScopeClient.FunctionTool> buildTools() {
        List<DashScopeClient.FunctionTool> list = new ArrayList<>();
        for (Tool tool : tools.values()) {
            list.add(new DashScopeClient.FunctionTool(tool.name(), tool.description(), tool.parameters()));
        }
        return list;
    }

    /**
     * 一次工具调用的执行记录(学习用:观察模型要了哪些参数、工具返回了什么)。
     */
    public record Step(String tool, String arguments, String result) {
    }

    /**
     * 一次 Function Calling 完整运行的结果。
     */
    public record RunResult(String question, List<Step> steps, String finalAnswer) {
    }

    /**
     * 运行 Function Calling 循环,见 {@link #run(String, boolean)}。
     */
    public RunResult run(String question) throws Exception {
        return run(question, false);
    }

    /**
     * 运行 Function Calling 循环:调用模型 → 若发起工具调用则执行并回填 → 直到模型直接回答。
     *
     * <p>支持多步链式调用:第 N 轮的模型输入已包含前 N-1 轮的工具结果,
     * 因此后续工具的参数可以使用前面工具的真实返回值。
     *
     * @param question 用户问题,如「北京明天天气怎么样」「现在几点了」「算一下 123*456」
     * @param concise  true 时要求模型简短口语化回答(语音播报场景)
     */
    public RunResult run(String question, boolean concise) throws Exception {
        List<DashScopeClient.HistoryMessage> messages = new ArrayList<>();
        messages.add(DashScopeClient.HistoryMessage.user(question));
        List<Step> steps = new ArrayList<>();
        List<DashScopeClient.FunctionTool> tools = buildTools();

        String systemHint = "需要实时信息(时间、天气等)或精确计算、单位换算时,请务必调用工具获取,不要凭记忆编造。"
                + "多步任务请按顺序调用:后一步的参数必须使用前一步工具返回的真实结果,禁止猜测或编造中间值。";
        if (concise) {
            systemHint += "回答请简洁口语化,控制在150字以内,适合语音播报。";
        }

        for (int round = 0; round < MAX_ROUNDS; round++) {
            DashScopeClient.ChatResult result = dashScopeClient.chatWithTools(
                    systemHint, messages, tools, null);

            if (!result.hasToolCalls()) {
                // 模型直接回答 → 循环结束
                log.info("Function Calling 完成(第 {} 轮,调用了 {} 个工具)", round, steps.size());
                return new RunResult(question, steps, result.content());
            }

            // 1) 把「助手发起的工具调用」追加进对话(否则模型不知道这是它自己发起的)
            messages.add(DashScopeClient.HistoryMessage.assistantWithToolCalls(result.toolCalls()));
            log.info("第 {} 轮:模型发起 {} 个工具调用", round + 1, result.toolCalls().size());

            // 2) 逐个执行工具,把结果作为 role=tool 消息回填(必须带 tool_call_id)
            for (DashScopeClient.ToolCall call : result.toolCalls()) {
                String toolResult = executeTool(call.name(), call.arguments());
                steps.add(new Step(call.name(), call.arguments(), toolResult));
                log.info("工具 {} 执行完成,参数: {},结果: {}", call.name(), call.arguments(), toolResult);
                messages.add(DashScopeClient.HistoryMessage.toolResult(call.id(), toolResult));
            }
            // 3) 回到循环顶部,带着工具结果再问一次模型
        }
        throw new IllegalStateException("工具调用超过最大轮数 " + MAX_ROUNDS + ",已终止");
    }

    /**
     * 通过注册表执行工具:未知工具或执行异常都会被捕获,转为工具结果回填给模型,
     * 由模型自行应对,而不是中断整个调用循环。
     */
    private String executeTool(String name, String argumentsJson) {
        Tool tool = tools.get(name);
        if (tool == null) {
            log.warn("模型发起了未注册的工具: {}", name);
            return "未知工具:" + name + ",可用工具:" + String.join("、", tools.keySet());
        }
        try {
            return tool.execute(argumentsJson);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("工具 {} 执行异常: {}", name, msg);
            return "工具执行失败:" + msg;
        }
    }

    // ==================== 内置工具实现 ====================

    /** 获取当前日期、时间和星期几(无入参)。 */
    private final class TimeTool implements Tool {
        @Override
        public String name() {
            return "get_current_time";
        }

        @Override
        public String description() {
            return "获取当前日期、时间和星期几";
        }

        @Override
        public ObjectNode parameters() {
            ObjectNode p = objectMapper.createObjectNode();
            p.put("type", "object");
            p.putObject("properties");
            p.putArray("required");
            p.put("additionalProperties", false);
            return p;
        }

        @Override
        public String execute(String argumentsJson) {
            return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE HH:mm", Locale.CHINA));
        }
    }

    /** 查询天气:复用 WeatherClient(实时/明天/几天,含空气质量、日出日落、生活指数)。 */
    private final class WeatherTool implements Tool {
        @Override
        public String name() {
            return "get_weather";
        }

        @Override
        public String description() {
            return "查询城市实时天气、空气质量、日出日落、紫外线与生活指数,或明天/未来几天的预报";
        }

        @Override
        public ObjectNode parameters() {
            ObjectNode p = objectMapper.createObjectNode();
            p.put("type", "object");
            ObjectNode props = p.putObject("properties");
            props.putObject("city")
                    .put("type", "string")
                    .put("description", "城市名,例如:北京、上海、南京、广州、深圳")
                    .put("minLength", 2)
                    .put("maxLength", 6);
            ObjectNode timeProp = props.putObject("time");
            timeProp.put("type", "string");
            timeProp.put("description", "查询的时间范围");
            ArrayNode enumArr = timeProp.putArray("enum");
            enumArr.add("现在").add("今天").add("明天").add("几天");
            timeProp.put("default", "现在");
            p.putArray("required").add("city");
            p.put("additionalProperties", false);
            return p;
        }

        @Override
        public String execute(String argumentsJson) throws Exception {
            JsonNode args = objectMapper.readTree(argumentsJson);
            String city = args.path("city").asText("");
            String time = args.path("time").asText("现在");
            return buildWeatherText(city, time);
        }
    }

    /** 精确计算:自写递归下降表达式解析器,支持 + - * / % ^ 与括号。 */
    private final class CalculateTool implements Tool {
        @Override
        public String name() {
            return "calculate";
        }

        @Override
        public String description() {
            return "执行数学表达式计算并返回数值结果,支持 + - * / % ^ 和括号,例如 (12+3)*4、2^10、100/7";
        }

        @Override
        public ObjectNode parameters() {
            ObjectNode p = objectMapper.createObjectNode();
            p.put("type", "object");
            p.putObject("properties")
                    .putObject("expression")
                    .put("type", "string")
                    .put("description", "要计算的数学表达式")
                    .put("minLength", 1)
                    .put("maxLength", 100);
            p.putArray("required").add("expression");
            p.put("additionalProperties", false);
            return p;
        }

        @Override
        public String execute(String argumentsJson) throws Exception {
            JsonNode args = objectMapper.readTree(argumentsJson);
            return calculate(args.path("expression").asText(""));
        }
    }

    /** 单位换算:温度(℃/℉/K)、长度(m/km/cm/mm/尺)、重量(kg/g/斤/两/磅),同维度互转。 */
    private final class UnitConvertTool implements Tool {
        private final Set<String> TEMP = Set.of("celsius", "fahrenheit", "kelvin");
        private final Set<String> LENGTH = Set.of("m", "km", "cm", "mm", "chi");
        private final Set<String> WEIGHT = Set.of("kg", "g", "jin", "liang", "lb");

        @Override
        public String name() {
            return "unit_convert";
        }

        @Override
        public String description() {
            return "单位换算,仅支持同维度互转。温度:celsius摄氏度/fahrenheit华氏度/kelvin开尔文;"
                    + "长度:m米/km千米/cm厘米/mm毫米/chi尺;重量:kg千克/g克/jin斤/liang两/lb磅。";
        }

        @Override
        public ObjectNode parameters() {
            ObjectNode p = objectMapper.createObjectNode();
            p.put("type", "object");
            ObjectNode props = p.putObject("properties");
            props.putObject("value").put("type", "number").put("description", "要换算的数值");
            String[] units = {"celsius", "fahrenheit", "kelvin", "m", "km", "cm", "mm", "chi", "kg", "g", "jin", "liang", "lb"};
            ObjectNode fromProp = props.putObject("from");
            fromProp.put("type", "string");
            fromProp.put("description", "原单位代码");
            ArrayNode fromEnum = fromProp.putArray("enum");
            for (String u : units) {
                fromEnum.add(u);
            }
            ObjectNode toProp = props.putObject("to");
            toProp.put("type", "string");
            toProp.put("description", "目标单位代码,须与原单位同维度");
            ArrayNode toEnum = toProp.putArray("enum");
            for (String u : units) {
                toEnum.add(u);
            }
            p.putArray("required").add("value").add("from").add("to");
            p.put("additionalProperties", false);
            return p;
        }

        @Override
        public String execute(String argumentsJson) throws Exception {
            JsonNode args = objectMapper.readTree(argumentsJson);
            double value = args.path("value").asDouble();
            String from = args.path("from").asText("");
            String to = args.path("to").asText("");
            try {
                return formatNumber(convert(from, to, value));
            } catch (IllegalArgumentException e) {
                return "无法换算:" + e.getMessage();
            }
        }

        private double convert(String from, String to, double value) {
            if (TEMP.contains(from) && TEMP.contains(to)) {
                double celsius = switch (from) {
                    case "celsius" -> value;
                    case "fahrenheit" -> (value - 32) * 5 / 9;
                    case "kelvin" -> value - 273.15;
                    default -> throw new IllegalArgumentException("未知温度单位:" + from);
                };
                return switch (to) {
                    case "celsius" -> celsius;
                    case "fahrenheit" -> celsius * 9 / 5 + 32;
                    case "kelvin" -> celsius + 273.15;
                    default -> throw new IllegalArgumentException("未知温度单位:" + to);
                };
            }
            if (LENGTH.contains(from) && LENGTH.contains(to)) {
                double meters = switch (from) {
                    case "m" -> value;
                    case "km" -> value * 1000;
                    case "cm" -> value / 100;
                    case "mm" -> value / 1000;
                    case "chi" -> value / 3; // 1米 = 3尺
                    default -> throw new IllegalArgumentException("未知长度单位:" + from);
                };
                return switch (to) {
                    case "m" -> meters;
                    case "km" -> meters / 1000;
                    case "cm" -> meters * 100;
                    case "mm" -> meters * 1000;
                    case "chi" -> meters * 3;
                    default -> throw new IllegalArgumentException("未知长度单位:" + to);
                };
            }
            if (WEIGHT.contains(from) && WEIGHT.contains(to)) {
                double grams = switch (from) {
                    case "kg" -> value * 1000;
                    case "g" -> value;
                    case "jin" -> value * 500;      // 1斤 = 500g
                    case "liang" -> value * 50;     // 1两 = 50g
                    case "lb" -> value * 453.59237; // 1磅 = 453.59237g
                    default -> throw new IllegalArgumentException("未知重量单位:" + from);
                };
                return switch (to) {
                    case "kg" -> grams / 1000;
                    case "g" -> grams;
                    case "jin" -> grams / 500;
                    case "liang" -> grams / 50;
                    case "lb" -> grams / 453.59237;
                    default -> throw new IllegalArgumentException("未知重量单位:" + to);
                };
            }
            throw new IllegalArgumentException("单位维度不一致,无法换算: " + from + " → " + to);
        }

        private String formatNumber(double v) {
            if (!Double.isFinite(v)) {
                return "结果超出范围";
            }
            if (v == Math.rint(v)) {
                return String.valueOf((long) v);
            }
            String s = String.format(Locale.ROOT, "%.4f", v);
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
            return s;
        }
    }

    // ==================== 工具执行辅助 ====================

    /**
     * 安全表达式求值:只接受数字与 + - * / % ^ ( ),不执行任意代码。
     * 计算失败时返回错误说明(作为工具结果回填给模型,让模型自己应对)。
     */
    private String calculate(String expression) {
        if (expression == null || expression.isBlank()) {
            return "表达式为空";
        }
        try {
            double result = new ExprParser(expression).parse();
            if (!Double.isFinite(result)) {
                return "结果超出范围(可能是除以 0 了)";
            }
            if (result == Math.rint(result)) {
                return String.valueOf((long) result);
            }
            return String.valueOf(result);
        } catch (Exception e) {
            return "表达式无法计算:" + e.getMessage();
        }
    }

    /**
     * 天气查询:城市清洗 → 默认城市兜底 → 按时间意图取数;失败再回落默认城市。
     */
    private String buildWeatherText(String city, String time) throws Exception {
        String effective = weatherClient.sanitizeCity(city);
        if (effective.isBlank()) {
            effective = defaultCity;
        }
        try {
            return weatherTextFor(effective, time);
        } catch (Exception first) {
            if (!defaultCity.equals(effective)) {
                try {
                    return weatherTextFor(defaultCity, time);
                } catch (Exception fallbackError) {
                    log.warn("默认城市 {} 天气查询也失败: {}", defaultCity, fallbackError.getMessage());
                }
            }
            throw first;
        }
    }

    private String weatherTextFor(String city, String time) throws Exception {
        return switch (time) {
            case "明天" -> weatherClient.formatTomorrow(city, weatherClient.getTomorrow(city));
            case "几天" -> weatherClient.format3d(city, weatherClient.get3d(city));
            default -> {
                Weather.Current now = weatherClient.getNow(city);
                List<Weather.Daily> forecast = weatherClient.get3d(city);
                yield weatherClient.describeToday(city, now, forecast.get(0))
                        + "\n" + weatherClient.format3d(city, forecast);
            }
        };
    }

    /**
     * 递归下降表达式解析器:expr → term(+-)→ factor(* / %)→ unary(^ 右结合)→ primary(数字/括号)。
     */
    static final class ExprParser {
        private final String s;
        private int pos;

        ExprParser(String input) {
            this.s = input.replace(" ", "");
        }

        double parse() {
            double v = expr();
            if (pos < s.length()) {
                throw new IllegalArgumentException("多余字符: " + s.substring(pos));
            }
            return v;
        }

        private double expr() {
            double v = term();
            while (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                char c = s.charAt(pos++);
                double r = term();
                v = c == '+' ? v + r : v - r;
            }
            return v;
        }

        private double term() {
            double v = factor();
            while (pos < s.length() && (s.charAt(pos) == '*' || s.charAt(pos) == '/' || s.charAt(pos) == '%')) {
                char c = s.charAt(pos++);
                double r = factor();
                v = c == '*' ? v * r : c == '/' ? v / r : v % r;
            }
            return v;
        }

        private double factor() {
            double v = unary();
            if (pos < s.length() && s.charAt(pos) == '^') {
                pos++;
                // 右结合:2^3^2 = 2^(3^2) = 512
                return Math.pow(v, factor());
            }
            return v;
        }

        private double unary() {
            if (pos < s.length() && (s.charAt(pos) == '-' || s.charAt(pos) == '+')) {
                char c = s.charAt(pos++);
                double v = unary();
                return c == '-' ? -v : v;
            }
            return primary();
        }

        private double primary() {
            if (pos < s.length() && s.charAt(pos) == '(') {
                pos++;
                double v = expr();
                if (pos >= s.length() || s.charAt(pos++) != ')') {
                    throw new IllegalArgumentException("括号不匹配");
                }
                return v;
            }
            int start = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalArgumentException("位置 " + start + " 处不是数字");
            }
            return Double.parseDouble(s.substring(start, pos));
        }
    }
}
