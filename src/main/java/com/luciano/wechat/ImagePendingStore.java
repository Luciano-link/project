package com.luciano.wechat;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 待合并图片缓存。
 * 用于"图文/文图两条消息"场景:用户发图片后短时间内再发文字,
 * 两条消息需要合并交给多模态模型理解。
 * 图片缓存带时间戳,超时未等到文字则单独识图。
 */
public final class ImagePendingStore {

    /** 待合并图片缓存:userId -> 图片信息 */
    private static final ConcurrentHashMap<String, PendingImage> PENDING = new ConcurrentHashMap<>();

    /** 图片等待文字描述的最大时间(毫秒):发图后短暂等待可能的文字补充,超时立即自动识图 */
    public static final long MERGE_WINDOW_MS = 15000;

    /** 待合并图片条目 */
    public record PendingImage(String id, byte[] bytes, String fileName, long timestamp) {
        public boolean expired() {
            return System.currentTimeMillis() - timestamp > MERGE_WINDOW_MS;
        }
    }

    private ImagePendingStore() {
    }

    /** 缓存一张待合并图片,返回其唯一 id */
    public static String put(String userId, byte[] bytes, String fileName) {
        String id = java.util.UUID.randomUUID().toString();
        PENDING.put(userId, new PendingImage(id, bytes, fileName, System.currentTimeMillis()));
        return id;
    }

    /**
     * 取出指定 id 的待合并图片(仅当 id 匹配当前缓存且未过期)。
     * 用于文字消息到达时,只合并"刚刚那一次"的图片,避免错配。
     */
    public static PendingImage take(String userId, String imageId) {
        PendingImage img = PENDING.get(userId);
        if (img == null || !img.id().equals(imageId) || img.expired()) {
            return null;
        }
        PENDING.remove(userId);
        return img;
    }

    /**
     * 取出指定 id 的图片用于兜底单独识图(不过期校验)。
     * 图片等待文字超时后调用,保证"发图不说话"也能得到识别结果。
     */
    public static PendingImage takeForFallback(String userId, String imageId) {
        PendingImage img = PENDING.get(userId);
        if (img == null || !img.id().equals(imageId)) {
            return null;
        }
        PENDING.remove(userId);
        return img;
    }

    /** 获取当前待合并图片(不消费,仅查看),无或过期返回 null */
    public static PendingImage getPending(String userId) {
        PendingImage img = PENDING.get(userId);
        return (img != null && !img.expired()) ? img : null;
    }

    /** 检查是否有未过期的待合并图片 */
    public static boolean hasPending(String userId) {
        PendingImage img = PENDING.get(userId);
        return img != null && !img.expired();
    }

    /** 清理指定用户的缓存 */
    public static void clear(String userId) {
        PENDING.remove(userId);
    }
}
