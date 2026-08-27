package com.luciano.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.luciano.llm.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 引导式澄清服务。
 * 第一步:基于用户目标,由 LLM 生成"玩法分类清单 + 澄清问题(带默认值)",让用户选择/确认;
 * 第二步:解析用户回复,LLM 提取画像(预算/偏好/人数/补充)写入 TaskState;
 * 画像完整后任务进入 EXECUTING,交给后续规划器。
 * 用户回复"看着办/随便"等跳过词时,直接采用默认画像,不阻塞流程。
 */
@Component
public class ClarifyService {

    private static final Logger log = LoggerFactory.getLogger(ClarifyService.class);

    private static final String CLARIFY_SYSTEM = "你是旅游规划助手。请针对用户的出行目标输出两段内容:\n"
            + "第一段【玩法推荐】列出3-5条该城市热门玩法/线路分类,每条带简短说明;\n"
            + "第二段【请确认】一次问清四项:预算档位、选择哪条线路、出行人数、其他需求,每项给出默认值。\n"
            + "整体控制在150字内,直接给内容,不要客套话。";

    private static final String EXTRACT_SYSTEM = "从用户回复中提取出行画像,只输出一个JSON对象,格式:\n"
            + "{\"budget\":\"预算档位或金额\",\"preference\":\"选择的线路或偏好\",\"persons\":\"人数\",\"extra\":\"补充需求\"}\n"
            + "未提到的字段填空字符串,不要输出其他任何内容。";

    /** 跳过词:用户不指定细节,直接按默认画像规划 */
    private static final String[] SKIP_WORDS = {"看着办", "随便", "都行", "你决定", "跳过", "你安排", "你看着"};

    private final LlmService llmService;
    private final TaskStateManager taskManager;

    public ClarifyService(LlmService llmService, TaskStateManager taskManager) {
        this.llmService = llmService;
        this.taskManager = taskManager;
    }

    /**
     * 启动一个新任务并生成引导式澄清,返回要展示给用户的引导文本。
     */
    public String start(String userId, String goal) {
        TaskState state = taskManager.start(userId, goal);
        state.setPhase(TaskState.Phase.CLARIFYING);
        String guide = llmService.ask(CLARIFY_SYSTEM, "用户目标:" + goal);
        if (guide == null || guide.isBlank()) {
            guide = "好的,我来为你规划。请确认:预算档位?(默认人均500/天)、想选哪种玩法?、出行人数?(默认2人)、有其他需求吗?回复\"你看着办\"可按默认规划。";
        }
        state.setResult("guide", guide);
        log.info("任务 {} 已启动,等待澄清,userId = {}", state.getGoal(), userId);
        return guide;
    }

    /**
     * 解析用户澄清回复写入画像。
     *
     * @return true 表示画像已完整、任务进入 EXECUTING;false 表示还需补充
     */
    public boolean parseReply(String userId, String reply) {
        TaskState state = taskManager.get(userId);
        if (state == null || state.getPhase() != TaskState.Phase.CLARIFYING) {
            return false;
        }
        // 跳过词:直接用默认画像,不阻塞
        if (isSkipReply(reply)) {
            applyDefaults(state);
            state.setPhase(TaskState.Phase.EXECUTING);
            return true;
        }
        String json = llmService.ask(EXTRACT_SYSTEM, "用户回复:" + reply);
        Map<String, String> profile = parseProfileJson(json);
        if (profile.isEmpty()) {
            // 完全没提取到画像信息(如用户闲聊而非回答画像):不应用默认,提示用户明确画像,避免误触发规划
            log.info("画像提取为空,提示用户明确画像,userId = {}", userId);
            return false;
        }
        // 只覆盖非空字段:用户分次补充画像时,已填写的值不能被 LLM 提取的空串覆盖丢失
        profile.forEach((k, v) -> {
            if (v != null && !v.isBlank()) {
                state.setProfile(k, v);
            }
        });
        boolean complete = isComplete(state);
        if (complete) {
            state.setPhase(TaskState.Phase.EXECUTING);
        }
        return complete;
    }

    /** 画像中仍缺失的字段中文名(用于提示用户继续补充),不在澄清阶段返回空 */
    public List<String> missingFields(String userId) {
        TaskState state = taskManager.get(userId);
        if (state == null || state.getPhase() != TaskState.Phase.CLARIFYING) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        if (!has(state, "budget")) {
            missing.add("预算");
        }
        if (!has(state, "preference")) {
            missing.add("游玩偏好");
        }
        if (!has(state, "persons")) {
            missing.add("出行人数");
        }
        return missing;
    }

    private boolean isSkipReply(String reply) {
        for (String w : SKIP_WORDS) {
            if (reply.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private void applyDefaults(TaskState state) {
        state.setProfile("budget", "人均500/天");
        state.setProfile("preference", "观光为主");
        state.setProfile("persons", "2人");
        state.setProfile("extra", "无");
    }

    private boolean isComplete(TaskState state) {
        return has(state, "budget") && has(state, "preference") && has(state, "persons");
    }

    private boolean has(TaskState state, String key) {
        String v = state.getProfile(key);
        return v != null && !v.isBlank();
    }

    /** 解析 LLM 返回的画像 JSON,失败返回空 Map */
    private Map<String, String> parseProfileJson(String json) {
        Map<String, String> profile = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return profile;
        }
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            profile.put("budget", getText(obj, "budget"));
            profile.put("preference", getText(obj, "preference"));
            profile.put("persons", getText(obj, "persons"));
            profile.put("extra", getText(obj, "extra"));
        } catch (Exception e) {
            log.warn("解析画像 JSON 失败: {}", json);
        }
        return profile;
    }

    private String getText(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
    }
}
