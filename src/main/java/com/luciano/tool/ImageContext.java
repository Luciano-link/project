package com.luciano.tool;

/**
 * 工具执行上下文。
 * 通过 ThreadLocal 在 Function Calling 执行链中传递当前用户标识,
 * 使生图工具生成的图片能归属到正确用户。
 */
public final class ImageContext {

    private static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();

    private ImageContext() {
    }

    public static void setCurrentUserId(String userId) {
        CURRENT_USER.set(userId);
    }

    public static String getCurrentUserId() {
        return CURRENT_USER.get();
    }

    public static void clear() {
        CURRENT_USER.remove();
    }
}
