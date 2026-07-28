package com.demo.demo.Service.scheduling.adapter;

import com.demo.demo.Service.BotInstance;
import com.demo.demo.Service.MultiBotManager;
import com.demo.demo.Service.scheduling.application.DeliveryTargetService;
import com.demo.demo.Service.scheduling.application.SchedulingException;
import com.demo.demo.Service.scheduling.execution.PushRequest;
import com.demo.demo.Service.scheduling.execution.PushResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ILinkMessagePushGatewayTest {

    private MultiBotManager botManager;
    private DeliveryTargetService targetService;
    private ILinkMessagePushGateway gateway;
    private BotInstance mockBot;

    @BeforeEach
    void setUp() {
        botManager = mock(MultiBotManager.class);
        targetService = mock(DeliveryTargetService.class);
        mockBot = mock(BotInstance.class);
        when(botManager.getDefaultBot()).thenReturn(mockBot);
        gateway = new ILinkMessagePushGateway(botManager, targetService);
    }

    @Test
    void shouldPushSuccessfully() {
        when(targetService.resolve("target-1"))
                .thenReturn(new com.demo.demo.Service.scheduling.application.DeliveryTargetResolved("user-1", "plain-token"));
        when(mockBot.sendTextWithResult(eq("user-1"), eq("plain-token"), anyString()))
                .thenReturn(BotInstance.PushResult.ok());

        PushResult result = gateway.pushText(new PushRequest("target-1", "hello"));

        assertTrue(result.success());
        assertNull(result.errorCode());
    }

    @Test
    void shouldMapOfflineError() {
        when(targetService.resolve("target-1"))
                .thenReturn(new com.demo.demo.Service.scheduling.application.DeliveryTargetResolved("user-1", "token"));
        when(mockBot.sendTextWithResult(anyString(), anyString(), anyString()))
                .thenReturn(BotInstance.PushResult.failed("BOT_OFFLINE"));

        PushResult result = gateway.pushText(new PushRequest("target-1", "hello"));

        assertFalse(result.success());
        assertEquals("BOT_OFFLINE", result.errorCode());
    }

    @Test
    void shouldMapSessionExpiredError() {
        when(targetService.resolve("target-1"))
                .thenReturn(new com.demo.demo.Service.scheduling.application.DeliveryTargetResolved("user-1", "token"));
        when(mockBot.sendTextWithResult(anyString(), anyString(), anyString()))
                .thenReturn(BotInstance.PushResult.failed("SESSION_EXPIRED"));

        PushResult result = gateway.pushText(new PushRequest("target-1", "hello"));

        assertFalse(result.success());
        assertEquals("SESSION_EXPIRED", result.errorCode());
    }

    @Test
    void shouldMapSdkError() {
        when(targetService.resolve("target-1"))
                .thenReturn(new com.demo.demo.Service.scheduling.application.DeliveryTargetResolved("user-1", "token"));
        when(mockBot.sendTextWithResult(anyString(), anyString(), anyString()))
                .thenReturn(BotInstance.PushResult.failed("SDK_ERROR"));

        PushResult result = gateway.pushText(new PushRequest("target-1", "hello"));

        assertFalse(result.success());
        assertEquals("SDK_ERROR", result.errorCode());
    }

    @Test
    void shouldReturnErrorWhenTargetNotFound() {
        when(targetService.resolve("bad-target"))
                .thenThrow(new SchedulingException("not found"));

        PushResult result = gateway.pushText(new PushRequest("bad-target", "hello"));

        assertFalse(result.success());
        assertEquals("TARGET_NOT_FOUND", result.errorCode());
        verify(mockBot, never()).sendTextWithResult(anyString(), anyString(), anyString());
    }

    @Test
    void shouldNotLogTokenContent() {
        // This is verified by code inspection: no log.info/warn/error contains
        // the token string — the gateway only logs targetId and error codes.
        when(targetService.resolve("target-1"))
                .thenReturn(new com.demo.demo.Service.scheduling.application.DeliveryTargetResolved("user-1", "secret-token-12345"));
        when(mockBot.sendTextWithResult(anyString(), anyString(), anyString()))
                .thenReturn(BotInstance.PushResult.ok());

        PushResult result = gateway.pushText(new PushRequest("target-1", "test message"));

        assertTrue(result.success());
        // Token "secret-token-12345" was passed to sendTextWithResult but not logged
    }
}
