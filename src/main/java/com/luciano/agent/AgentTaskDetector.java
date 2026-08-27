package com.luciano.agent;

/**
 * 识别是否应走自主规划 Agent(长任务),而非普通一问一答。
 */
public final class AgentTaskDetector {

    private AgentTaskDetector() {
    }

    public static boolean isAgentTask(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.contains("出行方案") || text.contains("行程方案") || text.contains("完整方案")
                || text.contains("旅行规划") || text.contains("旅游攻略")
                || text.contains("三日游") || text.contains("三天两夜") || text.contains("3日游")
                || (text.contains("规划") && (text.contains("旅行") || text.contains("旅游") || text.contains("出行")))
                || (text.contains("攻略") && (text.contains("旅行") || text.contains("旅游") || text.contains("出行")));
    }
}
