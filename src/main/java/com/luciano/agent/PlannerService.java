package com.luciano.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.luciano.llm.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 规划器:基于用户目标 + 画像 + 参考知识,拆解为结构化子任务列表(带 toolHint)。
 * 子任务写入 TaskState.subtasks,由执行器按 toolHint 分派执行。
 */
@Component
public class PlannerService {

    private static final Logger log = LoggerFactory.getLogger(PlannerService.class);

    private static final String PLAN_SYSTEM = "你是出行规划 Agent 的规划器。根据用户目标、画像和参考知识,拆解子任务,只输出一个 JSON 数组:\n"
            + "[{\"title\":\"任务标题\",\"description\":\"做什么\",\"toolHint\":\"工具提示\"},...]\n"
            + "toolHint 只能是:get_weather(查目的地天气)、search(检索景点/美食/住宿)、plan(生成完整出行方案)。\n"
            + "要求:必须且只能输出 3 个子任务,按顺序:①get_weather ②search ③plan。\n"
            + "只输出 JSON,不要输出任何其他文字。";

    private final LlmService llmService;

    public PlannerService(LlmService llmService) {
        this.llmService = llmService;
    }

    /** 生成结构化子任务清单并写入 TaskState,返回提示文本 */
    public String plan(TaskState state) {
        String profileText = buildProfileText(state);
        String knowledge = state.getResult("knowledge");
        String prompt = "用户目标:" + state.getGoal()
                + "\n用户画像:" + profileText
                + "\n参考知识:" + (knowledge == null || knowledge.isBlank() ? "无" : knowledge);
        String json = llmService.ask(PLAN_SYSTEM, prompt, false);
        List<TaskState.SubTask> subtasks = parseSubtasks(json);
        if (subtasks.isEmpty()) {
            log.warn("子任务解析失败,使用默认拆解,userId = {}", state.getUserId());
            subtasks = defaultSubtasks(state.getResult("city"));
        }
        state.setSubtasks(subtasks);
        log.info("任务拆解完成,{} 个子任务: {}", subtasks.size(),
                subtasks.stream().map(TaskState.SubTask::title).toList());
        return "已拆解 " + subtasks.size() + " 个子任务";
    }

    /** 解析 LLM 返回的子任务 JSON 数组 */
    private List<TaskState.SubTask> parseSubtasks(String json) {
        List<TaskState.SubTask> result = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        try {
            String s = json.trim();
            int start = s.indexOf('[');
            int end = s.lastIndexOf(']');
            if (start < 0 || end <= start) {
                return result;
            }
            JsonArray arr = JsonParser.parseString(s.substring(start, end + 1)).getAsJsonArray();
            int id = 1;
            for (var element : arr) {
                JsonObject obj = element.getAsJsonObject();
                String title = getText(obj, "title");
                String description = getText(obj, "description");
                String toolHint = getText(obj, "toolHint");
                if (title.isEmpty()) {
                    continue;
                }
                result.add(new TaskState.SubTask(id++, title, description, toolHint));
            }
        } catch (Exception e) {
            log.warn("解析子任务 JSON 失败: {}", json);
        }
        return result;
    }

    /** 默认拆解兜底(LLM 失败时) */
    private List<TaskState.SubTask> defaultSubtasks(String city) {
        String c = city == null || city.isBlank() ? "目的地" : city;
        List<TaskState.SubTask> list = new ArrayList<>();
        list.add(new TaskState.SubTask(1, "查询" + c + "天气", "查询目的地实时天气", "get_weather"));
        list.add(new TaskState.SubTask(2, "检索" + c + "景点美食住宿", "检索当地景点、美食、住宿参考信息", "search"));
        list.add(new TaskState.SubTask(3, "生成完整出行方案", "结合天气、参考知识与画像生成完整方案", "plan"));
        return list;
    }

    private String buildProfileText(TaskState state) {
        StringBuilder sb = new StringBuilder();
        state.getProfile().forEach((k, v) -> sb.append(k).append(": ").append(v).append("; "));
        return sb.toString().isEmpty() ? "未指定" : sb.toString();
    }

    private String getText(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
    }
}
