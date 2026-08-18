package com.luciano.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luciano.config.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 文生图服务。
 * 直接调用阿里云百炼异步文生图接口(当前 SDK 对 wanx 异步调用有兼容性问题,故走 HTTP)。
 * 流程:提交异步任务 -> 轮询任务状态 -> 下载生成的图片字节。
 */
@Service
public class ImageService {

    private static final Logger log = LoggerFactory.getLogger(ImageService.class);

    private static final String API_BASE = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis";
    private static final String TASK_BASE = "https://dashscope.aliyuncs.com/api/v1/tasks/";

    private final LlmProperties properties;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ImageService(LlmProperties properties) {
        this.properties = properties;
    }

    /**
     * 根据提示词生成图片。
     *
     * @param prompt 图片描述
     * @return 图片字节(PNG);失败或未配置 Key 时返回 null
     */
    public byte[] generate(String prompt) {
        String apiKey = properties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("未配置 llm.api-key,文生图不可用");
            return null;
        }
        try {
            String taskId = submitTask(apiKey, prompt);
            log.info("文生图任务已提交,taskId = {}", taskId);
            String imageUrl = waitForResult(apiKey, taskId);
            if (imageUrl == null) {
                return null;
            }
            return download(imageUrl);
        } catch (Exception e) {
            log.error("文生图失败,prompt = {}", prompt, e);
            return null;
        }
    }

    /** 提交异步文生图任务,返回 taskId */
    private String submitTask(String apiKey, String prompt) throws IOException, InterruptedException {
        String body = "{\"model\":\"" + properties.getImageModel()
                + "\",\"input\":{\"prompt\":\"" + escapeJson(prompt)
                + "\"},\"parameters\":{\"size\":\"1024*1024\",\"n\":1}}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("X-DashScope-Async", "enable")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("提交文生图任务失败,HTTP " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode node = objectMapper.readTree(resp.body());
        JsonNode taskId = node.path("output").path("task_id");
        if (taskId.isMissingNode() || taskId.asText().isBlank()) {
            throw new IOException("文生图任务提交成功但缺少 task_id: " + resp.body());
        }
        return taskId.asText();
    }

    /** 轮询任务状态,返回图片 URL */
    private String waitForResult(String apiKey, String taskId) throws IOException, InterruptedException {
        for (int i = 0; i < 30; i++) {
            Thread.sleep(5000);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TASK_BASE + taskId))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IOException("查询任务状态失败,HTTP " + resp.statusCode() + ": " + resp.body());
            }
            JsonNode node = objectMapper.readTree(resp.body());
            String status = node.path("output").path("task_status").asText();
            if ("SUCCEEDED".equals(status)) {
                JsonNode results = node.path("output").path("results");
                if (results == null || !results.isArray() || results.isEmpty()) {
                    log.warn("文生图成功但无结果,taskId = {}", taskId);
                    return null;
                }
                JsonNode url = results.get(0).path("url");
                return url.isMissingNode() ? null : url.asText();
            }
            if ("FAILED".equals(status) || "CANCELED".equals(status) || "UNKNOWN".equals(status)) {
                log.warn("文生图任务失败,taskId = {}, status = {}", taskId, status);
                return null;
            }
            log.debug("文生图任务进行中,taskId = {}, status = {}", taskId, status);
        }
        log.warn("文生图任务超时,taskId = {}", taskId);
        return null;
    }

    /** 下载图片字节 */
    private byte[] download(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<InputStream> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            throw new IOException("下载图片失败,HTTP " + resp.statusCode());
        }
        try (InputStream in = resp.body()) {
            return in.readAllBytes();
        }
    }

    /** 简单 JSON 字符串转义(防提示词含引号/换行导致请求体损坏) */
    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }
}
