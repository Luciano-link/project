package com.luciano.agent;

import com.luciano.agent.TaskState.StepResult;
import com.luciano.agent.TaskState.SubTask;
import com.luciano.llm.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 执行层:按规划逐步完成子任务,每步可调用工具/搜索。
 */
@Service
public class AgentExecutorService {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutorService.class);

    private static final String EXECUTOR_SYSTEM = """
            你是出行规划 Agent 的执行器,正在完成一个子任务。
            规则:
            1) 只完成当前子任务,输出简洁、可引用的中文结果;
            2) toolHint 为 get_weather 时必须调用 get_weather;
            3) toolHint 为 search 时可使用联网搜索获取景点/交通等实时信息;
            4) 不要重复已完成步骤的全部内容;
            5) 景点/餐厅必须对应用户目标城市,禁止套用其他城市;
            6) 无订房/订票接口时不要声称已预订成功。
            """;

    private final LlmService llmService;

    public AgentExecutorService(LlmService llmService) {
        this.llmService = llmService;
    }

    public StepResult executeStep(String userId, String goal, SubTask subTask,
                                  List<StepResult> previous, String knowledge) {
        String userMessage = buildStepPrompt(goal, subTask, previous);
        log.info("[Agent] 执行子任务 {} - {}", subTask.id(), subTask.title());
        LlmService.ChatResult result = llmService.agentStep(userId, EXECUTOR_SYSTEM, userMessage, knowledge);
        String output = result.reply();
        if (output == null || output.isBlank()) {
            output = "本子任务未生成有效结果,请结合其他步骤综合判断。";
        }
        return new StepResult(subTask.id(), subTask.title(), output);
    }

    private String buildStepPrompt(String goal, SubTask subTask, List<StepResult> previous) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户目标:").append(goal).append('\n');
        sb.append("当前子任务 #").append(subTask.id()).append(": ").append(subTask.title()).append('\n');
        sb.append("子任务说明:").append(subTask.description()).append('\n');
        sb.append("建议工具:").append(subTask.toolHint()).append('\n');
        if (!previous.isEmpty()) {
            sb.append("\n已完成步骤结果:\n");
            for (StepResult r : previous) {
                sb.append("- [").append(r.id()).append("] ").append(r.title()).append(": ")
                        .append(truncate(r.output(), 500)).append('\n');
            }
        }
        sb.append("\n请完成当前子任务并输出结果。");
        return sb.toString();
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
