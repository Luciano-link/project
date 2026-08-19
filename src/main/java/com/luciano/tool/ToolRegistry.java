package com.luciano.tool;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.alibaba.dashscope.tools.ToolBase;
import com.alibaba.dashscope.tools.ToolFunction;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册中心。
 * 管理所有可被大模型调用的工具,并负责:
 * 1. 把工具定义转换成 SDK 的 ToolFunction(JSON Schema),供 LLM 理解函数签名
 * 2. 根据 LLM 返回的工具名 + JSON 参数,找到并执行对应工具
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();

    /** 注册一个工具 */
    public void register(ToolDefinition tool) {
        tools.put(tool.name(), tool);
        log.info("已注册工具: {} - {}", tool.name(), tool.description());
    }

    /** 获取全部工具定义 */
    public List<ToolDefinition> getTools() {
        return List.copyOf(tools.values());
    }

    /** 将工具定义转换为 SDK 的 ToolBase 列表(供 GenerationParam.tools 使用) */
    public List<ToolBase> toSdkTools() {
        return tools.values().stream()
                .map(tool -> ToolFunction.builder()
                        .function(FunctionDefinition.builder()
                                .name(tool.name())
                                .description(tool.description())
                                .parameters(tool.parametersSchema())
                                .build())
                        .build())
                .map(ToolBase.class::cast)
                .toList();
    }

    /**
     * 执行工具。
     *
     * @param name 工具名
     * @param arguments JSON 格式参数
     * @return 执行结果文本;工具不存在时返回错误提示
     */
    public String execute(String name, JsonObject arguments) {
        ToolDefinition tool = tools.get(name);
        if (tool == null) {
            log.warn("未找到工具: {}", name);
            return "错误:工具 " + name + " 不存在。";
        }
        try {
            String result = tool.executor().execute(arguments);
            log.info("工具 {} 执行完成", name);
            return result;
        } catch (Exception e) {
            log.error("工具 {} 执行失败,参数 = {}", name, arguments, e);
            return "错误:工具 " + name + " 执行失败: " + e.getMessage();
        }
    }
}
