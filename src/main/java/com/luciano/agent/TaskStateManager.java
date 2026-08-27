package com.luciano.agent;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按用户管理 Agent 任务状态(内存,重启丢失)。
 */
@Component
public class TaskStateManager {

    private final Map<String, TaskState> byUser = new ConcurrentHashMap<>();

    public TaskState create(String userId, String goal) {
        String taskId = UUID.randomUUID().toString();
        TaskState state = new TaskState(taskId, userId, goal);
        byUser.put(userId, state);
        return state;
    }

    public TaskState get(String userId) {
        return byUser.get(userId);
    }

    public void remove(String userId) {
        byUser.remove(userId);
    }
}
