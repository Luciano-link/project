package com.luciano.agent;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务状态管理器:按用户维护进行中的长任务。
 * 提供创建/查询/清除,30 分钟无进展视为超时自动清理,防止内存膨胀。
 */
@Component
public class TaskStateManager {

    /** 任务最长存活时间:30 分钟无进展视为超时 */
    private static final long TASK_TIMEOUT_MS = 30 * 60 * 1000L;

    private final ConcurrentHashMap<String, TaskState> tasks = new ConcurrentHashMap<>();

    /** 为用户开启一个新任务(覆盖旧的,若存在) */
    public TaskState start(String userId, String goal) {
        TaskState state = new TaskState(userId, goal);
        tasks.put(userId, state);
        return state;
    }

    /** 获取用户当前任务;无任务或已超时返回 null */
    public TaskState get(String userId) {
        TaskState state = tasks.get(userId);
        if (state == null) {
            return null;
        }
        if (System.currentTimeMillis() - state.getCreatedAt() > TASK_TIMEOUT_MS) {
            tasks.remove(userId);
            return null;
        }
        return state;
    }

    /** 是否存在进行中的任务 */
    public boolean hasActive(String userId) {
        return get(userId) != null;
    }

    /** 清除用户任务(完成或取消时调用) */
    public void clear(String userId) {
        tasks.remove(userId);
    }
}
