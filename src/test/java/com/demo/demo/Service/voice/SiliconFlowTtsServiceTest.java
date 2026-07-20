package com.demo.demo.Service.voice;

import com.demo.demo.config.VoiceProperties;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SiliconFlowTtsServiceTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void synthesizes16kPcmWithConfiguredVoice() throws Exception {
        byte[] expectedPcm = new byte[]{1, 0, 2, 0};
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<JsonObject> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/audio/speech", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(JsonParser.parseString(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            send(exchange, 200, expectedPcm);
        });
        server.start();

        VoiceProperties properties = new VoiceProperties();
        properties.getTts().setApiKey("test-key");
        properties.getTts().setApiUrl("http://127.0.0.1:" + server.getAddress().getPort());
        SiliconFlowTtsService service = new SiliconFlowTtsService(properties, new OkHttpClient());

        byte[] actual = service.synthesize("你好");

        assertArrayEquals(expectedPcm, actual);
        assertEquals("Bearer test-key", authorization.get());
        assertEquals("FunAudioLLM/CosyVoice2-0.5B", requestBody.get().get("model").getAsString());
        assertEquals("FunAudioLLM/CosyVoice2-0.5B:anna", requestBody.get().get("voice").getAsString());
        assertEquals("你好", requestBody.get().get("input").getAsString());
        assertEquals("pcm", requestBody.get().get("response_format").getAsString());
        assertEquals(16000, requestBody.get().get("sample_rate").getAsInt());
        assertEquals(false, requestBody.get().get("stream").getAsBoolean());
    }

    private void send(HttpExchange exchange, int code, byte[] body) throws IOException {
        exchange.sendResponseHeaders(code, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
