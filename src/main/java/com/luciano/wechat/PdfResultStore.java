package com.luciano.wechat;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 待发送 PDF 缓存:userId -> PDF 字节。
 * Skill 生成 PDF 后暂存,由消息路由取走发送(与 GenerateImageTool 图片缓存同机制)。
 */
public final class PdfResultStore {

    /** 待发送 PDF 缓存上限,防止发送失败时滞留内存 */
    private static final int MAX_PENDING = 50;

    private static final ConcurrentHashMap<String, byte[]> PENDING = new ConcurrentHashMap<>();

    private PdfResultStore() {
    }

    public static void put(String userId, byte[] pdf) {
        if (userId == null || pdf == null) {
            return;
        }
        if (PENDING.size() >= MAX_PENDING) {
            PENDING.clear();
        }
        PENDING.put(userId, pdf);
    }

    public static byte[] take(String userId) {
        return userId == null ? null : PENDING.remove(userId);
    }
}
