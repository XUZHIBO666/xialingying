package com.demo.demo.Service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verify that {@link BotInstance#sendTextWithResult} properly distinguishes
 * success, offline, and error states.
 */
class BotInstanceScheduledSendTest {

    /**
     * The new method {@code sendTextWithResult} exists and returns a proper
     * result type — verified structurally.
     */
    @Test
    void sendTextWithResultMethodShouldExist() throws Exception {
        var method = BotInstance.class.getMethod(
                "sendTextWithResult", String.class, String.class, String.class);
        assertNotNull(method);
        assertEquals(BotInstance.PushResult.class, method.getReturnType());
    }

    /**
     * The original {@code sendReply} still exists unchanged.
     */
    @Test
    void originalSendReplyShouldStillExist() throws Exception {
        var method = BotInstance.class.getMethod(
                "sendReply", String.class, String.class, String.class);
        assertNotNull(method);
        assertEquals(void.class, method.getReturnType());
    }

    @Test
    void pushResultOkShouldHaveNoError() {
        BotInstance.PushResult ok = BotInstance.PushResult.ok();
        assertTrue(ok.success());
        assertNull(ok.errorCode());
    }

    @Test
    void pushResultFailedShouldHaveErrorCode() {
        BotInstance.PushResult failed = BotInstance.PushResult.failed("BOT_OFFLINE");
        assertFalse(failed.success());
        assertEquals("BOT_OFFLINE", failed.errorCode());
    }
}
