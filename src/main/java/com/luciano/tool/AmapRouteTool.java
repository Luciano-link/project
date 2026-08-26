package com.luciano.tool;

import com.google.gson.JsonObject;
import com.luciano.amap.AmapRouteService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * 高德路线查询工具。
 * 工具名 query_route,参数 from(起点)、to(终点)。供 LLM Function Calling 调用,
 * 用于"从 A 到 B 怎么走"这类实时路线查询。
 */
@Component
public class AmapRouteTool {

    private final AmapRouteService routeService;
    private final ToolRegistry registry;

    public AmapRouteTool(AmapRouteService routeService, ToolRegistry registry) {
        this.routeService = routeService;
        this.registry = registry;
    }

    @PostConstruct
    public void init() {
        registry.register(new ToolDefinition(
                "query_route",
                "查询两个地点之间的公交/地铁路线方案与预计耗时。用户问怎么走、路线、交通方式、地铁怎么坐时调用。",
                routeSchema(),
                arguments -> {
                    String from = getString(arguments, "from", null);
                    String to = getString(arguments, "to", null);
                    String city = getString(arguments, "city", null);
                    if (from == null || from.isBlank()) {
                        return "错误:缺少起点参数 from。";
                    }
                    if (to == null || to.isBlank()) {
                        return "错误:缺少终点参数 to。";
                    }
                    return routeService.route(from, to, city);
                }));
    }

    /** 构造 JSON Schema 描述 query_route 的参数 */
    private JsonObject routeSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();
        properties.add("from", strProp("起点地点名称,如 人民广场"));
        properties.add("to", strProp("终点地点名称,如 外滩"));
        properties.add("city", strProp("城市名(如 上海),用于限定地点范围避免同名歧义,建议填写"));
        schema.add("properties", properties);

        com.google.gson.JsonArray required = new com.google.gson.JsonArray();
        required.add("from");
        required.add("to");
        schema.add("required", required);
        return schema;
    }

    private JsonObject strProp(String desc) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "string");
        obj.addProperty("description", desc);
        return obj;
    }

    private String getString(JsonObject obj, String key, String defaultVal) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultVal;
        }
        return obj.get(key).getAsString();
    }
}
