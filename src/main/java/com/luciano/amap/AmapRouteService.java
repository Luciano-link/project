package com.luciano.amap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;

/**
 * 高德地图路线服务。
 * 封装高德 Web 服务 API:地理编码(地点名→经纬度)+ 公交/地铁路径规划。
 * 需要高德开放平台 Web 服务 Key(配置 amap.key,位于未提交的 local 配置)。
 * 实时路线能力:比静态知识库更可信,用于方案中的交通建议与主动提醒。
 */
@Component
public class AmapRouteService {

    private static final Logger log = LoggerFactory.getLogger(AmapRouteService.class);

    private static final String GEO_URL = "https://restapi.amap.com/v3/geocode/geo";
    private static final String TRANSIT_URL = "https://restapi.amap.com/v3/direction/transit/integrated";

    @Value("${amap.key:}")
    private String amapKey;

    private final RestClient restClient = RestClient.builder().build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 查询 from → to 的公交/地铁路线(无城市限定),返回可读摘要;失败或未配置 Key 返回错误提示 */
    public String route(String from, String to) {
        return route(from, to, null);
    }

    /**
     * 查询 from → to 的公交/地铁路线,返回可读摘要。
     *
     * @param city 城市名(如 上海),用于限定地理编码范围,解决同名地点歧义;可为 null
     */
    public String route(String from, String to, String city) {
        if (amapKey == null || amapKey.isBlank()) {
            return "错误:未配置高德地图 Key,请到高德开放平台申请后配置 amap.key。";
        }
        try {
            String[] fromLngLat = geocode(from, city);
            String[] toLngLat = geocode(to, city);
            String origin = fromLngLat[0] + "," + fromLngLat[1];
            String destination = toLngLat[0] + "," + toLngLat[1];
            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(TRANSIT_URL)
                    .queryParam("key", amapKey)
                    .queryParam("origin", origin)
                    .queryParam("destination", destination);
            if (city != null && !city.isBlank()) {
                builder.queryParam("city", city).queryParam("cityd", city);
            }
            URI uri = builder.build().encode().toUri();
            String body = restClient.get().uri(uri).retrieve().body(String.class);
            return parseTransit(from, to, body);
        } catch (Exception e) {
            log.error("路线查询失败,from = {}, to = {}, city = {}", from, to, city, e);
            return "错误:路线查询失败:" + e.getMessage();
        }
    }

    /** 地理编码:地点名转经纬度,返回 [经度, 纬度];city 可选,用于限定同名地点 */
    private String[] geocode(String address, String city) throws IOException {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(GEO_URL)
                .queryParam("key", amapKey)
                .queryParam("address", address);
        if (city != null && !city.isBlank()) {
            builder.queryParam("city", city);
        }
        URI uri = builder.build().encode().toUri();
        String body = restClient.get().uri(uri).retrieve().body(String.class);
        JsonNode node = objectMapper.readTree(body);
        if (!"1".equals(node.path("status").asText())) {
            throw new IOException("地理编码失败: " + body);
        }
        JsonNode geocodes = node.path("geocodes");
        if (geocodes.isEmpty()) {
            throw new IOException("未找到地点: " + address);
        }
        return geocodes.get(0).path("location").asText().split(",");
    }

    /** 解析公交路径规划结果,取第一套方案拼接摘要 */
    private String parseTransit(String from, String to, String body) throws IOException {        JsonNode node = objectMapper.readTree(body);
        if (!"1".equals(node.path("status").asText())) {
            throw new IOException("路径规划失败: " + body);
        }
        JsonNode transits = node.path("route").path("transits");
        if (!transits.isArray() || transits.isEmpty()) {
            return "从" + from + "到" + to + "暂无公交/地铁方案,建议打车或步行。";
        }
        JsonNode first = transits.get(0);
        JsonNode cost = first.path("cost");
        String duration = cost.path("duration_text").asText("");
        if (duration.isEmpty()) {
            long seconds = cost.path("duration").asLong(0);
            if (seconds > 0) {
                duration = (seconds / 60) + "分钟";
            }
        }
        String distance = first.path("distance").asText("");
        StringBuilder lines = new StringBuilder();
        for (JsonNode segment : first.path("segments")) {
            JsonNode bus = segment.path("bus");
            if (bus.path("buslines").isArray()) {
                for (JsonNode line : bus.path("buslines")) {
                    if (lines.length() > 0) {
                        lines.append("→");
                    }
                    lines.append(line.path("name").asText(""));
                }
            }
        }
        return "从" + from + "到" + to + ":约" + duration
                + ",全程约" + distance + "米"
                + (lines.length() > 0 ? ",路线:" + lines : "") + "。";
    }
}
