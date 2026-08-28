package com.luciano.agent;

import com.luciano.conversation.ConversationService;
import com.luciano.llm.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(AgentRouter.class);

    private static final String SUMMARY_SYSTEM = "你是出行规划助手。请把下面的完整方案压缩成不超过200字的中文概览,"
            + "只保留:每日行程要点、美食/住宿关键建议、最重要的注意事项,不要遗漏关键信息。\n完整方案:\n";

    private static final String REFINE_SYSTEM = "你是出行规划助手。用户已有一份完整出行方案,现在要求针对其中某部分"
            + "(如某一天的行程)做更详细的时间安排和路线安排。\n"
            + "请基于原方案细化该部分,【不要重新规划整个行程、不要改变原方案的整体框架】。\n"
            + "输出:细化的当日时间表(具体到小时)+ 各段交通/路程安排(含换乘提示)。\n"
            + "要求:留足用餐时间(每餐至少1小时)和景点游玩时间(每处至少2小时),行程现实合理。";

    private final TaskStateManager taskManager;
    private final ClarifyService clarifyService;
    private final PlannerService plannerService;
    private final ExecutorService executorService;
    private final LlmService llmService;
    private final ConversationService conversationService;

    public AgentRouter(TaskStateManager taskManager, ClarifyService clarifyService,
                       PlannerService plannerService, ExecutorService executorService,
                       LlmService llmService, ConversationService conversationService) {
        this.taskManager = taskManager;
        this.clarifyService = clarifyService;
        this.plannerService = plannerService;
        this.executorService = executorService;
        this.llmService = llmService;
        this.conversationService = conversationService;
    }

    /** 是否由 Agent 接管:澄清回复、对已有方案的细化追问、或规划类目标 */
    public boolean shouldHandle(String userId, String text) {
        TaskState state = taskManager.get(userId);
        if (state != null && state.getPhase() == TaskState.Phase.CLARIFYING) {
            return true;
        }
        if (state != null && state.getPhase() == TaskState.Phase.DONE && isRefineRequest(text, state)) {
            return true;
        }
        return isPlanGoal(text);
    }

    /** 判断是否为对已有方案的细化追问(而非新规划目标):已有最终方案且消息含细化词 */
    private boolean isRefineRequest(String text, TaskState state) {
        String finalPlan = state == null ? null : state.getResult("final");
        if (finalPlan == null || finalPlan.isBlank()) {
            return false;
        }
        return text.contains("详细") || text.contains("细化") || text.contains("更详细")
                || text.contains("补充") || text.contains("怎么安排") || text.contains("如何安排")
                || text.contains("更具体") || text.contains("具体") || text.contains("Day") || text.contains("day");
    }

    /** 处理结果:立即回复的提示文本 + 可选的异步执行(返回最终方案) */
    public record AgentResponse(String immediateReply, java.util.function.Supplier<String> asyncPlan) {
    }

    /** 处理消息(在异步线程执行):先给即时反馈,执行过程通过 progress 分步反馈,完成后返回精简概览 */
    public AgentResponse handle(String userId, String text, java.util.function.Consumer<String> progress) {
        TaskState state = taskManager.get(userId);
        // 已有完成的方案 + 细化意图:基于原方案细化,不重新规划
        if (state != null && state.getPhase() == TaskState.Phase.DONE && isRefineRequest(text, state)) {
            String finalPlan = state.getResult("final");
            return new AgentResponse("好的,正在为你细化安排,请稍候...", () -> {
                String detail = llmService.ask(REFINE_SYSTEM,
                        "原方案:\n" + finalPlan + "\n\n用户要求细化的部分:\n" + text, false);
                return detail == null || detail.isBlank() ? "抱歉,细化失败,请稍后再试。" : detail;
            });
        }
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
            // 保存到对话记忆,后续 LLM 可引用该方案
            saveToConversation(userId, state.getGoal(), full);
            String summary = llmService.ask(SUMMARY_SYSTEM, full);
            if (summary == null || summary.isBlank()) {
                summary = full;
            }
            return summary + "\n\n回复\"把方案发到 邮箱\"获取完整方案,回复\"生成PDF\"获取打印版。";
        });
    }

    /** 把 Agent 任务与最终方案写入对话记忆,使后续对话可引用 */
    private void saveToConversation(String userId, String goal, String finalDoc) {
        try {
            conversationService.addMessage(userId,
                    com.alibaba.dashscope.common.Message.builder()
                            .role(com.alibaba.dashscope.common.Role.USER.getValue())
                            .content("[Agent任务] " + goal).build());
            conversationService.addMessage(userId,
                    com.alibaba.dashscope.common.Message.builder()
                            .role(com.alibaba.dashscope.common.Role.ASSISTANT.getValue())
                            .content(finalDoc).build());
            log.info("Agent 方案已写入对话记忆,userId = {}", userId);
        } catch (Exception e) {
            log.error("保存 Agent 对话失败,userId = {}", userId, e);
        }
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
