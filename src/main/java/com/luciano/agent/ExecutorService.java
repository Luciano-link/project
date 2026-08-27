package com.luciano.agent;

import com.luciano.llm.LlmService;
import com.luciano.rag.RagService;
import com.luciano.weather.WeatherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 执行器:逐步执行规划器拆出的子任务清单。
 * 按"步骤行关键词"映射到能力:天气查询→天气服务,信息检索→RAG,方案生成→LLM。
 * 每步独立容错(失败不中断整体),中间结果写回 TaskState,最终方案存入 results.final。
 */
@Component
public class ExecutorService {

    private static final Logger log = LoggerFactory.getLogger(ExecutorService.class);

    private final LlmService llmService;
    private final WeatherService weatherService;
    private final RagService ragService;
    private final TaskStateManager taskManager;

    /** 支持检索的热门城市(知识库覆盖),其余城市靠 LLM 联网搜索兜底 */
    private static final List<String> CITIES = List.of("上海", "北京", "广州", "成都", "西安", "杭州", "重庆");

    public ExecutorService(LlmService llmService, WeatherService weatherService,
                           RagService ragService, TaskStateManager taskManager) {
        this.llmService = llmService;
        this.weatherService = weatherService;
        this.ragService = ragService;
        this.taskManager = taskManager;
    }

    /** 执行当前任务的全部子任务,返回最终方案文本;无任务或阶段不对返回 null */
    public String execute(String userId) {
        return execute(userId, null);
    }

    /**
     * 执行当前任务的全部子任务,返回最终方案文本。
     *
     * @param progress 步骤进度回调(可为 null),每完成一步通知用户,避免长时间无反馈
     */
    public String execute(String userId, java.util.function.Consumer<String> progress) {
        TaskState state = taskManager.get(userId);
        if (state == null) {
            log.warn("无可执行任务,userId = {}", userId);
            return null;
        }
        // 幂等:已完成的任务直接返回已有方案,避免客户端重试导致重复执行
        if (state.getPhase() == TaskState.Phase.DONE) {
            return state.getResult("final");
        }
        if (state.getPhase() != TaskState.Phase.EXECUTING) {
            log.warn("任务不在执行阶段,userId = {}, phase = {}", userId, state.getPhase());
            return null;
        }
        String plan = state.getResult("plan");
        if (plan == null) {
            log.warn("任务缺少拆解计划,userId = {}", userId);
            return null;
        }
        // 从目标中提取城市(默认上海)与天数,供天气/知识检索/方案生成使用
        String city = extractCity(state.getGoal());
        state.setResult("city", city);
        String days = extractDays(state.getGoal());
        state.setResult("days", days);
        log.info("识别目的地: {} {}", city, days);
        for (String line : plan.split("\n")) {
            String step = line.trim();
            if (step.isBlank()) {
                continue;
            }
            if (step.contains("天气")) {
                runWeather(state, progress);
            } else if (step.contains("检索")) {
                runRetrieve(state, progress);
            } else if (step.contains("方案")) {
                runPlan(state, progress);
            }
        }
        // 健壮性兜底:若拆解清单中缺失"方案生成"步骤,补生成一次,避免返回空
        String finalPlan = state.getResult("final");
        if (finalPlan == null || finalPlan.isBlank()) {
            log.warn("执行完成但缺少最终方案,补生成一次,userId = {}", userId);
            runPlan(state, progress);
            finalPlan = state.getResult("final");
        }
        state.setPhase(TaskState.Phase.DONE);
        log.info("任务执行完成,userId = {}", userId);
        return finalPlan;
    }

    /** 子任务:查询目的地天气 */
    private void runWeather(TaskState state, java.util.function.Consumer<String> progress) {
        if (progress != null) {
            progress.accept("正在查询" + state.getResult("city") + "天气...");
        }
        try {
            String weather = weatherService.getWeatherNow(state.getResult("city"));
            state.setResult("weather", weather);
            if (progress != null) {
                progress.accept("✓ 天气已获取");
            }
            log.info("天气查询完成: {}", weather);
        } catch (Exception e) {
            log.error("天气查询失败", e);
            state.setResult("weather", "天气查询失败");
        }
    }

    /** 子任务:检索目的地景点/美食/住宿知识(知识库未覆盖的城市返回空,由 LLM 搜索兜底) */
    private void runRetrieve(TaskState state, java.util.function.Consumer<String> progress) {
        if (progress != null) {
            progress.accept("正在检索景点/美食/住宿信息...");
        }
        try {
            String city = state.getResult("city");
            StringBuilder sb = new StringBuilder();
            appendIfPresent(sb, "景点", ragService.retrieve(city + "景点"));
            appendIfPresent(sb, "美食", ragService.retrieve(city + "美食"));
            appendIfPresent(sb, "住宿", ragService.retrieve(city + "住宿"));
            state.setResult("knowledge", sb.length() == 0 ? "知识库未覆盖该城市,请联网搜索" : sb.toString());
            if (progress != null) {
                progress.accept("✓ 信息检索完成");
            }
            log.info("知识检索完成,命中 = {}", sb.length() > 0);
        } catch (Exception e) {
            log.error("知识检索失败", e);
            state.setResult("knowledge", "知识检索失败");
        }
    }

    /** 子任务:生成最终方案(LLM 综合天气/知识/画像,开启联网搜索兜底任意城市) */
    private void runPlan(TaskState state, java.util.function.Consumer<String> progress) {
        if (progress != null) {
            progress.accept("正在生成完整方案,请稍候...");
        }
        try {
            String material = "【目的地】" + nullSafe(state.getResult("city"))
                    + nullSafe(state.getResult("days"))
                    + "\n【天气】" + nullSafe(state.getResult("weather"))
                    + "\n【参考知识】" + nullSafe(state.getResult("knowledge"))
                    + "\n【用户画像】" + buildProfileText(state);
            String finalPlan = llmService.ask(planSystem(state), material, true);
            state.setResult("final", finalPlan);
            log.info("方案生成完成,长度 = {}", finalPlan == null ? 0 : finalPlan.length());
        } catch (Exception e) {
            log.error("方案生成失败", e);
            state.setResult("final", "抱歉,方案生成失败,请稍后再试。");
        }
    }

    /** 方案生成系统提示词:目的地城市/天数动态注入,并明确 RAG 未覆盖时用自身知识兜底 */
    private String planSystem(TaskState state) {
        return "你是资深旅行规划师。请基于以下素材,结合用户画像,生成一份完整的" + nullSafe(state.getResult("city"))
                + nullSafe(state.getResult("days")) + "游出行方案:\n"
                + "要求:\n"
                + "1. 每日行程安排具体到时间段,【留足用餐时间(每餐至少1小时)和景点游玩时间(每处至少2小时)】,行程现实合理;\n"
                + "2. 包含美食推荐、住宿建议、出行注意事项;\n"
                + "3. 结合用户画像定制(预算/偏好/人数);\n"
                + "4. 排版清晰,用标题分段;\n"
                + "5. 若【参考知识】为空或未覆盖目的地,请基于自身知识规划,可联网核实。\n"
                + "直接输出完整方案。";
    }

    /** 从用户目标中提取城市,未匹配到已知城市时默认上海 */
    private String extractCity(String goal) {
        if (goal != null) {
            for (String city : CITIES) {
                if (goal.contains(city)) {
                    return city;
                }
            }
        }
        return "上海";
    }

    /** 从用户目标中提取游玩天数,默认三日 */
    private String extractDays(String goal) {
        if (goal != null) {
            if (goal.contains("一日")) return "一日";
            if (goal.contains("两日") || goal.contains("二日")) return "两日";
            if (goal.contains("三日")) return "三日";
            if (goal.contains("四日")) return "四日";
            if (goal.contains("五日")) return "五日";
        }
        return "三日";
    }

    private void appendIfPresent(StringBuilder sb, String tag, String content) {
        if (content != null && !content.isBlank()) {
            sb.append("【").append(tag).append("】").append(content).append("\n");
        }
    }

    private String buildProfileText(TaskState state) {
        StringBuilder sb = new StringBuilder();
        state.getProfile().forEach((k, v) -> sb.append(k).append(": ").append(v).append("; "));
        return sb.toString().isEmpty() ? "未指定" : sb.toString();
    }

    private String nullSafe(String s) {
        return s == null ? "无" : s;
    }
}
