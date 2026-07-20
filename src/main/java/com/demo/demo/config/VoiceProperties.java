package com.demo.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "ai.voice")
public class VoiceProperties {

    private final Asr asr = new Asr();
    private final Tts tts = new Tts();
    private final Audio audio = new Audio();

    public Asr getAsr() {
        return asr;
    }

    public Audio getAudio() {
        return audio;
    }

    public Tts getTts() {
        return tts;
    }

    public static class Asr {
        private String apiKey = "";
        private String apiUrl = "https://api.siliconflow.cn";
        private String model = "FunAudioLLM/SenseVoiceSmall";
        private Duration timeout = Duration.ofSeconds(60);

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getApiUrl() {
            return apiUrl;
        }

        public void setApiUrl(String apiUrl) {
            this.apiUrl = apiUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    public static class Tts {
        private String apiKey = "";
        private String apiUrl = "https://api.siliconflow.cn";
        private String model = "FunAudioLLM/CosyVoice2-0.5B";
        private String voice = "FunAudioLLM/CosyVoice2-0.5B:anna";
        private Duration timeout = Duration.ofSeconds(60);

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getApiUrl() { return apiUrl; }
        public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getVoice() { return voice; }
        public void setVoice(String voice) { this.voice = voice; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
    }

    public static class Audio {
        private String silkDecoderPath = "";
        private String silkEncoderPath = "";
        private Duration processTimeout = Duration.ofSeconds(30);

        public String getSilkDecoderPath() {
            return silkDecoderPath;
        }

        public void setSilkDecoderPath(String silkDecoderPath) {
            this.silkDecoderPath = silkDecoderPath;
        }

        public String getSilkEncoderPath() {
            return silkEncoderPath;
        }

        public void setSilkEncoderPath(String silkEncoderPath) {
            this.silkEncoderPath = silkEncoderPath;
        }

        public Duration getProcessTimeout() {
            return processTimeout;
        }

        public void setProcessTimeout(Duration processTimeout) {
            this.processTimeout = processTimeout;
        }
    }
}
