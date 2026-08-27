package com.luciano.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TaskStateManagerTest {

    private TaskStateManager manager;

    @BeforeEach
    void setUp() {
        manager = new TaskStateManager();
    }

    @Test
    void createAndGet_roundTrip() {
        TaskState state = manager.create("user1", "上海三日游");

        assertNotNull(state.getTaskId());
        assertEquals("user1", state.getUserId());
        assertEquals("上海三日游", state.getGoal());
        assertEquals(state, manager.get("user1"));
    }

    @Test
    void remove_clearsState() {
        manager.create("user1", "目标");
        manager.remove("user1");

        assertEquals(null, manager.get("user1"));
    }
}
