package com.luciano.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工具执行守卫:负责工具调用的超时控制与简单限流。
 * - 超时:工具执行超过上限则中断,防止生图/搜索等耗时操作拖垮线程池
 * - 限流:同一工具在窗口内最多执行 N 次,防止恶意刷消息
 */
public final class ToolExecutionGuard {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutionGuard.class);

    /** 工具默认执行超时(毫秒):生图较慢,给足时间 */
    private static final long DEFAULT_TIMEOUT_MS = 90000;

    /** 限流窗口(毫秒) */
    private static final long RATE_WINDOW_MS = 60000;

    /** 每个工具在窗口内最大执行次数 */
    private static final int MAX_PER_WINDOW = 10;

    /** 工具名 -> 最近执行时间戳列表 */
    private static final ConcurrentHashMap<String, java.util.Deque<Long>> CALL_HISTORY = new ConcurrentHashMap<>();

    /** 工具执行专用线程池:避免慢工具(生图等)占满公共池影响其他异步任务 */
    private static final ExecutorService TOOL_POOL = Executors.newFixedThreadPool(8);

    private ToolExecutionGuard() {
    }

    /**
     * 带超时与限流地执行工具。
     *
     * @param name      工具名
     * @param executor  实际执行逻辑(可能抛出异常)
     * @param arguments 工具参数
     * @param userId    当前用户标识(用于工具内部分配结果归属,可为 null)
     * @return 执行结果文本
     */
    public static String executeGuarded(String name, ToolDefinition.ToolExecutor executor,
                                        com.google.gson.JsonObject arguments, String userId) {
        if (!tryAcquire(name)) {
            log.warn("工具 {} 触发限流,拒绝执行", name);
            return "错误:工具 " + name + " 调用过于频繁,请稍后再试。";
        }
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            ImageContext.setCurrentUserId(userId);
            try {
                String result = executor.execute(arguments);
                return result == null ? "执行成功" : result;
            } catch (Exception e) {
                log.error("工具 {} 执行异常", name, e);
                return "错误:工具 " + name + " 执行失败: " + e.getMessage();
            } finally {
                ImageContext.clear();
            }
        }, TOOL_POOL);
        try {
            return future.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("工具 {} 执行超时(>{}ms),已中断", name, DEFAULT_TIMEOUT_MS);
            return "错误:工具 " + name + " 执行超时,请稍后再试。";
        } catch (Exception e) {
            log.error("工具 {} 执行异常", name, e);
            return "错误:工具 " + name + " 执行失败,请稍后再试。";
        }
    }

    /** 简单的滑动窗口限流:窗口内计数未超限则放行 */
    private static boolean tryAcquire(String name) {
        long now = System.currentTimeMillis();
        java.util.Deque<Long> history = CALL_HISTORY.computeIfAbsent(name, k -> new java.util.ArrayDeque<>());
        synchronized (history) {
            while (!history.isEmpty() && now - history.peekFirst() > RATE_WINDOW_MS) {
                history.pollFirst();
            }
            if (history.size() >= MAX_PER_WINDOW) {
                return false;
            }
            history.addLast(now);
            return true;
        }
    }
}
