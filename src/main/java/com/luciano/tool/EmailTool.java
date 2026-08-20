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

    public EmailTool(MailService mailService, ToolRegistry registry) {
        this.mailService = mailService;
        this.registry = registry;
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
