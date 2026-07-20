package com.demo.demo.Service.voice;

import com.demo.demo.config.VoiceProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiliconFlowAsrServiceTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsMultipartWavAndReturnsText() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/audio/transcriptions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            send(exchange, 200, "{\"text\":\"你好，世界\"}");
        });
        server.start();

        VoiceProperties properties = new VoiceProperties();
        properties.getAsr().setApiKey("test-key");
        properties.getAsr().setApiUrl("http://127.0.0.1:" + server.getAddress().getPort());
        SiliconFlowAsrService service = new SiliconFlowAsrService(properties, new OkHttpClient());

        byte[] pcm = new byte[]{1, 0, 2, 0};
        String result = service.transcribe(pcm);

        assertEquals("你好，世界", result);
        assertEquals("Bearer test-key", authorization.get());
        assertTrue(requestBody.get().contains("FunAudioLLM/SenseVoiceSmall"));
        assertTrue(requestBody.get().contains("voice.wav"));
        assertTrue(requestBody.get().contains("RIFF"));
        assertTrue(requestBody.get().contains("WAVEfmt "));
        assertTrue(requestBody.get().contains("data"));
    }

    private void send(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
