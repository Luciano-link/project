package com.luciano.mail;

import com.luciano.config.MailProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.Properties;

/**
 * 邮件发送服务(QQ 邮箱 SMTP)。
 * 授权码来自配置 mail.auth-code,只读取不落盘。
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final MailProperties properties;
    private JavaMailSender mailSender;

    public MailService(MailProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        String authCode = properties.getAuthCode();
        if (authCode == null || authCode.isBlank() || properties.getFrom() == null || properties.getFrom().isBlank()) {
            log.warn("未配置 mail.auth-code / mail.from,邮件发送不可用。请在 application-local.properties 中配置");
            return;
        }
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(properties.getHost());
        sender.setPort(properties.getPort());
        sender.setUsername(properties.getFrom());
        sender.setPassword(authCode);
        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.ssl.enable", "true");
        props.put("mail.smtp.ssl.trust", properties.getHost());
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.connectiontimeout", "10000");
        // 关闭 debug,避免日志泄露账号信息
        props.put("mail.debug", "false");
        this.mailSender = sender;
        log.info("邮件服务初始化完成,from = {}", properties.getFrom());
    }

    /**
     * 发送普通文本邮件。
     *
     * @param to      收件人邮箱
     * @param subject 主题
     * @param content 正文
     * @return 成功返回 null;失败返回错误信息
     */
    public String sendText(String to, String subject, String content) {
        if (mailSender == null) {
            return "错误:邮件功能未配置,请联系管理员配置 mail.auth-code 和 mail.from。";
        }
        if (to == null || to.isBlank() || !to.contains("@")) {
            return "错误:收件人邮箱不合法: " + to;
        }
        try {
            jakarta.mail.internet.MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(properties.getFrom(), properties.getFromName());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(content, false);
            mailSender.send(message);
            log.info("邮件已发送: to={}, subject={}", to, subject);
            return null;
        } catch (Exception e) {
            log.error("邮件发送失败, to={}", to, e);
            return "错误:邮件发送失败: " + e.getMessage();
        }
    }
}
