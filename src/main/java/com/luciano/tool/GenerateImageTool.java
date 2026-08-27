package com.luciano.tool;

import com.google.gson.JsonObject;
import com.luciano.llm.ImageService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 文生图工具。
 * 通过 JSON Schema 向大模型描述函数签名:工具名 generate_image,参数 prompt(图片描述)。
 * 生成的图片字节按用户缓存,由微信 Bot 在 Function Calling 完成后发送。
 */
@Component
public class GenerateImageTool {

    private static final Logger log = LoggerFactory.getLogger(GenerateImageTool.class);

    /** 待发送图片缓存条目:图片列表 + 最近写入时间 */
    private record PendingEntry(java.util.List<byte[]> images, long timestamp) {
    }

    /** 图片生成结果的临时缓存:userId -> 待发送图片条目 */
    private static final ConcurrentHashMap<String, PendingEntry> PENDING_IMAGES = new ConcurrentHashMap<>();

    /** 待发送图片缓存上限,防止异常场景下内存膨胀 */
    private static final int MAX_PENDING = 100;

    /** 缓存图片超过该时长未发送则视为滞留,定期清理 */
    private static final long IMAGE_TTL_MS = 5 * 60 * 1000L;

    /** 定期清理超过 5 分钟未发送的缓存图片,防止生成后未发送导致的内存滞留 */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60 * 1000)
    public void cleanupExpiredImages() {
        long now = System.currentTimeMillis();
        PENDING_IMAGES.entrySet().removeIf(e -> now - e.getValue().timestamp() > IMAGE_TTL_MS);
    }

    private final ImageService imageService;
    private final ToolRegistry registry;

    public GenerateImageTool(ImageService imageService, ToolRegistry registry) {
        this.imageService = imageService;
        this.registry = registry;
    }

    @PostConstruct
    public void init() {
        registry.register(new ToolDefinition(
                "generate_image",
                "根据文字描述生成一张图片。用户要求画、生成、制作一张图(如风景画、动物、插图)时调用。若用户要求结合天气/其他工具结果生成图片,请把之前的工具结果(如天气)融入图片描述,使图片更贴合场景。",
                imageSchema(),
                arguments -> {
                    String prompt = getString(arguments, "prompt", null);
                    if (prompt == null || prompt.isBlank()) {
                        return "错误:缺少图片描述参数 prompt。";
                    }
                    return generate(prompt);
                }));
    }

    /** 生成图片并缓存,返回给 LLM 的执行结果文本 */
    private String generate(String prompt) {
        // 通过当前线程上下文传递 userId 不优雅,此处由 LlmService 在调用前设置归属
        String userId = ImageContext.getCurrentUserId();
        byte[] imageBytes = imageService.generate(prompt);
        if (imageBytes == null) {
            return "错误:图片生成失败,请提示用户换个描述或稍后再试。";
        }
        if (userId != null) {
            if (PENDING_IMAGES.size() >= MAX_PENDING) {
                // 达到上限时清空最旧的,防止内存膨胀
                String oldestKey = PENDING_IMAGES.keys().nextElement();
                PENDING_IMAGES.remove(oldestKey);
                log.warn("待发送图片缓存达到上限,已丢弃用户 {} 的图片", oldestKey);
            }
            // 多张图追加到同一用户的列表,避免并行生图时互相覆盖
            PENDING_IMAGES.compute(userId, (k, entry) -> {
                java.util.List<byte[]> list = entry == null
                        ? new java.util.concurrent.CopyOnWriteArrayList<>() : entry.images();
                list.add(imageBytes);
                return new PendingEntry(list, System.currentTimeMillis());
            });
        }
        return "图片生成成功。请结合此前对话内容(如已查询的天气、用户的需求),用一句完整的中文总结图片内容并告知用户图片已生成。";
    }

    /** 获取并清除指定用户的全部待发送图片 */
    public static java.util.List<byte[]> takePendingImages(String userId) {
        PendingEntry entry = userId == null ? null : PENDING_IMAGES.remove(userId);
        return entry == null ? null : entry.images();
    }

    /** 构造 JSON Schema 描述 generate_image 的参数 */
    private JsonObject imageSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();
        JsonObject prompt = new JsonObject();
        prompt.addProperty("type", "string");
        prompt.addProperty("description", "要生成图片的文字描述,需详细具体,如:一只在草地上奔跑的橘猫");
        properties.add("prompt", prompt);
        schema.add("properties", properties);

        com.google.gson.JsonArray required = new com.google.gson.JsonArray();
        required.add("prompt");
        schema.add("required", required);
        return schema;
    }

    private String getString(JsonObject obj, String key, String defaultVal) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultVal;
        }
        return obj.get(key).getAsString();
    }
}
