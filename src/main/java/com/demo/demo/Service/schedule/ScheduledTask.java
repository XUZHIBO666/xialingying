package com.demo.demo.Service.schedule;

import java.time.LocalDateTime;

public class ScheduledTask {

    private String id;
    private String userId;
    private String contextToken;
    private String action;
    private String executeAt;
    private String status;
    private long createdAt;

    public ScheduledTask() {}

    public ScheduledTask(String id, String userId, String contextToken,
                         String action, String executeAt) {
        this.id = id;
        this.userId = userId;
        this.contextToken = contextToken;
        this.action = action;
        this.executeAt = executeAt;
        this.status = "pending";
        this.createdAt = System.currentTimeMillis();
    }

    public boolean isPending() {
        return "pending".equals(status);
    }

    public boolean isPastDue() {
        try {
            return LocalDateTime.parse(executeAt).isBefore(LocalDateTime.now());
        } catch (Exception e) {
            return false;
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getContextToken() { return contextToken; }
    public void setContextToken(String contextToken) { this.contextToken = contextToken; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getExecuteAt() { return executeAt; }
    public void setExecuteAt(String executeAt) { this.executeAt = executeAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

}
