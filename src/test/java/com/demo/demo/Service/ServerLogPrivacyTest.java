package com.demo.demo.Service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerLogPrivacyTest {

    @Test
    void serverLogsDoNotIncludeSensitivePayloadArguments() throws Exception {
        String sources = String.join("\n",
                source("Service/BotService.java"),
                source("Service/AIService.java"),
                source("Service/ImageGenerationService.java"),
                source("Service/ImageRecognitionService.java"),
                source("Service/voice/AsrService.java"),
                source("Service/voice/SiliconFlowAsrService.java"),
                source("Service/voice/TtsService.java"),
                source("Service/voice/SiliconFlowTtsService.java"),
                source("Service/voice/VoiceMessageService.java"),
                source("Service/voice/VoiceMessageHandler.java"),
                source("Service/weather/OpenMeteoWeatherProvider.java"),
                source("Service/weather/WeatherService.java"),
                source("Service/tool/WeatherTool.java"),
                source("controller/BotController.java"),
                source("controller/WeatherController.java"),
                source("execption/GlobalExpectionHandler.java"));
        String serverLogs = serverLogs(sources);

        // Task 2: 不含供应商响应正文
        assertFalse(serverLogs.contains("bodyPreview"));
        // 不含敏感请求/响应原始数据
        assertFalse(serverLogs.contains("mediaParam={}"));
        assertFalse(serverLogs.contains("API 返回数据: {}"));
        assertFalse(serverLogs.contains("请求 URL: {}"));
        // getRequestURI() 只包含路径；完整 URL 才可能泄露主机与查询参数。
        assertFalse(serverLogs.contains("request.getRequestURL()"));
        // 不含 Open-Meteo 请求参数或响应正文
        assertFalse(serverLogs.contains("requestedLocation"));
        assertFalse(serverLogs.contains("response.body().string()"));
        assertFalse(serverLogs.contains("request.url()"));
    }

    @Test
    void identifiersAreMaskedWhileAdminPageContentRemainsAvailable() throws Exception {
        String botService = source("Service/BotService.java");
        String aiService = source("Service/AIService.java");
        String controller = source("controller/BotController.java");

        assertTrue(botService.contains("maskUserId(fromUser)"));
        assertTrue(botService.contains("maskToken(contextToken)"));
        assertTrue(aiService.contains("maskUserId(userId)"));
        assertTrue(controller.contains("maskUserId(fromUser)"));

        assertTrue(botService.contains("displayLog(fromUser + \": \" + text)"));
        assertTrue(botService.contains("displayLog(\"回复 -> \" + toUserId + \": \" + text)"));
        assertTrue(controller.contains("safeMessage.put(\"content\", msg.content)"));
    }

    private String source(String relative) throws IOException {
        return Files.readString(Path.of("src/main/java/com/demo/demo").resolve(relative));
    }

    private String serverLogs(String sources) {
        Pattern pattern = Pattern.compile("log\\.(?:trace|debug|info|warn|error)\\((?s:.*?)\\);");
        Matcher matcher = pattern.matcher(sources);
        StringBuilder logs = new StringBuilder();
        while (matcher.find()) {
            logs.append(matcher.group()).append('\n');
        }
        return logs.toString();
    }
}
