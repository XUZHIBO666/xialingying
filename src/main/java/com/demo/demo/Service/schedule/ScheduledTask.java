package com.demo.demo.Service.schedule;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
@Slf4j
@Getter
@Setter
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


}
