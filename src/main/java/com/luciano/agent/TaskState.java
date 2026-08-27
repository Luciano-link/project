package com.luciano.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次 Agent 长任务的状态快照。
 */
public class TaskState {

    public enum Status {
        PLANNING, RUNNING, DONE, FAILED
    }

    private final String taskId;
    private final String userId;
    private final String goal;
    private Status status;
    private AgentPlan plan;
    private final List<StepResult> stepResults = new ArrayList<>();
    private String finalOutput;
    private final Instant createdAt = Instant.now();

    public TaskState(String taskId, String userId, String goal) {
        this.taskId = taskId;
        this.userId = userId;
        this.goal = goal;
        this.status = Status.PLANNING;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getUserId() {
        return userId;
    }

    public String getGoal() {
        return goal;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public AgentPlan getPlan() {
        return plan;
    }

    public void setPlan(AgentPlan plan) {
        this.plan = plan;
    }

    public List<StepResult> getStepResults() {
        return stepResults;
    }

    public void addStepResult(StepResult result) {
        stepResults.add(result);
    }

    public String getFinalOutput() {
        return finalOutput;
    }

    public void setFinalOutput(String finalOutput) {
        this.finalOutput = finalOutput;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** 规划出的子任务 */
    public record SubTask(int id, String title, String description, String toolHint) {
    }

    /** 完整计划 */
    public record AgentPlan(String goal, List<SubTask> subtasks) {
    }

    /** 单步执行结果 */
    public record StepResult(int id, String title, String output) {
    }
}
