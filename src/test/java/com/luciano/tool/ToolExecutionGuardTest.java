package com.luciano.tool;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolExecutionGuardTest {

    /** 工具名带唯一后缀,避免与其它测试/运行的限流计数相互影响 */
    private static final String TOOL = "rate-test-" + System.nanoTime();

    @Test
    void rateLimitRejectsAfterMaxPerUser() {
        ToolDefinition.ToolExecutor executor = args -> "ok";
        // 同一用户连续调用 MAX_PER_WINDOW 次(10 次)内放行
        for (int i = 0; i < 10; i++) {
            String result = ToolExecutionGuard.executeGuarded(TOOL, executor, new JsonObject(), "u1");
            assertTrue(!result.contains("过于频繁"), "第 " + (i + 1) + " 次不应被限流: " + result);
        }
        // 第 11 次被限流
        String rejected = ToolExecutionGuard.executeGuarded(TOOL, executor, new JsonObject(), "u1");
        assertTrue(rejected.contains("过于频繁"), "第 11 次应被限流");
    }

    @Test
    void userIsolation() {
        ToolDefinition.ToolExecutor executor = args -> "ok";
        String isolatedTool = "rate-iso-" + System.nanoTime();
        // 用户 ua 连续调用 11 次,第 11 次被限流
        for (int i = 0; i < 11; i++) {
            ToolExecutionGuard.executeGuarded(isolatedTool, executor, new JsonObject(), "ua");
        }
        assertTrue(ToolExecutionGuard.executeGuarded(isolatedTool, executor, new JsonObject(), "ua").contains("过于频繁"));
        // 用户 ub 使用同一工具仍应放行(限流按用户隔离)
        assertTrue(!ToolExecutionGuard.executeGuarded(isolatedTool, executor, new JsonObject(), "ub").contains("过于频繁"),
                "不同用户不应被其他用户的限流影响");
    }
}
