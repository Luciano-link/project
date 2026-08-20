package com.luciano.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 邮件发送配置(QQ 邮箱 SMTP)。
 * 授权码等敏感信息位于未提交的 application-local.properties,绝不入库。
 */
@ConfigurationProperties(prefix = "mail")
public class MailProperties {

    /** SMTP 主机 */
    private String host = "smtp.qq.com";

    /** SMTP 端口 */
    private int port = 465;

    /** 发件人邮箱(需与认证账号一致) */
    private String from;

    /** 发件人显示名 */
    private String fromName = "微信Bot";

    /** SMTP 授权码(QQ邮箱设置里生成,非登录密码) */
    private String authCode;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getFromName() {
        return fromName;
    }

    public void setFromName(String fromName) {
        this.fromName = fromName;
    }

    public String getAuthCode() {
        return authCode;
    }

    public void setAuthCode(String authCode) {
        this.authCode = authCode;
    }
}
