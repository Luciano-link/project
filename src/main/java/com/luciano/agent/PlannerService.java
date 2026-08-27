package com.luciano.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.luciano.agent.TaskState.AgentPlan;
import com.luciano.agent.TaskState.SubTask;
import com.luciano.llm.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规划层:将用户一句目标拆解为 ≥3 个可执行子任务。
 */
@Service
public class PlannerService {

    private static final Logger log = LoggerFactory.getLogger(PlannerService.class);

    private static final String PLANNER_SYSTEM = """
            你是出行规划 Agent 的任务规划器。用户只给最终目标,你要拆解为至少3个、最多6个有序子任务。
            仅输出 JSON,不要 markdown 代码块,格式:
            {"subtasks":[{"id":1,"title":"简短标题","description":"本步要做什么","toolHint":"get_weather|generate_image|send_email|search|none"}]}
            toolHint 说明:get_weather查天气;generate_image生图;send_email发邮件;search查实时信息;none纯推理整理。
            子任务应覆盖:天气/交通住宿/每日行程/餐饮预算/注意事项/最终汇总等不同类型,且彼此不重复。
            """;

    private static final Pattern JSON_BLOCK = Pattern.compile("\\{.*}", Pattern.DOTALL);

    private final LlmService llmService;

    public PlannerService(LlmService llmService) {
        this.llmService = llmService;
    }

    public AgentPlan plan(String goal, String knowledge) {
        String userPrompt = buildUserPrompt(goal, knowledge);
        try {
            String raw = llmService.agentGenerate(PLANNER_SYSTEM, userPrompt);
            AgentPlan parsed = parsePlan(goal, raw);
            if (parsed != null && parsed.subtasks().size() >= 3) {
                log.info("LLM 规划成功,子任务数 = {}", parsed.subtasks().size());
                return parsed;
            }
        } catch (Exception e) {
            log.warn("LLM 规划失败,使用默认模板: {}", e.getMessage());
        }
        return defaultTravelPlan(goal);
    }

    private String buildUserPrompt(String goal, String knowledge) {
        StringBuilder sb = new StringBuilder("用户目标:").append(goal);
        if (knowledge != null && !knowledge.isBlank()) {
            sb.append("\n\n知识库参考:\n").append(knowledge);
        }
        return sb.toString();
    }

    AgentPlan parsePlan(String goal, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String json = raw.trim();
        Matcher m = JSON_BLOCK.matcher(json);
        if (m.find()) {
            json = m.group();
        }
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("subtasks") || !root.get("subtasks").isJsonArray()) {
            return null;
        }
        JsonArray arr = root.getAsJsonArray("subtasks");
        List<SubTask> tasks = new ArrayList<>();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            int id = o.has("id") ? o.get("id").getAsInt() : tasks.size() + 1;
            String title = textOrDefault(o, "title", "子任务" + id);
            String desc = textOrDefault(o, "description", title);
            String tool = textOrDefault(o, "toolHint", "none");
            tasks.add(new SubTask(id, title, desc, tool));
        }
        return tasks.isEmpty() ? null : new AgentPlan(goal, tasks);
    }

    private String textOrDefault(JsonObject o, String key, String defaultVal) {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            return defaultVal;
        }
        return o.get(key).getAsString();
    }

    /** LLM 不可用或解析失败时的默认出行规划模板 */
    AgentPlan defaultTravelPlan(String goal) {
        List<SubTask> tasks = List.of(
                new SubTask(1, "查询目的地天气", "查询目标城市未来几天天气,给出穿衣建议", "get_weather"),
                new SubTask(2, "规划交通与住宿", "建议往返交通方式、抵达换乘与酒店区位", "search"),
                new SubTask(3, "规划每日行程", "按天安排景点路线,控制打卡点数量", "search"),
                new SubTask(4, "餐饮与预算", "推荐当地餐饮并粗估人均花费", "none"),
                new SubTask(5, "汇总完整方案", "整合前述信息输出结构化出行方案成品", "none")
        );
        log.info("使用默认出行规划模板,goal = {}", goal);
        return new AgentPlan(goal, tasks);
    }
}
