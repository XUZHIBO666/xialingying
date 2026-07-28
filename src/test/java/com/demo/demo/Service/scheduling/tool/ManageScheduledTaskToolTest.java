package com.demo.demo.Service.scheduling.tool;

import com.demo.demo.Service.scheduling.application.ScheduledTaskService;
import com.demo.demo.Service.scheduling.application.SchedulingException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ManageScheduledTaskToolTest {

    private ManageScheduledTaskTool tool;
    private FakeManageScheduledTaskService fakeService;

    @BeforeEach
    void setUp() {
        fakeService = new FakeManageScheduledTaskService();
        tool = new ManageScheduledTaskTool(fakeService);
    }

    @AfterEach
    void tearDown() {
        TrustedToolContext.clear();
    }

    @Test
    void listShouldReturnErrorWhenNoTargetId() {
        ScheduledTaskToolResult result = tool.manage("LIST", null);

        assertEquals("ERROR", result.status());
        assertTrue(result.message().contains("无法识别用户身份"));
    }

    @Test
    void listShouldReturnOkWhenTargetIdSet() {
        TrustedToolContext.setTargetId("target-1");

        ScheduledTaskToolResult result = tool.manage("LIST", null);

        assertEquals("OK", result.status());
        assertEquals("LIST", fakeService.lastAction);
        assertEquals("target-1", fakeService.lastTargetId);
    }

    @Test
    void pauseShouldRequireTaskId() {
        TrustedToolContext.setTargetId("target-1");

        ScheduledTaskToolResult result = tool.manage("PAUSE", null);

        assertEquals("ERROR", result.status());
        assertTrue(result.message().contains("任务ID"));
    }

    @Test
    void pauseShouldSucceedWithValidInput() {
        TrustedToolContext.setTargetId("target-1");

        ScheduledTaskToolResult result = tool.manage("PAUSE", "task-1");

        assertEquals("OK", result.status());
        assertEquals("PAUSE", fakeService.lastAction);
        assertEquals("task-1", fakeService.lastTaskId);
    }

    @Test
    void resumeShouldWork() {
        TrustedToolContext.setTargetId("target-1");

        ScheduledTaskToolResult result = tool.manage("RESUME", "task-1");

        assertEquals("OK", result.status());
        assertEquals("RESUME", fakeService.lastAction);
    }

    @Test
    void cancelShouldWork() {
        TrustedToolContext.setTargetId("target-1");

        ScheduledTaskToolResult result = tool.manage("CANCEL", "task-1");

        assertEquals("OK", result.status());
        assertEquals("CANCEL", fakeService.lastAction);
    }

    @Test
    void invalidActionShouldReturnError() {
        TrustedToolContext.setTargetId("target-1");

        ScheduledTaskToolResult result = tool.manage("DELETE", "task-1");

        assertEquals("ERROR", result.status());
        assertTrue(result.message().contains("不支持"));
    }

    @Test
    void shouldPropagateServiceException() {
        fakeService.failWith = new SchedulingException("Task not found");
        TrustedToolContext.setTargetId("target-1");

        ScheduledTaskToolResult result = tool.manage("PAUSE", "task-1");

        assertEquals("ERROR", result.status());
        assertTrue(result.message().contains("Task not found"));
    }

    // ---- fake service ----

    private static class FakeManageScheduledTaskService extends ScheduledTaskService {
        String lastAction;
        String lastTargetId;
        String lastTaskId;
        SchedulingException failWith;

        FakeManageScheduledTaskService() {
            super(null);
        }

        @Override
        public void pause(String targetId, String taskId) {
            this.lastAction = "PAUSE";
            this.lastTargetId = targetId;
            this.lastTaskId = taskId;
            if (failWith != null) throw failWith;
        }

        @Override
        public void resume(String targetId, String taskId) {
            this.lastAction = "RESUME";
            this.lastTargetId = targetId;
            this.lastTaskId = taskId;
            if (failWith != null) throw failWith;
        }

        @Override
        public void cancel(String targetId, String taskId) {
            this.lastAction = "CANCEL";
            this.lastTargetId = targetId;
            this.lastTaskId = taskId;
            if (failWith != null) throw failWith;
        }

        @Override
        public java.util.List<com.demo.demo.Service.scheduling.application.ScheduledTaskSummary> listTasks(String targetId) {
            this.lastAction = "LIST";
            this.lastTargetId = targetId;
            if (failWith != null) throw failWith;
            return java.util.List.of();
        }
    }
}
