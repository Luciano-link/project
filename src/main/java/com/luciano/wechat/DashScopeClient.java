package com.luciano.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * 封装 DashScope(通义千问)的文本对话、图片理解、图片生成能力。
 */
@Component
@PropertySource(value = "classpath:secret.properties", ignoreResourceNotFound = true)
public class DashScopeClient {

    private static final Logger log = LoggerFactory.getLogger(DashScopeClient.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    @Value("${dashscope.api-key:}")
    private String apiKey;

    @Value("${dashscope.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String baseUrl;

    @Value("${dashscope.chat-model:qwen-plus}")
    private String chatModel;

    @Value("${dashscope.vision-model:qwen-vl-max}")
    private String visionModel;

    @Value("${dashscope.image-model:qwen-image-plus}")
    private String imageModel;

    @Value("${dashscope.image-endpoint:https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis}")
    private String imageEndpoint;

    @Value("${dashscope.task-endpoint:https://dashscope.aliyuncs.com/api/v1/tasks}")
    private String taskEndpoint;

    /**
     * 文本对话,返回模型回复文本。
     *
     * @param history 历史消息(role 与 content 交替,可为空)
     */
    public String chat(String prompt, List<HistoryMessage> history) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", chatModel);
        ArrayNode messages = body.putArray("messages");
        String currentTime = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE HH:mm", Locale.CHINA));
        messages.addObject()
                .put("role", "system")
                .put("content", "你是一个微信助手。当前时间是 " + currentTime + ",回答日期、星期、时间相关问题时请以此为准。");
        for (HistoryMessage m : history) {
            ObjectNode node = messages.addObject();
            node.put("role", m.role());
            node.put("content", m.content());
        }
        messages.addObject().put("role", "user").put("content", prompt);

        String url = baseUrl + "/chat/completions";
        JsonNode resp = postJson(url, body);
        return extractChatContent(resp);
    }

    /**
     * 图片理解:把图片字节编码为 base64 data URL 交给视觉模型,返回文字描述。
     */
    public String describeImage(byte[] imageBytes, String prompt) throws Exception {
        String mimeType = detectMimeType(imageBytes);
        String dataUrl = "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(imageBytes);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", visionModel);
        ArrayNode messages = body.putArray("messages");
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        ArrayNode content = userMsg.putArray("content");
        content.addObject()
                .put("type", "image_url")
                .putObject("image_url").put("url", dataUrl);
        content.addObject().put("type", "text").put("text", prompt);

        String url = baseUrl + "/chat/completions";
        JsonNode resp = postJson(url, body);
        return extractChatContent(resp);
    }

    /**
     * 图片生成:提交异步任务,轮询直到成功,返回图片字节。
     */
    public byte[] generateImage(String prompt) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", imageModel);
        body.putObject("input").put("prompt", prompt);
        body.putObject("parameters")
                .put("size", "1328*1328")
                .put("n", 1)
                .put("prompt_extend", true)
                .put("watermark", false);

        JsonNode submitResp = postJson(imageEndpoint, body, "X-DashScope-Async", "enable");
        String taskId = submitResp.path("output").path("task_id").asText();
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalStateException("图像生成未返回 task_id: " + submitResp);
        }
        log.info("图像生成任务已提交, task_id={}", taskId);

        String imageUrl = pollTaskForImage(taskId);
        return download(imageUrl);
    }

    private String pollTaskForImage(String taskId) throws Exception {
        String url = taskEndpoint + "/" + taskId;
        long deadline = System.currentTimeMillis() + 120_000L;
        while (System.currentTimeMillis() < deadline) {
            JsonNode resp = getJson(url);
            String status = resp.path("output").path("task_status").asText();
            log.info("图像生成任务状态: {}", status);
            if ("SUCCEEDED".equals(status)) {
                JsonNode results = resp.path("output").path("results");
                if (results.isArray() && results.size() > 0) {
                    String imageUrl = results.get(0).path("url").asText();
                    if (imageUrl != null && !imageUrl.isBlank()) {
                        return imageUrl;
                    }
                }
                throw new IllegalStateException("任务成功但未找到图片 URL: " + resp);
            }
            if ("FAILED".equals(status) || "CANCELED".equals(status)) {
                throw new IllegalStateException("图像生成失败: " + resp);
            }
            Thread.sleep(3000);
        }
        throw new IllegalStateException("图像生成超时");
    }

    private String extractChatContent(JsonNode resp) {
        JsonNode choices = resp.path("choices");
        if (choices.isArray() && choices.size() > 0) {
            String content = choices.get(0).path("message").path("content").asText();
            if (content != null && !content.isBlank()) {
                return content;
            }
        }
        // 兼容部分模型返回 reasoning_content 或错误
        JsonNode error = resp.path("error");
        if (!error.isMissingNode()) {
            throw new IllegalStateException("LLM 返回错误: " + error);
        }
        throw new IllegalStateException("LLM 响应解析失败: " + resp);
    }

    private JsonNode postJson(String url, ObjectNode body) throws Exception {
        return postJson(url, body, null, null);
    }

    private JsonNode postJson(String url, ObjectNode body, String headerName, String headerValue) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        if (headerName != null) {
            builder.header(headerName, headerValue);
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    private JsonNode getJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    private byte[] download(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("下载图片失败 HTTP " + response.statusCode());
        }
        return response.body();
    }

    /**
     * 简单检测图片 MIME 类型(仅识别常见格式)。
     */
    private String detectMimeType(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
            return "image/jpeg";
        }
        if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return "image/png";
        }
        if (bytes.length >= 3 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
            return "image/gif";
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        return "image/jpeg";
    }

    public record HistoryMessage(String role, String content) {
        public static HistoryMessage user(String content) {
            return new HistoryMessage("user", content);
        }

        public static HistoryMessage assistant(String content) {
            return new HistoryMessage("assistant", content);
        }
    }
}
