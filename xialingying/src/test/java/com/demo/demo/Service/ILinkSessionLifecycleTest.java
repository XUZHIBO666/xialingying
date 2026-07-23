package com.demo.demo.Service;

import com.lth.wechat.ilink.ILinkClient;
import com.lth.wechat.ilink.LoginCredentials;
import com.lth.wechat.ilink.exception.ILinkSessionExpiredException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ILinkSessionLifecycleTest {

    @Test
    @SuppressWarnings("unchecked")
    void sessionExpiredStopsListeningAndMarksLoggedOut() throws Exception {
        ILinkClient client = mock(ILinkClient.class);
        BotService botService = loggedInBotService(client);
        when(client.receiveMessages(any(LoginCredentials.class), anyString()))
                .thenThrow(new ILinkSessionExpiredException());

        ReflectionTestUtils.invokeMethod(botService, "startListening");

        verify(client, timeout(1000)).receiveMessages(any(LoginCredentials.class), anyString());
        Thread.sleep(100);

        AtomicReference<LoginCredentials> credentials =
                (AtomicReference<LoginCredentials>) ReflectionTestUtils.getField(botService, "credentials");
        assertFalse(botService.isLoggedIn());
        assertNull(credentials.get());
        assertTrue(botService.getStatusText().contains("重新扫码"));
    }

    private BotService loggedInBotService(ILinkClient client) {
        BotService botService = new BotService(client);
        ReflectionTestUtils.setField(botService, "loggedIn", true);
        @SuppressWarnings("unchecked")
        AtomicReference<LoginCredentials> credentials =
                (AtomicReference<LoginCredentials>) ReflectionTestUtils.getField(botService, "credentials");
        credentials.set(new LoginCredentials("token", "bot-user", "https://example.test"));
        return botService;
    }
}
