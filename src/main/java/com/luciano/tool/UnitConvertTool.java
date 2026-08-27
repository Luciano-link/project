package com.luciano.tool;

import com.google.gson.JsonObject;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * 单位换算工具(从 LiuHaoran 分支整合,零外部依赖)。
 * 温度(℃/℉/K)、长度(m/km/cm/mm/尺)、重量(kg/g/斤/两/磅),同维度互转。
 */
@Component
public class UnitConvertTool {

    private static final Set<String> TEMP = Set.of("celsius", "fahrenheit", "kelvin");
    private static final Set<String> LENGTH = Set.of("m", "km", "cm", "mm", "chi");
    private static final Set<String> WEIGHT = Set.of("kg", "g", "jin", "liang", "lb");

    private final ToolRegistry registry;

    public UnitConvertTool(ToolRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    public void init() {
        registry.register(new ToolDefinition(
                "unit_convert",
                "单位换算,仅支持同维度互转。温度:celsius摄氏度/fahrenheit华氏度/kelvin开尔文;长度:m米/km千米/cm厘米/mm毫米/chi尺;重量:kg千克/g克/jin斤/liang两/lb磅。",
                convertSchema(),
                arguments -> {
                    double value = getDouble(arguments, "value");
                    String from = getString(arguments, "from", null);
                    String to = getString(arguments, "to", null);
                    if (from == null || to == null) {
                        return "错误:缺少单位参数 from/to。";
                    }
                    try {
                        return formatNumber(convert(from, to, value));
                    } catch (IllegalArgumentException e) {
                        return "无法换算:" + e.getMessage();
                    }
                }));
    }

    private double convert(String from, String to, double value) {
        if (TEMP.contains(from) && TEMP.contains(to)) {
            double celsius = switch (from) {
                case "celsius" -> value;
                case "fahrenheit" -> (value - 32) * 5 / 9;
                case "kelvin" -> value - 273.15;
                default -> throw new IllegalArgumentException("未知温度单位:" + from);
            };
            return switch (to) {
                case "celsius" -> celsius;
                case "fahrenheit" -> celsius * 9 / 5 + 32;
                case "kelvin" -> celsius + 273.15;
                default -> throw new IllegalArgumentException("未知温度单位:" + to);
            };
        }
        if (LENGTH.contains(from) && LENGTH.contains(to)) {
            double meters = switch (from) {
                case "m" -> value;
                case "km" -> value * 1000;
                case "cm" -> value / 100;
                case "mm" -> value / 1000;
                case "chi" -> value / 3;
                default -> throw new IllegalArgumentException("未知长度单位:" + from);
            };
            return switch (to) {
                case "m" -> meters;
                case "km" -> meters / 1000;
                case "cm" -> meters * 100;
                case "mm" -> meters * 1000;
                case "chi" -> meters * 3;
                default -> throw new IllegalArgumentException("未知长度单位:" + to);
            };
        }
        if (WEIGHT.contains(from) && WEIGHT.contains(to)) {
            double grams = switch (from) {
                case "kg" -> value * 1000;
                case "g" -> value;
                case "jin" -> value * 500;
                case "liang" -> value * 50;
                case "lb" -> value * 453.59237;
                default -> throw new IllegalArgumentException("未知重量单位:" + from);
            };
            return switch (to) {
                case "kg" -> grams / 1000;
                case "g" -> grams;
                case "jin" -> grams / 500;
                case "liang" -> grams / 50;
                case "lb" -> grams / 453.59237;
                default -> throw new IllegalArgumentException("未知重量单位:" + to);
            };
        }
        throw new IllegalArgumentException("单位维度不一致,无法换算: " + from + " → " + to);
    }

    private String formatNumber(double v) {
        if (!Double.isFinite(v)) {
            return "结果超出范围";
        }
        if (v == Math.rint(v)) {
            return String.valueOf((long) v);
        }
        String s = String.format(Locale.ROOT, "%.4f", v);
        s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }

    private JsonObject convertSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();

        JsonObject value = new JsonObject();
        value.addProperty("type", "number");
        value.addProperty("description", "要换算的数值");
        properties.add("value", value);

        JsonObject from = new JsonObject();
        from.addProperty("type", "string");
        from.addProperty("description", "原单位代码,如 celsius/m/km/kg");
        properties.add("from", from);

        JsonObject to = new JsonObject();
        to.addProperty("type", "string");
        to.addProperty("description", "目标单位代码,须与原单位同维度");
        properties.add("to", to);

        schema.add("properties", properties);
        com.google.gson.JsonArray required = new com.google.gson.JsonArray();
        required.add("value");
        required.add("from");
        required.add("to");
        schema.add("required", required);
        return schema;
    }

    private String getString(JsonObject obj, String key, String defaultVal) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultVal;
        }
        return obj.get(key).getAsString();
    }

    private double getDouble(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return 0;
        }
        return obj.get(key).getAsDouble();
    }
}
