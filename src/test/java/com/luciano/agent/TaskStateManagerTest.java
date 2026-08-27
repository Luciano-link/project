package com.luciano.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskStateManagerTest {

    @Test
    void startGetClear() {
        TaskStateManager manager = new TaskStateManager();
        manager.start("u1", "帮我制定上海三日游规划");
        assertTrue(manager.hasActive("u1"));
        assertEquals("帮我制定上海三日游规划", manager.get("u1").getGoal());
        manager.clear("u1");
        assertNull(manager.get("u1"));
    }

    @Test
    void phaseStartsWithClarifying() {
        TaskState state = new TaskStateManager().start("u1", "目标");
        assertEquals(TaskState.Phase.CLARIFYING, state.getPhase());
    }

    @Test
    void profileAndResultsPersist() {
        TaskState state = new TaskStateManager().start("u1", "目标");
        state.setProfile("budget", "800");
        state.setProfile("preference", "美食为主");
        state.setResult("weather", "晴 26度");
        assertEquals("800", state.getProfile("budget"));
        assertEquals("美食为主", state.getProfile("preference"));
        assertEquals("晴 26度", state.getResult("weather"));
    }

    @Test
    void startOverwritesExisting() {
        TaskStateManager manager = new TaskStateManager();
        manager.start("u1", "旧目标");
        manager.start("u1", "新目标");
        assertEquals("新目标", manager.get("u1").getGoal());
    }
}
