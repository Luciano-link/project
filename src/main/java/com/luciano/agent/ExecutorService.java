package com.luciano.agent;

import com.luciano.llm.LlmService;
import com.luciano.rag.RagService;
import com.luciano.weather.WeatherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 执行器:逐步执行规划器拆出的子任务清单。
 * 按"步骤行关键词"映射到能力:天气查询→天气服务,信息检索→RAG,方案生成→LLM。
 * 每步独立容错(失败不中断整体),中间结果写回 TaskState,最终方案存入 results.final。
 */
@Component
public class ExecutorService {

    private static final Logger log = LoggerFactory.getLogger(ExecutorService.class);

    private static final String PLAN_SYSTEM = "你是资深旅行规划师。请基于以下素材,结合用户画像,生成一份完整的上海三日游出行方案:\n"
            + "要求:\n"
            + "1. 每日行程安排具体到时间段,【留足用餐时间(每餐至少1小时)和景点游玩时间(每处至少2小时)】,行程现实合理;\n"
            + "2. 包含美食推荐、住宿建议、出行注意事项;\n"
            + "3. 结合用户画像定制(预算/偏好/人数);\n"
            + "4. 排版清晰,用标题分段。\n"
            + "直接输出完整方案。";

    private final LlmService llmService;
    private final WeatherService weatherService;
    private final RagService ragService;
    private final TaskStateManager taskManager;

    public ExecutorService(LlmService llmService, WeatherService weatherService,
                           RagService ragService, TaskStateManager taskManager) {
        this.llmService = llmService;
        this.weatherService = weatherService;
        this.ragService = ragService;
        this.taskManager = taskManager;
    }

    /** 执行当前任务的全部子任务,返回最终方案文本;无任务或阶段不对返回 null */
    public String execute(String userId) {
        TaskState state = taskManager.get(userId);
        if (state == null || state.getPhase() != TaskState.Phase.EXECUTING) {
            log.warn("无可执行任务,userId = {}, phase = {}", userId,
                    state == null ? "NONE" : state.getPhase());
            return null;
        }
        String plan = state.getResult("plan");
        if (plan == null) {
            log.warn("任务缺少拆解计划,userId = {}", userId);
            return null;
        }
        for (String line : plan.split("\n")) {
            String step = line.trim();
            if (step.isBlank()) {
                continue;
            }
            if (step.contains("天气")) {
                runWeather(state);
            } else if (step.contains("检索")) {
                runRetrieve(state);
            } else if (step.contains("方案")) {
                runPlan(state);
            }
        }
        // 健壮性兜底:若拆解清单中缺失"方案生成"步骤,补生成一次,避免返回空
        String finalPlan = state.getResult("final");
        if (finalPlan == null || finalPlan.isBlank()) {
            log.warn("执行完成但缺少最终方案,补生成一次,userId = {}", userId);
            runPlan(state);
            finalPlan = state.getResult("final");
        }
        state.setPhase(TaskState.Phase.DONE);
        log.info("任务执行完成,userId = {}", userId);
        return finalPlan;
    }

    /** 子任务:查询目的地天气 */
    private void runWeather(TaskState state) {
        try {
            String weather = weatherService.getWeatherNow("上海");
            state.setResult("weather", weather);
            log.info("天气查询完成: {}", weather);
        } catch (Exception e) {
            log.error("天气查询失败", e);
            state.setResult("weather", "天气查询失败");
        }
    }

    /** 子任务:检索景点/美食/住宿知识 */
    private void runRetrieve(TaskState state) {
        try {
            StringBuilder sb = new StringBuilder();
            appendIfPresent(sb, "景点", ragService.retrieve("上海景点"));
            appendIfPresent(sb, "美食", ragService.retrieve("上海美食"));
            appendIfPresent(sb, "住宿", ragService.retrieve("上海住宿"));
            state.setResult("knowledge", sb.length() == 0 ? "未检索到相关参考知识" : sb.toString());
            log.info("知识检索完成");
        } catch (Exception e) {
            log.error("知识检索失败", e);
            state.setResult("knowledge", "知识检索失败");
        }
    }

    /** 子任务:生成最终方案(LLM 综合天气/知识/画像) */
    private void runPlan(TaskState state) {
        try {
            String material = "【天气】" + nullSafe(state.getResult("weather"))
                    + "\n【参考知识】" + nullSafe(state.getResult("knowledge"))
                    + "\n【用户画像】" + buildProfileText(state);
            String finalPlan = llmService.ask(PLAN_SYSTEM, material);
            state.setResult("final", finalPlan);
            log.info("方案生成完成,长度 = {}", finalPlan == null ? 0 : finalPlan.length());
        } catch (Exception e) {
            log.error("方案生成失败", e);
            state.setResult("final", "抱歉,方案生成失败,请稍后再试。");
        }
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
