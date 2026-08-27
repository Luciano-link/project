package com.luciano.agent;

import com.luciano.agent.TaskState.AgentPlan;
import com.luciano.agent.TaskState.StepResult;
import com.luciano.agent.TaskState.SubTask;
import com.luciano.conversation.ConversationService;
import com.luciano.llm.LlmService;
import com.luciano.rag.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Agent 总路由:澄清 → RAG → 规划 → 分步执行 → 汇总成品。
 */
@Service
public class AgentRouter {

    private static final Logger log = LoggerFactory.getLogger(AgentRouter.class);

    private static final String SYNTHESIZER_SYSTEM = """
            你是出行规划 Agent 的汇总器。根据各子任务执行结果,输出一份完整、结构化的中文出行方案成品。
            必须包含:行程概览、天气与穿衣、交通、住宿、逐日行程、餐饮推荐、预算粗估、注意事项与打包清单。
            使用清晰标题与分点,不要零散回答,不要只列提纲。城市信息必须与用户目标一致。
            """;

    private final boolean enabled;
    private final ClarifyService clarifyService;
    private final PlannerService plannerService;
    private final AgentExecutorService agentExecutorService;
    private final TaskStateManager taskStateManager;
    private final RagService ragService;
    private final LlmService llmService;
    private final ConversationService conversationService;

    public AgentRouter(@Value("${agent.enabled:true}") boolean enabled,
                       ClarifyService clarifyService,
                       PlannerService plannerService,
                       AgentExecutorService agentExecutorService,
                       TaskStateManager taskStateManager,
                       RagService ragService,
                       LlmService llmService,
                       ConversationService conversationService) {
        this.enabled = enabled;
        this.clarifyService = clarifyService;
        this.plannerService = plannerService;
        this.agentExecutorService = agentExecutorService;
        this.taskStateManager = taskStateManager;
        this.ragService = ragService;
        this.llmService = llmService;
        this.conversationService = conversationService;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @param progress 可选进度回调(index 从 1 开始, total 为子任务总数, message 为步骤标题)
     */
    public AgentResult run(String userId, String goal, Consumer<Progress> progress) {
        if (!enabled) {
            return AgentResult.fallback("Agent 规划未启用,请联系管理员开启 agent.enabled。");
        }
        String clarification = clarifyService.needClarification(goal);
        if (clarification != null) {
            return AgentResult.clarify(clarification);
        }

        TaskState state = taskStateManager.create(userId, goal);
        try {
            String knowledge = ragService.retrieve(goal);
            state.setStatus(TaskState.Status.PLANNING);
            notifyProgress(progress, Progress.planning());

            AgentPlan plan = plannerService.plan(goal, knowledge);
            state.setPlan(plan);
            state.setStatus(TaskState.Status.RUNNING);

            List<StepResult> completed = new ArrayList<>();
            int total = plan.subtasks().size();
            for (SubTask subTask : plan.subtasks()) {
                notifyProgress(progress, Progress.step(subTask.id(), total, subTask.title()));
                StepResult stepResult = agentExecutorService.executeStep(userId, goal, subTask, completed, knowledge);
                completed.add(stepResult);
                state.addStepResult(stepResult);
            }

            notifyProgress(progress, Progress.synthesizing(total));
            String finalDoc = synthesize(goal, completed, knowledge);
            state.setFinalOutput(finalDoc);
            state.setStatus(TaskState.Status.DONE);
            saveToConversation(userId, goal, finalDoc);
            log.info("[Agent] 任务完成,userId = {}, 子任务数 = {}", userId, total);
            return AgentResult.done(finalDoc, plan, completed);
        } catch (Exception e) {
            log.error("[Agent] 任务失败,userId = {}", userId, e);
            state.setStatus(TaskState.Status.FAILED);
            return AgentResult.fallback("规划执行失败,请稍后再试:" + e.getMessage());
        }
    }

    private String synthesize(String goal, List<StepResult> steps, String knowledge) {
        StringBuilder sb = new StringBuilder("用户目标:").append(goal).append("\n\n各子任务结果:\n");
        for (StepResult step : steps) {
            sb.append("## ").append(step.title()).append('\n').append(step.output()).append("\n\n");
        }
        String raw = llmService.agentGenerate(SYNTHESIZER_SYSTEM, sb.toString() + "\n请汇总为完整出行方案。");
        if (raw != null && !raw.isBlank()) {
            return raw.trim();
        }
        return sb.toString().trim();
    }

    private void saveToConversation(String userId, String goal, String finalDoc) {
        conversationService.addMessage(userId,
                com.alibaba.dashscope.common.Message.builder()
                        .role(com.alibaba.dashscope.common.Role.USER.getValue())
                        .content("[Agent任务] " + goal).build());
        conversationService.addMessage(userId,
                com.alibaba.dashscope.common.Message.builder()
                        .role(com.alibaba.dashscope.common.Role.ASSISTANT.getValue())
                        .content(finalDoc).build());
    }

    private void notifyProgress(Consumer<Progress> progress, Progress event) {
        if (progress != null) {
            progress.accept(event);
        }
    }

    public record Progress(Phase phase, int index, int total, String title) {

        public enum Phase {
            PLANNING, STEP, SYNTHESIZING
        }

        public static Progress planning() {
            return new Progress(Phase.PLANNING, 0, 0, "任务拆解");
        }

        public static Progress step(int index, int total, String title) {
            return new Progress(Phase.STEP, index, total, title);
        }

        public static Progress synthesizing(int total) {
            return new Progress(Phase.SYNTHESIZING, total, total, "方案汇总");
        }
    }

    public record AgentResult(Type type, String message, AgentPlan plan, List<StepResult> steps) {
        public enum Type { DONE, CLARIFY, FALLBACK }

        public static AgentResult done(String message, AgentPlan plan, List<StepResult> steps) {
            return new AgentResult(Type.DONE, message, plan, steps);
        }

        public static AgentResult clarify(String message) {
            return new AgentResult(Type.CLARIFY, message, null, List.of());
        }

        public static AgentResult fallback(String message) {
            return new AgentResult(Type.FALLBACK, message, null, List.of());
        }
    }
}
