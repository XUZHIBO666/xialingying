package com.demo.demo.controller;

import com.demo.demo.Service.AIService;
import com.demo.demo.Service.BotService;
import com.demo.demo.Service.memory.ConversationMemoryStore;
import com.demo.demo.config.VoiceProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BotHealthControllerTest {

    private MockMvc mockMvc;
    private BotService botService;
    private AIService aiService;
    private ConversationMemoryStore memoryStore;

    @BeforeEach
    void setUp() {
        botService = mock(BotService.class);
        aiService = mock(AIService.class);
        memoryStore = mock(ConversationMemoryStore.class);

        BotHealthController controller = new BotHealthController();
        ReflectionTestUtils.setField(controller, "botService", botService);
        ReflectionTestUtils.setField(controller, "aiService", aiService);
        ReflectionTestUtils.setField(controller, "voiceProperties", voiceProperties());
        ReflectionTestUtils.setField(controller, "memoryStore", memoryStore);

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static VoiceProperties voiceProperties() {
        VoiceProperties props = new VoiceProperties();
        props.getAsr().setApiKey("");
        props.getTts().setApiKey("");
        return props;
    }

    @Test
    void livenessAlwaysUp() throws Exception {
        mockMvc.perform(get("/bot/health/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.timestamp").isNumber());
    }

    @Test
    void readinessChecksIlinkLogin() throws Exception {
        when(botService.isLoggedIn()).thenReturn(true);
        when(botService.getReplyQueueSize()).thenReturn(1);
        when(botService.getReplyQueueCapacity()).thenReturn(200);
        when(memoryStore.getUserCount()).thenReturn(0);

        mockMvc.perform(get("/bot/health/ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.checks[0].name").value("ilinkLogin"))
                .andExpect(jsonPath("$.checks[0].status").value("UP"));
    }

    @Test
    void readinessDownWhenNotLoggedIn() throws Exception {
        when(botService.isLoggedIn()).thenReturn(false);
        when(botService.getReplyQueueSize()).thenReturn(0);
        when(botService.getReplyQueueCapacity()).thenReturn(200);
        when(memoryStore.getUserCount()).thenReturn(0);

        mockMvc.perform(get("/bot/health/ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DOWN"));
    }

    @Test
    void metricsReturnsValues() throws Exception {
        when(botService.getReplyQueueSize()).thenReturn(3);
        when(botService.getReplyQueueCapacity()).thenReturn(200);
        when(botService.getRateLimiterBucketCount()).thenReturn(5);
        when(botService.getTotalRateLimitAccepted()).thenReturn(100L);
        when(botService.getTotalRateLimitRejected()).thenReturn(2L);
        when(memoryStore.getUserCount()).thenReturn(12);

        mockMvc.perform(get("/bot/health/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replyQueue.size").value(3))
                .andExpect(jsonPath("$.rateLimiter.activeBuckets").value(5))
                .andExpect(jsonPath("$.memory.userCount").value(12))
                .andExpect(jsonPath("$.jvm.maxMemoryMB").isNumber());
    }

    @Test
    void healthIncludesOptionals() throws Exception {
        when(botService.isLoggedIn()).thenReturn(true);
        when(botService.getReplyQueueSize()).thenReturn(0);
        when(botService.getReplyQueueCapacity()).thenReturn(200);
        when(memoryStore.getUserCount()).thenReturn(0);
        when(aiService.isConfigured()).thenReturn(false);

        mockMvc.perform(get("/bot/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.optionals[0].name").value("aiApi"))
                .andExpect(jsonPath("$.uptime").isString());
    }
}
