package com.luciano.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class UnitConvertToolTest {

    private ToolDefinition captureTool() {
        ToolRegistry registry = mock(ToolRegistry.class);
        UnitConvertTool tool = new UnitConvertTool(registry);
        tool.init();
        ArgumentCaptor<ToolDefinition> captor = ArgumentCaptor.forClass(ToolDefinition.class);
        verify(registry).register(captor.capture());
        return captor.getValue();
    }

    private String convert(String json) {
        JsonObject args = JsonParser.parseString(json).getAsJsonObject();
        return captureTool().executor().execute(args);
    }

    @Test
    void temperatureCelsiusToFahrenheit() {
        assertEquals("212", convert("{\"value\":100,\"from\":\"celsius\",\"to\":\"fahrenheit\"}"));
        assertEquals("0", convert("{\"value\":32,\"from\":\"fahrenheit\",\"to\":\"celsius\"}"));
    }

    @Test
    void lengthKmToM() {
        assertEquals("5000", convert("{\"value\":5,\"from\":\"km\",\"to\":\"m\"}"));
        // 1 米 = 3 尺,5 尺 = 5/3 ≈ 1.6667 米
        assertEquals(1.6667, Double.parseDouble(convert("{\"value\":5,\"from\":\"chi\",\"to\":\"m\"}")), 0.0001);
    }

    @Test
    void weightKgToJin() {
        assertEquals("2", convert("{\"value\":1,\"from\":\"kg\",\"to\":\"jin\"}"));
        assertEquals("500", convert("{\"value\":1,\"from\":\"jin\",\"to\":\"g\"}"));
    }

    @Test
    void differentDimensionsRejected() {
        String result = convert("{\"value\":10,\"from\":\"celsius\",\"to\":\"m\"}");
        assertTrue(result.contains("无法换算"));
    }
}
