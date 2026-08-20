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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * 封装 DashScope(通义千问)的文本对话、图片理解、图片生成、语音合成与语音识别能力。
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

    @Value("${dashscope.tts-model:cosyvoice-v2}")
    private String ttsModel;

    @Value("${dashscope.tts-voice:longxiaochun_v2}")
    private String ttsVoice;

    @Value("${dashscope.tts-endpoint:https://dashscope.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer}")
    private String ttsEndpoint;

    /** 语音识别(ASR)模型,逗号分隔按顺序逐个尝试,遇到失败自动切换下一个。 */
    @Value("${dashscope.asr-models:qwen3-asr-flash,qwen-audio-asr}")
    private String asrModels;

    /** 语音识别兜底:qwen-audio-asr 多模态接口(以 base64 data URI 传音频)。 */
    @Value("${dashscope.asr-multimodal-endpoint:https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation}")
    private String asrMultimodalEndpoint;

    /**
     * 文本对话,返回模型回复文本。
     *
     * @param history 历史消息(role 与 content 交替,可为空)
     */
    public String chat(String prompt, List<HistoryMessage> history) throws Exception {
        return chat(prompt, history, null);
    }

    /**
     * 文本对话,可附加系统提示(如要求简洁回答,便于语音播报)。
     */
    public String chat(String prompt, List<HistoryMessage> history, String systemHint) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", chatModel);
        ArrayNode messages = body.putArray("messages");
        String currentTime = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE HH:mm", Locale.CHINA));
        String systemContent = "你是一个微信助手。当前时间是 " + currentTime + ",回答日期、星期、时间相关问题时请以此为准。";
        if (systemHint != null && !systemHint.isBlank()) {
            systemContent += " " + systemHint;
        }
        messages.addObject()
                .put("role", "system")
                .put("content", systemContent);
        for (HistoryMessage m : history) {
            messages.add(buildMessageNode(m));
        }
        messages.addObject().put("role", "user").put("content", prompt);

        String url = baseUrl + "/chat/completions";
        JsonNode resp = postJson(url, body);
        return extractChatContent(resp);
    }

    /**
     * Function Calling 对话:带工具清单调用模型。
     *
     * <p>模型可能返回两种结果之一:
     * <ul>
     *   <li>{@code content != null} — 直接回答,不需要调用工具;</li>
     *   <li>{@code toolCalls != null} — 模型决定调用工具,需执行后把结果回填再问一次。</li>
     * </ul>
     *
     * @param systemHint 系统提示(可告诉模型什么时候该用工具)
     * @param history    完整对话上下文(含此前的 user/assistant/tool 消息)
     * @param tools      工具清单(JSON Schema 描述)
     * @param toolChoice "auto"(默认)让模型自行决定,或指定 "none" / 具体函数名
     */
    public ChatResult chatWithTools(String systemHint, List<HistoryMessage> history,
                                    List<FunctionTool> tools, String toolChoice) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", chatModel);
        ArrayNode messages = body.putArray("messages");
        String currentTime = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE HH:mm", Locale.CHINA));
        String systemContent = "你是一个微信助手。当前时间是 " + currentTime
                + ",回答日期、星期、时间相关问题时请以此为准。";
        if (systemHint != null && !systemHint.isBlank()) {
            systemContent += " " + systemHint;
        }
        messages.addObject()
                .put("role", "system")
                .put("content", systemContent);
        for (HistoryMessage m : history) {
            messages.add(buildMessageNode(m));
        }

        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArr = body.putArray("tools");
            for (FunctionTool t : tools) {
                toolsArr.add(t.toJson());
            }
        }
        if (toolChoice != null && !toolChoice.isBlank()) {
            body.put("tool_choice", toolChoice);
        }

        JsonNode resp = postJson(baseUrl + "/chat/completions", body);
        JsonNode msg = resp.path("choices").get(0).path("message");
        String content = msg.path("content").isValueNode() ? msg.path("content").asText() : null;
        List<ToolCall> calls = new ArrayList<>();
        JsonNode toolCalls = msg.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode call : toolCalls) {
                calls.add(new ToolCall(
                        call.path("id").asText(),
                        call.path("function").path("name").asText(),
                        call.path("function").path("arguments").asText()));
            }
        }
        return new ChatResult(content, calls);
    }

    /**
     * 把一条对话消息序列化为 API 需要的 JSON 节点。
     * 普通消息只有 role/content;工具调用消息带 tool_calls;工具结果消息带 tool_call_id。
     */
    private ObjectNode buildMessageNode(HistoryMessage m) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("role", m.role());
        if (m.content() != null) {
            node.put("content", m.content());
        }
        if (m.toolCallId() != null) {
            node.put("tool_call_id", m.toolCallId());
        }
        if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
            ArrayNode calls = node.putArray("tool_calls");
            for (ToolCall tc : m.toolCalls()) {
                ObjectNode call = calls.addObject();
                call.put("id", tc.id());
                call.put("type", "function");
                ObjectNode fn = call.putObject("function");
                fn.put("name", tc.name());
                // arguments 必须是 JSON 字符串(OpenAI 兼容格式要求)
                fn.put("arguments", tc.arguments());
            }
        }
        return node;
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

    /**
     * 语音合成(CosyVoice):把文本合成 mp3 音频字节。
     */
    public byte[] synthesizeSpeech(String text) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", ttsModel);
        ObjectNode input = body.putObject("input");
        input.put("text", text);
        input.put("voice", ttsVoice);
        input.put("format", "mp3");
        input.put("sample_rate", 24000);

        JsonNode resp = postJson(ttsEndpoint, body);
        String audioUrl = resp.path("output").path("audio").path("url").asText();
        if (audioUrl == null || audioUrl.isBlank()) {
            throw new IllegalStateException("TTS 未返回音频 URL: " + resp);
        }
        return download(audioUrl);
    }

    /**
     * 语音识别(ASR):把 WAV/PCM 音频上传到 DashScope OpenAI 兼容的
     * {@code /audio/transcriptions} 接口(可直接上传本地文件,无需公网 URL)。
     *
     * <p>按 {@link #asrModels} 配置的模型列表逐个尝试,全部失败则抛出异常。
     *
     * @param wav      音频字节(WAV/PCM s16le 单声道)
     * @param fileName 上传文件名(建议带 .wav 后缀)
     */
    public String transcribeSpeech(byte[] wav, String fileName) throws Exception {
        List<String> models = Arrays.stream(asrModels.split(","))
                .map(String::trim)
                .filter(m -> !m.isEmpty())
                .toList();
        if (models.isEmpty()) {
            models = List.of("qwen3-asr-flash", "qwen-audio-asr");
        }
        String lastError = null;
        for (String model : models) {
            try {
                String text = transcribeWithModel(wav, fileName, model);
                log.info("语音识别成功(model={}): {}", model, text);
                return text;
            } catch (Exception e) {
                lastError = e.getMessage();
                log.warn("语音识别模型 {} 失败: {}", model, e.getMessage());
            }
        }
        // 最后兜底:qwen-audio-asr 多模态接口(base64 data URI 传音频)
        try {
            String text = transcribeWithMultimodal(wav);
            log.info("语音识别成功(model=qwen-audio-asr 多模态): {}", text);
            return text;
        } catch (Exception e) {
            lastError = "多模态识别失败: " + e.getMessage() + " (之前的错误: " + lastError + ")";
            log.warn("qwen-audio-asr 多模态识别失败: {}", e.getMessage());
        }
        throw new IllegalStateException("语音识别失败: " + lastError);
    }

    /**
     * 用指定 ASR 模型识别一段音频(OpenAI 兼容 multipart/form-data 上传)。
     */
    private String transcribeWithModel(byte[] wav, String fileName, String model) throws Exception {
        String boundary = "----dsh-asr-" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeMultipartField(body, boundary, "model", model);
        writeMultipartField(body, boundary, "language", "zh");
        writeMultipartFile(body, boundary, "file", fileName, "audio/wav", wav);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/audio/transcriptions"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
        }
        JsonNode resp = objectMapper.readTree(response.body());
        // OpenAI 兼容格式返回 {"text": "..."};兼容部分 DashScope 原生返回 output.text
        String text = resp.path("text").asText("");
        if (text.isBlank()) {
            text = resp.path("output").path("text").asText("");
        }
        if (text.isBlank()) {
            throw new IllegalStateException("ASR 未返回文本: " + resp);
        }
        return text.trim();
    }

    /**
     * 兜底识别:qwen-audio-asr 多模态接口,音频以 base64 data URI 直接内嵌上传。
     */
    private String transcribeWithMultimodal(byte[] wav) throws Exception {
        String dataUrl = "data:audio/wav;base64," + Base64.getEncoder().encodeToString(wav);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", "qwen-audio-asr");
        ArrayNode messages = body.putObject("input").putArray("messages");
        ArrayNode content = messages.addObject().put("role", "user").putArray("content");
        content.addObject().put("audio", dataUrl);

        JsonNode resp = postJsonTimeout(asrMultimodalEndpoint, body, Duration.ofSeconds(60));
        JsonNode choices = resp.path("output").path("choices");
        if (choices.isArray() && choices.size() > 0) {
            JsonNode contentNode = choices.get(0).path("message").path("content");
            StringBuilder sb = new StringBuilder();
            if (contentNode.isArray()) {
                for (JsonNode c : contentNode) {
                    sb.append(c.path("text").asText(""));
                }
            } else {
                sb.append(contentNode.asText(""));
            }
            String text = sb.toString().trim();
            if (!text.isBlank()) {
                return text;
            }
        }
        throw new IllegalStateException("qwen-audio-asr 未返回文本: " + resp);
    }

    private void writeMultipartField(ByteArrayOutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private void writeMultipartFile(ByteArrayOutputStream out, String boundary, String name,
                                    String fileName, String contentType, byte[] data) throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + fileName + "\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
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
        return postJsonWithTimeout(url, body, headerName, headerValue, Duration.ofSeconds(120));
    }

    private JsonNode postJsonTimeout(String url, ObjectNode body, Duration timeout) throws Exception {
        return postJsonWithTimeout(url, body, null, null, timeout);
    }

    private JsonNode postJsonWithTimeout(String url, ObjectNode body, String headerName, String headerValue,
                                         Duration timeout) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
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

    /**
     * 对话消息。除常规 user/assistant 外,支持 Function Calling 需要的两种特殊消息:
     * <ul>
     *   <li>assistant 携带 {@code toolCalls}(模型决定要调用哪些工具);</li>
     *   <li>{@code tool} 携带 {@code toolCallId}(工具执行结果回填给模型)。</li>
     * </ul>
     */
    public record HistoryMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {
        public HistoryMessage(String role, String content) {
            this(role, content, null, null);
        }

        public static HistoryMessage user(String content) {
            return new HistoryMessage("user", content, null, null);
        }

        public static HistoryMessage assistant(String content) {
            return new HistoryMessage("assistant", content, null, null);
        }

        /** 助手消息 + 要调用的工具列表(模型输出 tool_calls 后回填到对话)。 */
        public static HistoryMessage assistantWithToolCalls(List<ToolCall> toolCalls) {
            return new HistoryMessage("assistant", null, toolCalls, null);
        }

        /** 工具执行结果消息,必须携带对应的 tool_call_id。 */
        public static HistoryMessage toolResult(String toolCallId, String content) {
            return new HistoryMessage("tool", content, null, toolCallId);
        }
    }

    /**
     * 模型发起的工具调用:name 为函数名,arguments 是 JSON Schema 校验过的参数字符串。
     */
    public record ToolCall(String id, String name, String arguments) {
    }

    /**
     * 一次对话返回:要么有 content(直接回答),要么有 toolCalls(需要调用工具)。
     */
    public record ChatResult(String content, List<ToolCall> toolCalls) {
        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }

    /**
     * 函数工具定义(OpenAI 兼容格式):JSON Schema 描述函数签名。
     *
     * @param name        函数名(模型会用它发起调用,需与执行端一致)
     * @param description 函数作用说明,写得越清楚,模型判断越准
     * @param parameters  JSON Schema 的 parameters 节点,描述入参结构
     */
    public record FunctionTool(String name, String description, ObjectNode parameters) {
        public ObjectNode toJson() {
            ObjectNode node = objectMapperForTool.createObjectNode();
            node.put("type", "function");
            ObjectNode fn = node.putObject("function");
            fn.put("name", name);
            fn.put("description", description);
            fn.set("parameters", parameters);
            return node;
        }
    }

    private static final ObjectMapper objectMapperForTool = new ObjectMapper();
}
