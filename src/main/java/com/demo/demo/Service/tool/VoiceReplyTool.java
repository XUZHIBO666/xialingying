package com.demo.demo.Service.tool;

import com.demo.demo.Service.voice.TtsService;
import jakarta.annotation.Resource;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class VoiceReplyTool {
    @Resource
    private TtsService ttsService;
    private final ThreadLocal<byte[]> lastAudio = new ThreadLocal<>();
    @Tool(description = "将指定文本合成为语音（TTS），用于语音回复用户。" +
            "当用户明确说「用语音回复」「读出来」「讲给我听」「念一下」「播报」" +
            "「发语音」「说给我听」等任何想要'听'回复的表达时调用此工具。" +
            "参数 text 是你准备回复给用户的完整文本内容（纯中文，不含标记）。" +
            "调用后系统会自动发送语音，你只需简短告知用户「已用语音回复」即可。")
    public String voiceReply(
            @ToolParam(description = "要朗读给用户的完整文本") String text) {
        try {
            if (!ttsService.isConfigured()) {
                return "语音功能未配置，请用文字回复用户";
            }
            byte[] audio = ttsService.synthesize(text);
            lastAudio.set(audio);
            return "语音已合成，大小 " + (audio.length / 1024) + " KB";
        } catch (Exception e) {
            return "语音合成失败: " + e.getMessage() + "，请用文字回复用户";
        }
    }
    /** 取出当前线程最近一次合成的语音，取后清空。 */

    public byte[] takeLastAudio() {
        byte[] audio = lastAudio.get();
        lastAudio.remove();
        return audio;
    }

}

