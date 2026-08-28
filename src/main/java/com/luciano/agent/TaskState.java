package com.luciano.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 长任务执行状态:一个用户一次进行中的规划任务。
 * 与 ConversationService(跨对话记忆)区分:TaskState 只存"当前这个任务"的临时数据,
 * 任务完成或超时后清除。
 * 同一用户的任务串行处理,跨用户并发,map 用 ConcurrentHashMap 保证线程安全。
 */
public class TaskState {

    /** 任务阶段 */
    public enum Phase {
        /** 等待用户澄清画像(引导式澄清) */
        CLARIFYING,
        /** 执行规划中 */
        EXECUTING,
        /** 已完成(产出最终方案) */
        DONE
    }

    private final String userId;
    private final String goal;
    private final long createdAt;
    private volatile Phase phase = Phase.CLARIFYING;
    /** 用户画像:预算/偏好/人数/补充(引导式澄清后填充) */
    private final Map<String, String> profile = new ConcurrentHashMap<>();
    /** 中间结果:天气/检索/最终方案等(按 key 存取) */
    private final Map<String, String> results = new ConcurrentHashMap<>();
    /** 规划出的结构化子任务 */
    private List<SubTask> subtasks = List.of();
    /** 各子任务执行结果 */
    private final List<StepResult> stepResults = new ArrayList<>();

    public TaskState(String userId, String goal) {
        this.userId = userId;
        this.goal = goal;
        this.createdAt = System.currentTimeMillis();
    }

    public String getUserId() {
        return userId;
    }

    public String getGoal() {
        return goal;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public Phase getPhase() {
        return phase;
    }

    public void setPhase(Phase phase) {
        this.phase = phase;
    }

    public String getProfile(String key) {
        return profile.get(key);
    }

    public void setProfile(String key, String value) {
        profile.put(key, value);
    }

    public Map<String, String> getProfile() {
        return profile;
    }

    public String getResult(String key) {
        return results.get(key);
    }

    public void setResult(String key, String value) {
        results.put(key, value);
    }

    public Map<String, String> getResults() {
        return results;
    }

    public List<SubTask> getSubtasks() {
        return subtasks;
    }

    public void setSubtasks(List<SubTask> subtasks) {
        this.subtasks = subtasks == null ? List.of() : subtasks;
    }

    public List<StepResult> getStepResults() {
        return stepResults;
    }

    public void addStepResult(StepResult result) {
        stepResults.add(result);
    }

    /** 规划出的子任务:toolHint 为 get_weather/search/plan 等,执行时按此分派 */
    public record SubTask(int id, String title, String description, String toolHint) {
    }

    /** 单步执行结果 */
    public record StepResult(int id, String title, String output) {
    }
}
