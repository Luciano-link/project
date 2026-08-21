package com.luciano.tool;

/**
 * 工具执行上下文。
 * 通过 InheritableThreadLocal 传递当前用户标识,使子线程(工具执行线程)也能继承,
 * 让生图工具生成的图片能归属到正确用户。
 */
public final class ImageContext {

    private static final ThreadLocal<String> CURRENT_USER = new InheritableThreadLocal<>();

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
