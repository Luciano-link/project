package com.luciano.tool;

import com.google.gson.JsonObject;

/**
 * 工具定义:名称、描述、JSON Schema 参数定义、执行函数。
 * LLM 通过 Function Calling 决定调用哪个工具,并传入 JSON 格式的参数。
 */
public record ToolDefinition(
        String name,
        String description,
        JsonObject parametersSchema,
        ToolExecutor executor) {

    /** 工具执行接口:接收 JSON 参数,返回结果文本(用于回填给大模型) */
    @FunctionalInterface
    public interface ToolExecutor {
        String execute(JsonObject arguments);
    }
}
