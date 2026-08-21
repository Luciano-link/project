package com.luciano.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 安全配置。
 * 鉴权 token 位于未提交的 application-local.properties,绝不入库。
 */
@ConfigurationProperties(prefix = "security")
public class SecurityProperties {

    /** 调试接口鉴权 token(请求头 X-Auth-Token) */
    private String apiToken;

    public String getApiToken() {
        return apiToken;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
    }
}
