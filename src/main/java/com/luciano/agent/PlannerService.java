package com.luciano.agent;

import com.luciano.llm.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 规划器:基于用户目标 + 画像,将长任务拆解为可执行的子任务清单。
 * 拆解结果存入 TaskState.plan,由执行器逐步消费。
 * 子任务类型限定为:天气查询 / 信息检索 / 方案生成(与执行器能力映射一致)。
 */
@Component
public class PlannerService {

    private static final Logger log = LoggerFactory.getLogger(PlannerService.class);

    private static final String PLAN_SYSTEM = "你是旅行规划 Agent 的执行计划器。把用户目标拆解为可执行的子任务清单,每行一条,格式:\n"
            + "步骤N:动作(关键词) - 简短说明\n"
            + "动作必须使用且只能使用以下关键词之一:天气查询、信息检索、方案生成。\n"
            + "要求:必须按顺序包含三条且只包含三条:步骤1:天气查询、步骤2:信息检索、步骤3:方案生成,每条带一句简短说明(如查询哪里的天气、检索什么信息、生成什么方案)。\n"
            + "直接输出清单,不要输出其他内容。";

    private final LlmService llmService;

    public PlannerService(LlmService llmService) {
        this.llmService = llmService;
    }

    /** 生成子任务清单并存入 TaskState,返回计划文本 */
    public String plan(TaskState state) {
        String profileText = buildProfileText(state);
        String prompt = "用户目标:" + state.getGoal() + "\n用户画像:" + profileText;
        String plan = llmService.ask(PLAN_SYSTEM, prompt);
        if (plan == null || plan.isBlank()) {
            // LLM 失败时按默认三步兜底,保证链路可继续
            plan = "步骤1:天气查询 - 查询目的地天气\n步骤2:信息检索 - 检索景点美食住宿\n步骤3:方案生成 - 生成完整出行方案";
        }
        state.setResult("plan", plan);
        log.info("任务拆解完成: {}", plan.replace("\n", " | "));
        return plan;
    }

    private String buildProfileText(TaskState state) {
        StringBuilder sb = new StringBuilder();
        state.getProfile().forEach((k, v) -> sb.append(k).append(": ").append(v).append("; "));
        return sb.toString().isEmpty() ? "未指定" : sb.toString();
    }
}
