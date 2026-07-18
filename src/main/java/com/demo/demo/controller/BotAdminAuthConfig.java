package com.demo.demo.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Supplier;

@Configuration
public class BotAdminAuthConfig implements WebMvcConfigurer {

    static final String ADMIN_TOKEN_ENV = "BOT_ADMIN_TOKEN";
    static final String ADMIN_TOKEN_HEADER = "X-Bot-Admin-Token";

    private final Environment environment;

    public BotAdminAuthConfig(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new BotAdminTokenInterceptor(() -> environment.getProperty(ADMIN_TOKEN_ENV)))
                .addPathPatterns("/bot", "/bot/**");
    }

    static class BotAdminTokenInterceptor implements HandlerInterceptor {
        private final Supplier<String> configuredTokenSupplier;

        BotAdminTokenInterceptor(Supplier<String> configuredTokenSupplier) {
            this.configuredTokenSupplier = configuredTokenSupplier;
        }

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                throws Exception {
            String configuredToken = configuredTokenSupplier.get();
            String requestToken = request.getHeader(ADMIN_TOKEN_HEADER);
            if (requestToken == null || requestToken.isBlank()) {
                requestToken = request.getParameter("adminToken");
            }
            if (requestToken == null || requestToken.isBlank()) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return false;
            }
            if (configuredToken == null || configuredToken.isBlank()
                    || !tokenEquals(configuredToken, requestToken)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return false;
            }
            return true;
        }

        private boolean tokenEquals(String expected, String actual) {
            byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
            byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(expectedBytes, actualBytes);
        }
    }
}
