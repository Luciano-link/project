package com.luciano.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 封装和风天气(QWeather)的实时天气、3 天预报、空气质量与生活指数。
 *
 * <p>V4 版本要求:天气接口的 location 参数必须传 LocationID(如 101010100),
 * 不再接受中文城市名;先用 /geo/v2/city/lookup 把城市名解析成 LocationID。
 *
 * <p>查询方法返回结构化的 {@link Weather} 模型对象,格式化文本由 formatXxx 方法负责;
 * 空气质量/生活指数等附加数据在不可用(未订阅/超限)时自动降级,不影响核心天气回复。
 */
@Component
@PropertySource(value = "classpath:secret.properties", ignoreResourceNotFound = true)
public class WeatherClient {

    private static final Logger log = LoggerFactory.getLogger(WeatherClient.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    @Value("${qweather.api-key:}")
    private String apiKey;

    @Value("${qweather.base-url:https://mj6cdqy77e.re.qweatherapi.com}")
    private String baseUrl;

    /** 生活指数展示顺序与类型(1=运动,2=洗车,5=穿衣,6=紫外线)。 */
    private static final List<String> INDICES_PREFERRED = List.of("5", "1", "6", "2");

    /**
     * 查询城市实时天气,返回结构化对象。
     */
    public Weather.Current getNow(String city) throws Exception {
        String locationId = resolveLocationId(city);
        JsonNode resp = getJson("/v7/weather/now", locationId);
        JsonNode now = resp.path("now");
        if (now.isMissingNode()) {
            throw new IllegalStateException("未找到天气数据: " + resp);
        }
        return new Weather.Current(
                city,
                now.path("text").asText("未知"),
                now.path("temp").asText("--"),
                now.path("feelsLike").asText("--"),
                now.path("humidity").asText("--"),
                now.path("windDir").asText("--"),
                now.path("windScale").asText("--"),
                now.path("windSpeed").asText("--"),
                now.path("precip").asText("--"),
                now.path("pressure").asText("--"),
                now.path("vis").asText("--"));
    }

    /**
     * 查询城市 3 天预报,返回结构化列表。
     */
    public List<Weather.Daily> get3d(String city) throws Exception {
        String locationId = resolveLocationId(city);
        JsonNode resp = getJson("/v7/weather/3d", locationId);
        JsonNode daily = resp.path("daily");
        if (!daily.isArray() || daily.size() == 0) {
            throw new IllegalStateException("未找到预报数据: " + resp);
        }
        List<Weather.Daily> result = new ArrayList<>();
        for (JsonNode day : daily) {
            result.add(new Weather.Daily(
                    day.path("fxDate").asText(""),
                    day.path("textDay").asText("--"),
                    day.path("textNight").asText("--"),
                    day.path("tempMin").asText("--"),
                    day.path("tempMax").asText("--"),
                    day.path("windDirDay").asText("--"),
                    day.path("windScaleDay").asText("--"),
                    day.path("sunrise").asText(""),
                    day.path("sunset").asText(""),
                    day.path("uvIndex").asText("--"),
                    day.path("humidity").asText("--"),
                    day.path("precip").asText("--")));
        }
        return result;
    }

    /**
     * 查询城市明天天气,返回结构化对象。
     */
    public Weather.Daily getTomorrow(String city) throws Exception {
        List<Weather.Daily> days = get3d(city);
        if (days.size() < 2) {
            throw new IllegalStateException("未找到明天预报数据");
        }
        return days.get(1);
    }

    /**
     * 查询城市空气质量实况(AQI)。未订阅或超限时抛出异常,由调用方降级。
     */
    public Weather.Air getAir(String city) throws Exception {
        String locationId = resolveLocationId(city);
        JsonNode resp = getJson("/v7/air/now", locationId);
        JsonNode now = resp.path("now");
        if (now.isMissingNode()) {
            throw new IllegalStateException("未找到空气质量数据: " + resp);
        }
        return new Weather.Air(
                now.path("aqi").asText("--"),
                now.path("category").asText("--"),
                now.path("primary").asText(""),
                now.path("pm2p5").asText("--"),
                now.path("pm10").asText("--"));
    }

    /**
     * 查询城市生活指数(1 天),按 {@link #INDICES_PREFERRED} 的顺序取 穿衣/运动/紫外线/洗车。
     * 未订阅或超限时抛出异常,由调用方降级。
     */
    public List<Weather.IndexItem> getIndices(String city) throws Exception {
        String locationId = resolveLocationId(city);
        JsonNode resp = getJson("/v7/indices/1d", locationId, "type=0");
        JsonNode daily = resp.path("daily");
        if (!daily.isArray() || daily.size() == 0) {
            throw new IllegalStateException("未找到生活指数数据: " + resp);
        }
        Map<String, Weather.IndexItem> byType = new LinkedHashMap<>();
        for (JsonNode item : daily) {
            String type = item.path("type").asText();
            byType.putIfAbsent(type, new Weather.IndexItem(
                    item.path("name").asText(""),
                    item.path("category").asText(""),
                    item.path("text").asText("")));
        }
        List<Weather.IndexItem> result = new ArrayList<>();
        for (String type : INDICES_PREFERRED) {
            Weather.IndexItem it = byType.get(type);
            if (it != null) {
                result.add(it);
            }
        }
        return result;
    }

    /**
     * 组合「今天完整天气」:实时 + 空气质量 + 日出日落/紫外线 + 生活指数。
     * 空气质量/生活指数等附加接口失败时自动跳过,保证核心信息必达。
     */
    public String describeToday(String city, Weather.Current now, Weather.Daily today) {
        StringBuilder sb = new StringBuilder(formatNow(now));
        try {
            sb.append("\n").append(formatAir(getAir(city)));
        } catch (Exception e) {
            log.debug("空气质量获取失败(已降级): {}", e.getMessage());
        }
        sb.append("\n").append(formatSun(today));
        try {
            List<Weather.IndexItem> indices = getIndices(city);
            if (!indices.isEmpty()) {
                sb.append("\n").append(formatIndices(indices));
            }
        } catch (Exception e) {
            log.debug("生活指数获取失败(已降级): {}", e.getMessage());
        }
        return sb.toString();
    }

    /**
     * 格式化实时天气为中文句子(含降水、能见度)。
     */
    public String formatNow(Weather.Current c) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s当前天气:%s,气温 %s℃(体感 %s℃),相对湿度 %s%%,%s %s级。",
                c.city(), c.text(), c.temp(), c.feelsLike(), c.humidity(), c.windDir(), c.windScale()));
        if (!"--".equals(c.precip()) && !c.precip().isBlank()) {
            if ("0".equals(c.precip()) || "0.0".equals(c.precip())) {
                sb.append("无降水。");
            } else {
                sb.append(String.format("过去1小时降水 %smm。", c.precip()));
            }
        }
        if (!"--".equals(c.vis()) && !c.vis().isBlank()) {
            sb.append(String.format("能见度 %skm。", c.vis()));
        }
        return sb.toString();
    }

    /**
     * 格式化空气质量为中文句子,如「空气质量:优(AQI 32,首要污染物 PM2.5)」。
     */
    public String formatAir(Weather.Air a) {
        StringBuilder sb = new StringBuilder("空气质量:").append(a.category());
        sb.append("(AQI ").append(a.aqi());
        if (a.pm2p5() != null && !a.pm2p5().isBlank() && !"--".equals(a.pm2p5())) {
            sb.append(",PM2.5 ").append(a.pm2p5()).append("μg/m³");
        }
        if (a.primary() != null && !a.primary().isBlank()) {
            sb.append(",首要污染物 ").append(a.primary());
        }
        sb.append(")");
        return sb.toString();
    }

    /**
     * 格式化当日日出日落与紫外线,如「今日日出 06:12,日落 18:03,紫外线指数 3」。
     */
    public String formatSun(Weather.Daily day) {
        StringBuilder sb = new StringBuilder("今日日出 ");
        sb.append(day.sunrise() == null || day.sunrise().isBlank() ? "--" : day.sunrise());
        sb.append(",日落 ");
        sb.append(day.sunset() == null || day.sunset().isBlank() ? "--" : day.sunset());
        if (day.uvIndex() != null && !day.uvIndex().isBlank() && !"--".equals(day.uvIndex())) {
            sb.append(",紫外线指数 ").append(day.uvIndex());
        }
        return sb.toString();
    }

    /**
     * 格式化生活指数摘要,如「生活指数:穿衣「舒适」、运动「较适宜」」。
     */
    public String formatIndices(List<Weather.IndexItem> indices) {
        StringBuilder sb = new StringBuilder("生活指数:");
        for (int i = 0; i < indices.size(); i++) {
            Weather.IndexItem it = indices.get(i);
            String name = it.name().replace("指数", "");
            if (i > 0) {
                sb.append("、");
            }
            sb.append(name).append('「').append(it.category()).append('」');
        }
        return sb.toString();
    }

    /**
     * 格式化 3 天预报为文本摘要。
     */
    public String format3d(String city, List<Weather.Daily> days) {
        StringBuilder sb = new StringBuilder(city).append("未来3天预报:\n");
        for (Weather.Daily day : days) {
            sb.append(String.format("%s %s,%s~%s℃",
                    day.date(), day.textDay(), day.tempMin(), day.tempMax()));
            if (day.uvIndex() != null && !day.uvIndex().isBlank() && !"--".equals(day.uvIndex())) {
                sb.append(",紫外线").append(day.uvIndex());
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * 格式化明天天气为中文句子(含紫外线指数)。
     */
    public String formatTomorrow(String city, Weather.Daily day) {
        StringBuilder sb = new StringBuilder(String.format("%s明天(%s):%s转%s,%s~%s℃,%s%s级",
                city, day.date(), day.textDay(), day.textNight(),
                day.tempMin(), day.tempMax(), day.windDirDay(), day.windScaleDay()));
        if (day.uvIndex() != null && !day.uvIndex().isBlank() && !"--".equals(day.uvIndex())) {
            sb.append(",紫外线").append(day.uvIndex());
        }
        sb.append('。');
        return sb.toString();
    }

    /**
     * 清洗城市名:去掉末尾的「天气/气温/怎么样/今天/吗」等杂质词与标点,
     * 避免把整句话拿去和风天气查 LocationID 而报「No Such Location」。
     * 清洗后为空(或本身就不是城市)时返回 "",由调用方回落到默认城市。
     */
    public String sanitizeCity(String city) {
        if (city == null) {
            return "";
        }
        String s = city.trim();
        for (int i = 0; i < 4; i++) {
            String next = s.replaceAll(
                    "(?:天气|气温|温度|预报|怎么样|如何|怎样|多少|几度|好不好|会不会|会|呢|吗|啊|吧|的|"
                            + "下雨|下雪|有雨|降雨|"
                            + "今天|明天|后天|昨天|现在|未来|几天|本周|下周|周末|今晚|明晚|"
                            + "早上|上午|中午|下午|晚上|[,\\s。，、？！?!]+)+$", "");
            if (next.equals(s)) {
                break;
            }
            s = next;
        }
        return s;
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
        return getJson(path, location, null);
    }

    /**
     * 调用和风天气接口(可带额外查询参数),返回已校验业务码为 200 的 JSON。
     */
    private JsonNode getJson(String path, String location, String extraQuery) throws Exception {
        String encoded = URLEncoder.encode(location, StandardCharsets.UTF_8);
        String url = baseUrl + path + "?location=" + encoded;
        if (extraQuery != null && !extraQuery.isBlank()) {
            url += "&" + extraQuery;
        }
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
