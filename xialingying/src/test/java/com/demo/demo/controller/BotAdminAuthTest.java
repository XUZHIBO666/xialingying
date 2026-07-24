package com.demo.demo.controller;

import com.demo.demo.Service.BotService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BotAdminAuthTest {

    @Test
    void botStatusWithoutTokenReturnsUnauthorized() throws Exception {
        mockMvc().perform(get("/bot/status"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void botStatusWithWrongTokenReturnsForbidden() throws Exception {
        mockMvc().perform(get("/bot/status")
                        .header("X-Bot-Admin-Token", "wrong-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void botStatusWithCorrectTokenCanAccess() throws Exception {
        mockMvc().perform(get("/bot/status")
                        .header("X-Bot-Admin-Token", "test-admin-token"))
                .andExpect(status().isOk());
    }

    @Test
    void botStatusWithCorrectQueryTokenCanAccess() throws Exception {
        mockMvc().perform(get("/bot/status")
                        .param("adminToken", "test-admin-token"))
                .andExpect(status().isOk());
    }

    @Test
    void botPageWithoutTokenReturnsUnauthorizedBeforeStartingLogin() throws Exception {
        mockMvc().perform(get("/bot"))
                .andExpect(status().isUnauthorized());
    }

    private MockMvc mockMvc() {
        BotService botService = mock(BotService.class);
        when(botService.getStatusText()).thenReturn("ok");

        BotController controller = new BotController();
        ReflectionTestUtils.setField(controller, "botService", botService);

        return MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new BotAdminAuthConfig.BotAdminTokenInterceptor(() -> "test-admin-token"))
                .build();
    }
}
