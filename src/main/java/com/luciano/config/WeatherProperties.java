package com.luciano.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 心知天气配置。
 * API Key 来自配置 weather.api-key(位于未提交的 application-local.properties)。
 */
@ConfigurationProperties(prefix = "weather")
public class WeatherProperties {

    /** 心知天气 API Key */
    private String apiKey;

    /** 心知天气 API 地址 */
    private String apiUrl = "https://api.seniverse.com/v3/weather/now.json";

    /** 默认查询城市 */
    private String defaultLocation = "beijing";

    /** 和风天气 API Key(增强天气,可选) */
    private String qweatherKey;

    /** 和风天气专属 API Host,如 mv5n8pkxc2.re.qweatherapi.com */
    private String qweatherHost;

    public String getQweatherKey() {
        return qweatherKey;
    }

    public void setQweatherKey(String qweatherKey) {
        this.qweatherKey = qweatherKey;
    }

    public String getQweatherHost() {
        return qweatherHost;
    }

    public void setQweatherHost(String qweatherHost) {
        this.qweatherHost = qweatherHost;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getDefaultLocation() {
        return defaultLocation;
    }

    public void setDefaultLocation(String defaultLocation) {
        this.defaultLocation = defaultLocation;
    }
}
