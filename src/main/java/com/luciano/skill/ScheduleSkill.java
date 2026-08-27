package com.luciano.skill;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.luciano.agent.ScheduleManager;
import com.luciano.llm.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 日程登记技能。
 * 用户一句话说明时间和事项(如"2月15日下午4点去A地,提醒我"),
 * 由 LLM 解析出"出发时间/标题/地点",写入 ScheduleManager 自动设置提前提醒。
 */
@Component
public class ScheduleSkill implements Skill {

    private static final Logger log = LoggerFactory.getLogger(ScheduleSkill.class);

    private static final String EXTRACT_SYSTEM = "从用户话语中提取日程信息,只输出一个JSON对象:\n"
            + "{\"when\":\"出发时间,格式 yyyy-MM-dd HH:mm\",\"title\":\"事项标题\",\"location\":\"地点\"}\n"
            + "今天日期是 {today}。用户说的\"X月X日\"请解析为当前之后最近的真实日期,补全正确的年份(若用户未指定年份)。"
            + "时间无法确定时 when 给空字符串。不要输出其他内容。";

    private final LlmService llmService;
    private final ScheduleManager scheduleManager;

    public ScheduleSkill(LlmService llmService, ScheduleManager scheduleManager) {
        this.llmService = llmService;
        this.scheduleManager = scheduleManager;
    }

    @Override
    public String name() {
        return "schedule";
    }

    @Override
    public boolean match(String text) {
        return text.contains("提醒我") || text.contains("帮我记日程") || text.contains("添加日程")
                || text.contains("日程提醒") || text.contains("几点去");
    }

    @Override
    public String execute(String userId, String text) {
        String system = EXTRACT_SYSTEM.replace("{today}", java.time.LocalDate.now().toString());
        String json = llmService.ask(system, "用户说:" + text);
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            String when = getText(obj, "when");
            String title = getText(obj, "title");
            String location = getText(obj, "location");
            if (when.isEmpty()) {
                return "抱歉,我没解析出具体时间。请说清楚,如:2月15日下午4点去外滩,提醒我。";
            }
            ScheduleManager.ScheduleItem item = scheduleManager.add(userId, title.isEmpty() ? "日程" : title,
                    location, when);
            return "已设置提醒:【" + item.title + "】"
                    + (item.location != null && !item.location.isBlank() ? "@" + item.location : "")
                    + " 出发时间 " + item.when + ",我会提前20分钟提醒你。";
        } catch (Exception e) {
            log.warn("日程解析失败,原文: {}", json);
            return "抱歉,日程解析失败,请重试。";
        }
    }

    private String getText(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
    }
}
