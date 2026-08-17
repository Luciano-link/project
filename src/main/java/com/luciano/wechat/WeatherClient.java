package com.luciano.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.zip.GZIPInputStream;

/**
 * 封装和风天气(QWeather)的实时天气与 3 天预报。
 *
 * <p>V4 版本要求:天气接口的 location 参数必须传 LocationID(如 101010100),
 * 不再接受中文城市名;先用 /geo/v2/city/lookup 把城市名解析成 LocationID。
 */
@Component
@PropertySource(value = "classpath:secret.properties", ignoreResourceNotFound = true)
public class WeatherClient {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    @Value("${qweather.api-key:}")
    private String apiKey;

    @Value("${qweather.base-url:https://mj6cdqy77e.re.qweatherapi.com}")
    private String baseUrl;

    /**
     * 查询城市实时天气,返回简短中文句子。
     */
    public String getNow(String city) throws Exception {
        String locationId = resolveLocationId(city);
        JsonNode resp = getJson("/v7/weather/now", locationId);
        JsonNode now = resp.path("now");
        if (now.isMissingNode()) {
            throw new IllegalStateException("未找到天气数据: " + resp);
        }
        String text = now.path("text").asText("未知");
        String temp = now.path("temp").asText("--");
        String feelsLike = now.path("feelsLike").asText("--");
        String humidity = now.path("humidity").asText("--");
        String windDir = now.path("windDir").asText("--");
        String windScale = now.path("windScale").asText("--");
        return String.format("%s当前天气:%s,气温 %s℃(体感 %s℃),相对湿度 %s%%,风向 %s %s级。",
                city, text, temp, feelsLike, humidity, windDir, windScale);
    }

    /**
     * 查询城市 3 天预报,返回文本摘要。
     */
    public String get3d(String city) throws Exception {
        String locationId = resolveLocationId(city);
        JsonNode resp = getJson("/v7/weather/3d", locationId);
        JsonNode daily = resp.path("daily");
        if (!daily.isArray() || daily.size() == 0) {
            throw new IllegalStateException("未找到预报数据: " + resp);
        }
        StringBuilder sb = new StringBuilder(city).append("未来3天预报:\n");
        for (JsonNode day : daily) {
            String date = day.path("fxDate").asText("");
            String textDay = day.path("textDay").asText("--");
            String tempMin = day.path("tempMin").asText("--");
            String tempMax = day.path("tempMax").asText("--");
            sb.append(String.format("%s %s,%s~%s℃\n", date, textDay, tempMin, tempMax));
        }
        return sb.toString().trim();
    }

    /**
     * 用城市名查 LocationID(取第一个匹配结果)。
     */
    private String resolveLocationId(String city) throws Exception {
        JsonNode resp = getJson("/geo/v2/city/lookup", city);
        JsonNode locations = resp.path("location");
        if (locations.isArray() && locations.size() > 0) {
            String id = locations.get(0).path("id").asText();
            if (id != null && !id.isBlank()) {
                return id;
            }
        }
        throw new IllegalStateException("未找到城市「" + city + "」: " + resp);
    }

    /**
     * 调用和风天气接口,返回已校验业务码为 200 的 JSON。
     */
    private JsonNode getJson(String path, String location) throws Exception {
        String encoded = URLEncoder.encode(location, StandardCharsets.UTF_8);
        String url = baseUrl + path + "?location=" + encoded;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Accept-Encoding", "gzip")
                .header("X-QW-Api-Key", apiKey)
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        String body;
        if ("gzip".equalsIgnoreCase(response.headers().firstValue("Content-Encoding").orElse(""))) {
            try (InputStream in = new GZIPInputStream(response.body())) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } else {
            try (InputStream in = response.body()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + ": " + body);
        }
        JsonNode resp = objectMapper.readTree(body);
        String code = resp.path("code").asText();
        if (!"200".equals(code)) {
            throw new IllegalStateException("天气接口业务错误 code=" + code + ": " + resp);
        }
        return resp;
    }
}
