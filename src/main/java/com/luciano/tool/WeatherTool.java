package com.luciano.tool;

import com.google.gson.JsonObject;
import com.luciano.weather.WeatherService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * 天气查询工具。
 * 通过 JSON Schema 向大模型描述函数签名:工具名 get_weather,参数 location(城市)。
 */
@Component
public class WeatherTool {

    private final WeatherService weatherService;
    private final ToolRegistry registry;

    public WeatherTool(WeatherService weatherService, ToolRegistry registry) {
        this.weatherService = weatherService;
        this.registry = registry;
    }

    @PostConstruct
    public void init() {
        registry.register(new ToolDefinition(
                "get_weather",
                "查询指定城市(或全国任意城市)的实时天气,包括温度、体感温度、湿度、风向、风力。用户问天气、气温、冷不冷、热不热时调用。",
                weatherSchema(),
                arguments -> {
                    String location = getString(arguments, "location", null);
                    return weatherService.getWeatherNow(location);
                }));
    }

    /** 构造 JSON Schema 描述 get_weather 的参数 */
    private JsonObject weatherSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();
        JsonObject location = new JsonObject();
        location.addProperty("type", "string");
        location.addProperty("description", "城市名称,如 北京 或 beijing");
        properties.add("location", location);
        schema.add("properties", properties);

        com.google.gson.JsonArray required = new com.google.gson.JsonArray();
        required.add("location");
        schema.add("required", required);
        return schema;
    }

    /** 从 JSON 参数安全取值 */
    private String getString(JsonObject obj, String key, String defaultVal) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultVal;
        }
        return obj.get(key).getAsString();
    }
}
