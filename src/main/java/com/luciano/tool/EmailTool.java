package com.luciano.tool;

import com.google.gson.JsonObject;
import com.luciano.mail.MailService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * 发送邮件工具。
 * 通过 JSON Schema 向大模型描述函数签名:工具名 send_email,参数 to(收件人)、subject(主题)、content(正文)。
 */
@Component
public class EmailTool {

    private final MailService mailService;
    private final ToolRegistry registry;

    /** 每用户每日邮件发送上限,防止滥用(如批量垃圾邮件) */
    private static final int MAX_DAILY = 20;

    private static final java.util.Map<String, java.time.LocalDate> LAST_SEND_DATE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, Integer> DAILY_COUNT = new java.util.concurrent.ConcurrentHashMap<>();

    public EmailTool(MailService mailService, ToolRegistry registry) {
        this.mailService = mailService;
        this.registry = registry;
    }

    /** 按用户做每日发送上限检查(跨天重置) */
    private boolean allowSend(String userId) {
        String key = userId == null ? "anonymous" : userId;
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDate last = LAST_SEND_DATE.get(key);
        if (last == null || !last.equals(today)) {
            LAST_SEND_DATE.put(key, today);
            DAILY_COUNT.put(key, 1);
            return true;
        }
        int count = DAILY_COUNT.merge(key, 1, Integer::sum);
        return count <= MAX_DAILY;
    }

    @PostConstruct
    public void init() {
        registry.register(new ToolDefinition(
                "send_email",
                "发送一封邮件到指定邮箱。用户要求发邮件、发到邮箱、把内容发到某某邮箱时调用。需要收件人邮箱、主题和正文三个参数。",
                emailSchema(),
                arguments -> {
                    String to = getString(arguments, "to", null);
                    String subject = getString(arguments, "subject", null);
                    String content = getString(arguments, "content", null);
                    if (to == null || to.isBlank()) {
                        return "错误:缺少收件人邮箱参数 to。";
                    }
                    if (subject == null || subject.isBlank()) {
                        return "错误:缺少邮件主题参数 subject。";
                    }
                    if (content == null || content.isBlank()) {
                        return "错误:缺少邮件正文参数 content。";
                    }
                    if (!allowSend(ImageContext.getCurrentUserId())) {
                        return "错误:今日发送邮件已达上限(" + MAX_DAILY + "封),请明日再试。";
                    }
                    String err = mailService.sendText(to, subject, content);
                    return err == null
                            ? "邮件已成功发送到 " + to + "。请告知用户邮件已发出,并简要说明主题。"
                            : err;
                }));
    }

    /** 构造 JSON Schema 描述 send_email 的参数 */
    private JsonObject emailSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();
        properties.add("to", strProp("收件人邮箱地址,如 example@qq.com"));
        properties.add("subject", strProp("邮件主题"));
        properties.add("content", strProp("邮件正文内容"));
        schema.add("properties", properties);

        com.google.gson.JsonArray required = new com.google.gson.JsonArray();
        required.add("to");
        required.add("subject");
        required.add("content");
        schema.add("required", required);
        return schema;
    }

    private JsonObject strProp(String desc) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "string");
        obj.addProperty("description", desc);
        return obj;
    }

    private String getString(JsonObject obj, String key, String defaultVal) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultVal;
        }
        return obj.get(key).getAsString();
    }
}
