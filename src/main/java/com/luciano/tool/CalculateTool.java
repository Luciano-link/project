package com.luciano.tool;

import com.google.gson.JsonObject;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * 数学计算工具(从 LiuHaoran 分支整合,零外部依赖)。
 * 自写递归下降表达式解析器,支持 + - * / % ^ 与括号,如 (12+3)*4、2^10、100/7。
 */
@Component
public class CalculateTool {

    private final ToolRegistry registry;

    public CalculateTool(ToolRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    public void init() {
        registry.register(new ToolDefinition(
                "calculate",
                "执行数学表达式计算并返回数值结果,支持 + - * / % ^ 和括号,例如 (12+3)*4、2^10、100/7",
                calculateSchema(),
                arguments -> {
                    String expression = getString(arguments, "expression", null);
                    if (expression == null || expression.isBlank()) {
                        return "错误:缺少表达式参数 expression。";
                    }
                    return evaluate(expression);
                }));
    }

    /** 安全表达式求值:只接受数字与 + - * / % ^ ( ),不执行任意代码 */
    private String evaluate(String expression) {
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

    private JsonObject calculateSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        JsonObject expression = new JsonObject();
        expression.addProperty("type", "string");
        expression.addProperty("description", "要计算的数学表达式,如 (12+3)*4");
        properties.add("expression", expression);
        schema.add("properties", properties);
        com.google.gson.JsonArray required = new com.google.gson.JsonArray();
        required.add("expression");
        schema.add("required", required);
        return schema;
    }

    private String getString(JsonObject obj, String key, String defaultVal) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultVal;
        }
        return obj.get(key).getAsString();
    }

    /** 递归下降表达式解析器:expr → term(+-)→ factor(* / %)→ unary(^ 右结合)→ primary(数字/括号) */
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
