package com.luciano.agent;

import com.luciano.llm.LlmService;
import org.springframework.stereotype.Component;

/**
 * Agent 消息路由。
 * 在微信消息进入 Skill/RAG/LLM 兜底之前,判断是否由长任务 Agent 接管:
 * - 无进行中任务且消息是规划类目标 → 启动引导式澄清
 * - 有任务且处于澄清阶段 → 解析用户回复画像,完整后自动执行规划
 * 执行过程通过 progress 回调分步反馈进度;完成后返回精简概览,完整方案留待邮件发送。
 */
@Component
public class AgentRouter {

    private static final String SUMMARY_SYSTEM = "你是出行规划助手。请把下面的完整方案压缩成不超过200字的中文概览,"
            + "只保留:每日行程要点、美食/住宿关键建议、最重要的注意事项,不要遗漏关键信息。\n完整方案:\n";

    private final TaskStateManager taskManager;
    private final ClarifyService clarifyService;
    private final PlannerService plannerService;
    private final ExecutorService executorService;
    private final LlmService llmService;

    public AgentRouter(TaskStateManager taskManager, ClarifyService clarifyService,
                       PlannerService plannerService, ExecutorService executorService,
                       LlmService llmService) {
        this.taskManager = taskManager;
        this.clarifyService = clarifyService;
        this.plannerService = plannerService;
        this.executorService = executorService;
        this.llmService = llmService;
    }

    /** 是否由 Agent 接管:澄清阶段的回复,或规划类目标(无任务/旧任务已完成时新开任务) */
    public boolean shouldHandle(String userId, String text) {
        TaskState state = taskManager.get(userId);
        if (state != null && state.getPhase() == TaskState.Phase.CLARIFYING) {
            return true;
        }
        return isPlanGoal(text);
    }

    /** 处理结果:立即回复的提示文本 + 可选的异步执行(返回最终方案) */
    public record AgentResponse(String immediateReply, java.util.function.Supplier<String> asyncPlan) {
    }

    /** 处理消息(在异步线程执行):先给即时反馈,执行过程通过 progress 分步反馈,完成后返回精简概览 */
    public AgentResponse handle(String userId, String text, java.util.function.Consumer<String> progress) {
        TaskState state = taskManager.get(userId);
        if (state == null || state.getPhase() != TaskState.Phase.CLARIFYING) {
            // 无进行中任务或旧任务不在澄清阶段:规划目标则新开任务
            if (isPlanGoal(text)) {
                return new AgentResponse(clarifyService.start(userId, text), null);
            }
            return null;
        }
        boolean complete = clarifyService.parseReply(userId, text);
        if (!complete) {
            return new AgentResponse("还需要告诉我:" + String.join("、", clarifyService.missingFields(userId)), null);
        }
        // 画像完整:先确认收到,再异步执行规划(分步进度 + 精简概览)
        return new AgentResponse("好的,正在为你规划,请稍候...", () -> {
            plannerService.plan(state);
            String full = executorService.execute(userId, progress);
            if (full == null) {
                return "规划生成中,请稍后再试。";
            }
            String summary = llmService.ask(SUMMARY_SYSTEM, full);
            if (summary == null || summary.isBlank()) {
                summary = full;
            }
            return summary + "\n\n回复\"把方案发到 邮箱\"可获取完整方案。";
        });
    }

    /** 兼容调试接口:同步返回处理结果(无进度回调,直接返回精简概览) */
    public String process(String userId, String text) {
        AgentResponse resp = handle(userId, text, null);
        if (resp == null) {
            return null;
        }
        return resp.asyncPlan() == null ? resp.immediateReply() : resp.asyncPlan().get();
    }

    /** 判断是否为"规划类目标"(而非普通聊天) */
    private boolean isPlanGoal(String text) {
        return text.contains("规划") || text.contains("攻略") || text.contains("几日游")
                || text.contains("一日游") || text.contains("出行方案");
    }
}
