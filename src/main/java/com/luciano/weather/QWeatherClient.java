package com.luciano.weather;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.luciano.config.WeatherProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * 和风天气客户端(增强版天气)。
 * 使用专属 API Host + API KEY 认证(请求头 X-QW-Api-Key)。
 * 提供实时天气 + 空气质量 + 3天预报。
 * 城市定位:使用内置常用城市 ID 映射表(专属 Host 不含 geo 接口);
 * 未配置 Key 或城市不在映射表时返回 null,由调用方降级到心知天气。
 */
@Component
public class QWeatherClient {

    private static final Logger log = LoggerFactory.getLogger(QWeatherClient.class);

    /** 常用城市 ID 映射(和风城市ID) */
    private static final Map<String, String> CITY_IDS = Map.ofEntries(
            Map.entry("北京", "101010100"),
            Map.entry("上海", "101020100"),
            Map.entry("广州", "101280101"),
            Map.entry("深圳", "101280601"),
            Map.entry("南京", "101190101"),
            Map.entry("杭州", "101210101"),
            Map.entry("成都", "101270101"),
            Map.entry("重庆", "101040100"),
            Map.entry("武汉", "101200101"),
            Map.entry("西安", "101110101"),
            Map.entry("天津", "101030100"),
            Map.entry("苏州", "101190401"),
            Map.entry("beijing", "101010100"),
            Map.entry("shanghai", "101020100"),
            Map.entry("guangzhou", "101280101"),
            Map.entry("shenzhen", "101280601"),
            Map.entry("nanjing", "101190101")
    );

    private final WeatherProperties properties;
    private final RestClient restClient;

    public QWeatherClient(WeatherProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder().build();
    }

    /** 是否已配置和风 Key 和 Host */
    public boolean isEnabled() {
        return properties.getQweatherKey() != null && !properties.getQweatherKey().isBlank()
                && properties.getQweatherHost() != null && !properties.getQweatherHost().isBlank();
    }

    /**
     * 获取增强天气文本。
     *
     * @param location 城市名(中文或拼音)
     * @return 增强天气描述;未配置 Key 或城市不在映射表返回 null(调用方降级)
     */
    public String getEnhancedWeather(String location) {
        if (!isEnabled()) {
            return null;
        }
        try {
            String loc = (location == null || location.isBlank()) ? properties.getDefaultLocation() : location;
            String locationId = CITY_IDS.get(loc.trim());
            if (locationId == null) {
                log.info("城市 {} 不在内置映射表,降级到心知天气", loc);
                return null;
            }
            Now now = fetchNow(locationId);
            Air air = fetchAir(locationId);
            List<Daily> daily = fetchDaily(locationId);
            if (now == null) {
                return null;
            }
            return buildText(loc, now, air, daily);
        } catch (Exception e) {
            log.error("和风天气查询失败,location = {}", location, e);
            return null;
        }
    }

    private Now fetchNow(String id) {
        String url = properties.getQweatherHost() + "/v7/weather/now?location=" + id;
        NowResponse resp = get(url, NowResponse.class);
        return resp != null && "200".equals(resp.code) ? resp.now : null;
    }

    private Air fetchAir(String id) {
        try {
            String url = properties.getQweatherHost() + "/v7/air/now?location=" + id;
            AirResponse resp = get(url, AirResponse.class);
            return resp != null && "200".equals(resp.code) ? resp.now : null;
        } catch (Exception e) {
            // 空气质量接口可能已弃用或订阅不含,失败不阻断天气主流程
            log.debug("空气质量查询失败(忽略): {}", e.getMessage());
            return null;
        }
    }

    private List<Daily> fetchDaily(String id) {
        String url = properties.getQweatherHost() + "/v7/weather/3d?location=" + id;
        DailyResponse resp = get(url, DailyResponse.class);
        return resp != null && "200".equals(resp.code) ? resp.daily : List.of();
    }

    /** 带 API KEY 认证的 GET 请求 */
    private <T> T get(String url, Class<T> type) {
        return restClient.get()
                .uri(url)
                .header("X-QW-Api-Key", properties.getQweatherKey())
                .retrieve()
                .body(type);
    }

    private String buildText(String city, Now now, Air air, List<Daily> daily) {
        StringBuilder sb = new StringBuilder(city + "当前天气:");
        sb.append(now.text).append(",温度 ").append(now.temp).append("°C")
                .append(",体感 ").append(now.feelsLike).append("°C")
                .append(",湿度 ").append(now.humidity).append("%")
                .append(",风向 ").append(now.windDir).append(" ").append(now.windScale).append("级");
        if (air != null) {
            sb.append(",空气质量:").append(air.category == null ? "未知" : air.category)
                    .append("(AQI ").append(air.aqi).append(")");
        }
        if (daily != null && !daily.isEmpty()) {
            Daily today = daily.get(0);
            sb.append(",今日:").append(today.textDay)
                    .append(" ").append(today.tempMin).append("~").append(today.tempMax).append("°C");
            if (today.sunrise != null && today.sunset != null) {
                sb.append(",日出").append(today.sunrise).append(" 日落").append(today.sunset);
            }
            if (daily.size() > 1) {
                sb.append(",明日:").append(daily.get(1).textDay)
                        .append(" ").append(daily.get(1).tempMin).append("~").append(daily.get(1).tempMax).append("°C");
            }
        }
        sb.append("。");
        return sb.toString();
    }

    // ============ 和风天气响应结构 ============

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NowResponse {
        public String code;
        public Now now;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Now {
        public String text;
        public String temp;

        @JsonProperty("feelsLike")
        public String feelsLike;

        public String humidity;

        @JsonProperty("windDir")
        public String windDir;

        @JsonProperty("windScale")
        public String windScale;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AirResponse {
        public String code;
        public Air now;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Air {
        public String aqi;
        public String category;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DailyResponse {
        public String code;
        public List<Daily> daily;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Daily {
        public String textDay;

        @JsonProperty("tempMin")
        public String tempMin;

        @JsonProperty("tempMax")
        public String tempMax;

        public String sunrise;

        public String sunset;
    }
}
