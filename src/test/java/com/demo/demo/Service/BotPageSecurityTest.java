package com.demo.demo.Service;

import com.demo.demo.controller.BotController;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lth.wechat.ilink.ILinkClient;
import com.lth.wechat.ilink.LoginCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;

@ExtendWith(OutputCaptureExtension.class)
class BotPageSecurityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void messagesResponseDoesNotExposeContextToken() throws Exception {
        BotService botService = loggedInBotService();
        BotController controller = controller(botService);

        botService.processTextMessage("wx-user", "ctx-secret-token", "<img src=x onerror=alert(1)>");

        Map<String, Object> response = controller.messages();
        String json = objectMapper.writeValueAsString(response);

        assertFalse(json.contains("ctx-secret-token"));
        assertFalse(json.contains("contextToken"));
        assertTrue(json.contains("replyId"));
        assertTrue(json.contains("<img src=x onerror=alert(1)>"));
    }

    @Test
    void botPageDoesNotUseInnerHtmlForDynamicOutput() throws Exception {
        String html = Files.readString(Path.of("src/main/resources/templates/bot.html"));

        assertFalse(html.contains(".innerHTML"));
        assertFalse(html.contains("innerHTML"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void manualReplyUsesReplyIdWithoutExposingContextToken() throws Exception {
        ILinkClient client = mock(ILinkClient.class);
        BotService botService = loggedInBotService(client);
        BotController controller = controller(botService);

        botService.processTextMessage("wx-user", "ctx-secret-token", "hello");
        Map<String, Object> response = controller.messages();
        Map<String, Object> message = (Map<String, Object>) ((java.util.List<?>) response.get("messages")).get(0);

        Map<String, Object> sendResponse = controller.send((String) message.get("replyId"), "manual reply");

        assertTrue((Boolean) sendResponse.get("ok"));
        verify(client).sendTextMessage(any(LoginCredentials.class), org.mockito.Mockito.eq("wx-user"),
                org.mockito.Mockito.eq("ctx-secret-token"), org.mockito.Mockito.eq("manual reply"));
    }

    @Test
    void sendReplyLogsMaskedContextToken(CapturedOutput output) {
        BotService botService = loggedInBotService();

        botService.sendReply("wx-user", "ctx-secret-token", "manual reply");

        String logs = output.toString();
        assertFalse(logs.contains("ctx-secret-token"));
        assertTrue(logs.contains("ctx-...oken"));
    }

    @SuppressWarnings("unchecked")
    private BotService loggedInBotService() {
        return loggedInBotService(mock(ILinkClient.class));
    }

    @SuppressWarnings("unchecked")
    private BotService loggedInBotService(ILinkClient client) {
        BotService botService = new BotService(client);
        ReflectionTestUtils.setField(botService, "loggedIn", true);
        AtomicReference<LoginCredentials> credentials =
                (AtomicReference<LoginCredentials>) ReflectionTestUtils.getField(botService, "credentials");
        credentials.set(new LoginCredentials("token", "bot-user", "https://example.test"));
        return botService;
    }

    private BotController controller(BotService botService) {
        BotController controller = new BotController();
        ReflectionTestUtils.setField(controller, "botService", botService);
        return controller;
    }
}
