package com.luciano.weather;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.luciano.config.WeatherProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * 心知天气服务。
 * 调用 https://api.seniverse.com/v3/weather/now.json 获取指定城市天气实况。
 */
@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

    private final WeatherProperties properties;
    private final RestClient restClient;

    public WeatherService(WeatherProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .baseUrl(properties.getApiUrl())
                .build();
    }

    /**
     * 获取指定城市的实时天气。
     *
     * @param location 城市名(中文或拼音,如"北京"或"beijing");为空时使用默认城市
     * @return 格式化后的天气描述;失败或未配置 Key 时返回错误提示
     */
    public String getWeatherNow(String location) {
        String apiKey = properties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return "天气功能未配置,请联系管理员在 application-local.properties 中配置 weather.api-key。";
        }
        String loc = (location == null || location.isBlank()) ? properties.getDefaultLocation() : location;
        try {
            java.net.URI uri = org.springframework.web.util.UriComponentsBuilder
                    .fromUriString(properties.getApiUrl())
                    .queryParam("key", apiKey)
                    .queryParam("location", loc)
                    .queryParam("language", "zh-Hans")
                    .queryParam("unit", "c")
                    .encode()
                    .build()
                    .toUri();
            WeatherResponse resp = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(WeatherResponse.class);
            if (resp == null || resp.results == null || resp.results.isEmpty()) {
                log.warn("天气接口返回为空,location = {}", loc);
                return "抱歉,暂时查不到" + loc + "的天气。";
            }
            WeatherResponse.Result result = resp.results.get(0);
            WeatherResponse.Result.Now now = result.now;
            String cityName = result.location != null ? result.location.name : loc;
            if (now == null) {
                return cityName + "暂无天气数据。";
            }
            return cityName + "当前天气:" + now.text
                    + ",温度 " + now.temperature + "°C"
                    + (now.feelsLike != null ? ",体感 " + now.feelsLike + "°C" : "")
                    + (now.humidity != null ? ",湿度 " + now.humidity + "%" : "")
                    + (now.windDirection != null ? ",风向 " + now.windDirection : "")
                    + (now.windScale != null ? ",风力 " + now.windScale + " 级" : "")
                    + "。";
        } catch (Exception e) {
            log.error("获取天气失败,location = {}", loc, e);
            return "抱歉,查询" + loc + "天气失败,请稍后再试。";
        }
    }

    /** 心知天气响应结构(只解析需要的字段) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WeatherResponse {
        public List<Result> results;

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Result {
            public Location location;
            public Now now;

            @JsonIgnoreProperties(ignoreUnknown = true)
            public static class Location {
                public String name;
            }

            @JsonIgnoreProperties(ignoreUnknown = true)
            public static class Now {
                public String text;
                public String temperature;

                @JsonProperty("feels_like")
                public String feelsLike;

                public String humidity;

                @JsonProperty("wind_direction")
                public String windDirection;

                @JsonProperty("wind_scale")
                public String windScale;
            }
        }
    }
}
