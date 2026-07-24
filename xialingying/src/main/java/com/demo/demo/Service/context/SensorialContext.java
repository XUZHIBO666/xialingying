package com.demo.demo.Service.context;

import java.util.concurrent.TimeUnit;

/**
 * 单个用户的感知上下文：最近一次图片识别描述 + 最近一次语音转写文本。
 * 超过 5 分钟未被刷新的上下文自动过期。
 */
public class SensorialContext {

    private static final long TTL_MS = TimeUnit.MINUTES.toMillis(5);

    private String lastImageDescription;
    private long lastImageTime;

    private String lastVoiceTranscript;
    private long lastVoiceTime;

    public void recordImage(String description) {
        this.lastImageDescription = description;
        this.lastImageTime = System.currentTimeMillis();
    }

    public void recordVoice(String transcript) {
        this.lastVoiceTranscript = transcript;
        this.lastVoiceTime = System.currentTimeMillis();
    }

    /**
     * 构建注入 LLM system prompt 的上下文提示文本。
     * 过期的上下文会被自动忽略。
     */
    public String buildContextHint() {
        StringBuilder sb = new StringBuilder();
        long now = System.currentTimeMillis();

        if (lastImageDescription != null
                && now - lastImageTime < TTL_MS) {
            sb.append("用户刚才发了一张图片，内容是：")
                    .append(lastImageDescription).append("\n");
        }
        if (lastVoiceTranscript != null
                && now - lastVoiceTime < TTL_MS) {
            sb.append("用户刚才发了一条语音，内容是：")
                    .append(lastVoiceTranscript).append("\n");
        }
        return sb.toString();
    }

    /** 清除过期数据后返回是否完全为空。 */
    public boolean isEmpty() {
        long now = System.currentTimeMillis();
        boolean imageExpired = lastImageDescription == null
                || now - lastImageTime >= TTL_MS;
        boolean voiceExpired = lastVoiceTranscript == null
                || now - lastVoiceTime >= TTL_MS;
        return imageExpired && voiceExpired;
    }
}
