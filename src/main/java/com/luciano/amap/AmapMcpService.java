package com.luciano.amap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.Map;

/**
 * 高德 MCP 客户端(轻量实现)。
 * MCP 本质是 HTTP + JSON-RPC:initialize 握手 → tools/call 调用工具。
 * 不引入 mcp-java-sdk,规避与 Spring Boot 4.1 的依赖兼容风险。
 * 注意:高德 MCP 需要单独开通的专用 key(当前 Web 服务 key 无法认证,返回 INVALID_USER_KEY),
 * 未配置 key 或调用失败时返回 null,由调用方降级到 Web 服务 API。
 */
@Component
public class AmapMcpService {

    private static final Logger log = LoggerFactory.getLogger(AmapMcpService.class);

    @Value("${mcp.amap.url:https://mcp.amap.com/mcp}")
    private String mcpUrl;

    /** 高德 MCP 专用 key(需在高德控制台单独开通 MCP 服务后获取) */
    @Value("${mcp.amap.key:}")
    private String mcpKey;

    private final RestClient restClient = RestClient.builder().build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 通过 MCP 协议调用高德路线规划;未配置 key/认证失败返回 null(调用方降级 Web API) */
    public String routeViaMcp(String from, String to, String city) {
        if (mcpKey == null || mcpKey.isBlank()) {
            log.info("未配置 mcp.amap.key,跳过 MCP,走 Web API");
            return null;
        }
        try {
            initialize();
            Map<String, Object> args = new java.util.LinkedHashMap<>();
            args.put("from", from);
            args.put("to", to);
            if (city != null && !city.isBlank()) {
                args.put("city", city);
            }
            return callTool("route_planning", args);
        } catch (Exception e) {
            log.warn("MCP 调用失败,降级 Web API: {}", e.getMessage());
            return null;
        }
    }

    /** MCP initialize 握手 */
    private void initialize() throws IOException {
        JsonNode resp = post(Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "initialize",
                "params", Map.of("protocolVersion", "2025-06-18",
                        "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "luciano", "version", "1.0"))));
        if (resp.has("error")) {
            throw new IOException("MCP initialize 失败: " + resp.get("error"));
        }
    }

    /** MCP tools/call 调用工具,返回结果 JSON 字符串 */
    private String callTool(String name, Map<String, Object> args) throws IOException {
        JsonNode resp = post(Map.of(
                "jsonrpc", "2.0", "id", 2, "method", "tools/call",
                "params", Map.of("name", name, "arguments", args)));
        if (resp.has("error")) {
            throw new IOException("MCP tools/call 失败: " + resp.get("error"));
        }
        JsonNode result = resp.path("result");
        return result.isMissingNode() ? "MCP 调用成功" : result.toString();
    }

    /** 发送 MCP JSON-RPC 请求 */
    private JsonNode post(Object body) throws IOException {
        String respBody = restClient.post()
                .uri(mcpUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .header("MCP-Protocol-Version", "2025-06-18")
                .headers(h -> h.setBearerAuth(mcpKey))
                .body(objectMapper.writeValueAsString(body))
                .retrieve()
                .body(String.class);
        return objectMapper.readTree(respBody);
    }
}
