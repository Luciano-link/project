package com.luciano.wechat;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 待合并图片/文字缓存。
 * 用于"图文/文图两条消息"场景:先发图后发文字,或先发文字后发图,
 * 两条消息需要合并交给多模态模型理解。
 * 缓存带时间戳,超时未等到对方则单独处理。
 */
public final class ImagePendingStore {

    /** 待合并图片缓存:userId -> 图片信息 */
    private static final ConcurrentHashMap<String, PendingImage> PENDING = new ConcurrentHashMap<>();

    /** 待合并文字缓存:userId -> 文字信息(先发文字后发图的场景) */
    private static final ConcurrentHashMap<String, PendingText> PENDING_TEXTS = new ConcurrentHashMap<>();

    /** 图片/文字等待对方的最大时间(毫秒):覆盖"先发文字/图片再补对方"的上传间隔 */
    public static final long MERGE_WINDOW_MS = 20000;

    /** 待合并图片缓存上限,防止大量发图不补文字时内存膨胀 */
    private static final int MAX_PENDING = 200;

    /** 待合并文字条目 */
    public record PendingText(String id, String text, long timestamp) {
        public boolean expired() {
            return System.currentTimeMillis() - timestamp > MERGE_WINDOW_MS;
        }
    }

    /** 缓存一条待合并文字,返回唯一 id */
    public static String putText(String userId, String text) {
        String id = java.util.UUID.randomUUID().toString();
        PENDING_TEXTS.put(userId, new PendingText(id, text, System.currentTimeMillis()));
        return id;
    }

    /** 获取最近的待合并文字(不消费),无或过期返回 null */
    public static PendingText getPendingText(String userId) {
        PendingText pt = PENDING_TEXTS.get(userId);
        return (pt != null && !pt.expired()) ? pt : null;
    }

    /** 取出待合并文字(消费),无或过期返回 null */
    public static PendingText takeText(String userId) {
        PendingText pt = PENDING_TEXTS.remove(userId);
        if (pt != null && pt.expired()) {
            return null;
        }
        return pt;
    }

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
        evictIfNeeded();
        String id = java.util.UUID.randomUUID().toString();
        PENDING.put(userId, new PendingImage(id, bytes, fileName, System.currentTimeMillis()));
        return id;
    }

    /** 缓存达到上限时:先清过期项,仍超限则移除最旧的一条 */
    private static void evictIfNeeded() {
        if (PENDING.size() < MAX_PENDING) {
            return;
        }
        long now = System.currentTimeMillis();
        PENDING.entrySet().removeIf(e -> now - e.getValue().timestamp() > MERGE_WINDOW_MS);
        if (PENDING.size() >= MAX_PENDING) {
            PENDING.entrySet().stream()
                    .min(java.util.Comparator.comparingLong(e -> e.getValue().timestamp()))
                    .ifPresent(e -> PENDING.remove(e.getKey()));
        }
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
