package com.demo.demo.Service.scheduling.tool;

import com.demo.demo.Service.scheduling.application.CreateDailyWeatherTaskCommand;
import com.demo.demo.Service.scheduling.application.ScheduledTaskService;
import com.demo.demo.Service.scheduling.application.SchedulingException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CreateScheduledTaskToolTest {

    private CreateScheduledTaskTool tool;
    private FakeScheduledTaskService fakeService;

    @BeforeEach
    void setUp() {
        fakeService = new FakeScheduledTaskService();
        tool = new CreateScheduledTaskTool(fakeService);
    }

    @AfterEach
    void tearDown() {
        TrustedToolContext.clear();
    }

    @Test
    void shouldCreateTaskSuccessfully() {
        TrustedToolContext.setTargetId("target-1");

        ScheduledTaskToolResult result = tool.createDailyWeatherTask(
                "杭州", "08:00", "Asia/Shanghai");

        assertEquals("OK", result.status());
        assertTrue(result.message().contains("杭州"));
        assertTrue(result.message().contains("08:00"));
        assertNotNull(fakeService.lastCommand, "command should be passed to service");
        assertEquals("target-1", fakeService.lastCommand.ownerTargetId());
        assertEquals("杭州", fakeService.lastCommand.location());
    }

    @Test
    void shouldRejectWhenNoTargetId() {
        // TrustedToolContext NOT set
        ScheduledTaskToolResult result = tool.createDailyWeatherTask(
                "杭州", "08:00", "Asia/Shanghai");

        assertEquals("ERROR", result.status());
        assertTrue(result.message().contains("无法识别用户身份"));
    }

    @Test
    void shouldRejectInvalidTime() {
        TrustedToolContext.setTargetId("target-1");

        ScheduledTaskToolResult result = tool.createDailyWeatherTask(
                "杭州", "not-a-time", "Asia/Shanghai");

        assertEquals("ERROR", result.status());
        assertTrue(result.message().contains("HH:mm"));
    }

    @Test
    void shouldRejectInvalidTimezone() {
        TrustedToolContext.setTargetId("target-1");

        ScheduledTaskToolResult result = tool.createDailyWeatherTask(
                "杭州", "08:00", "Mars/Base");

        assertEquals("ERROR", result.status());
        assertTrue(result.message().contains("时区"));
    }

    @Test
    void shouldRejectEmptyLocation() {
        TrustedToolContext.setTargetId("target-1");

        ScheduledTaskToolResult result = tool.createDailyWeatherTask(
                "   ", "08:00", "Asia/Shanghai");

        assertEquals("ERROR", result.status());
    }

    @Test
    void shouldReturnErrorMessageOnDuplicate() {
        fakeService.failWith = new SchedulingException("Duplicate task");
        TrustedToolContext.setTargetId("target-1");

        ScheduledTaskToolResult result = tool.createDailyWeatherTask(
                "杭州", "08:00", "Asia/Shanghai");

        assertEquals("ERROR", result.status());
        assertTrue(result.message().contains("Duplicate"));
    }

    @Test
    void targetIdMustNotAppearInModelParameters() {
        // The tool's @Tool annotation must NOT expose targetId as a @ToolParam
        // This is verified by inspection: the method signature has only
        // location, localTimeStr, timeZoneStr — no targetId parameter
        var methods = CreateScheduledTaskTool.class.getMethods();
        for (var m : methods) {
            if ("createDailyWeatherTask".equals(m.getName())) {
                for (var p : m.getParameters()) {
                    assertNotEquals("targetId", p.getName(),
                            "targetId must NOT be a ToolParam");
                }
            }
        }
    }

    // ---- fake service ----

    private static class FakeScheduledTaskService extends ScheduledTaskService {
        CreateDailyWeatherTaskCommand lastCommand;
        SchedulingException failWith;

        FakeScheduledTaskService() {
            super(null); // null repo — won't be called in unit test
        }

        @Override
        public String createDailyWeatherTask(CreateDailyWeatherTaskCommand cmd) {
            this.lastCommand = cmd;
            if (failWith != null) throw failWith;
            return "task-uuid-123";
        }
    }
}
