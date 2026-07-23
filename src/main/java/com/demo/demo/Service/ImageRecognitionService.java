package com.demo.demo.Service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import java.io.IOException;
import java.util.List;

/**
 * 图片识别服务 —— OpenAiApi + OpenAiChatModel → 百炼 compatible-mode 端点。
 *
 * <p>百炼原生 API 不支持 Data URI，但 compatible-mode/v1 端点走 OpenAI 协议，
 * OpenAiChatModel 将 byte[] 序列化为 image_url + data URI，百炼兼容端点能识别。
 */
@Slf4j
@Service
public class ImageRecognitionService {

    /** 包装 OpenAiChatModel，和参考代码一致 */
    private ChatModel chatModel;

    @Value("${spring.ai.dashscope.api-key:}")
    private String apiKey;

    @Value("${ai.vision.model:qwen-vl-max}")
    private String model;

    @Value("${ai.vision.prompt:请用中文简洁描述这张图片的主要内容，如果图片中有文字也请读出来。}")
    private String prompt;

    @PostConstruct
    public void init() {
        // compatible-mode：百炼 OpenAI 兼容端点，OpenAiApi 内部自动追加 /v1/chat/completions
        OpenAiApi api = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode")
                .build();

        this.chatModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model)
                        .build())
                .build();

        log.info("[图片识别] 初始化完成（OpenAiApi 兼容模式） model={}", model);
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String recognize(byte[] imageBytes) throws IOException {
        if (!isConfigured()) {
            throw new IOException("图片识别 API 未配置");
        }
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IOException("图片内容为空");
        }

        // 检测 MIME 类型
        String mimeType = detectImageMimeType(imageBytes);

        // 参照参考代码：Media.data(byte[]) 直传，OpenAiChatModel 序列化成 image_url + data URI
        UserMessage userMessage = UserMessage.builder()
                .text(prompt.trim())
                .media(List.of(
                        Media.builder()
                                .mimeType(MimeTypeUtils.parseMimeType(mimeType))
                                .data(imageBytes)   // ← byte[] 直传，不转 base64！
                                .build()
                ))
                .build();

        try {
            String reply = chatModel.call(new Prompt(userMessage,
                            OpenAiChatOptions.builder().build()))
                    .getResult()
                    .getOutput()
                    .getText();

            log.info("[图片识别] 成功 replyLength={} reply={}",
                    reply == null ? 0 : reply.length(), reply);
            return reply == null ? "" : reply.trim();
        } catch (Exception e) {
            log.error("[图片识别] 失败: {}", e.getMessage(), e);
            throw new IOException("图片识别失败: " + e.getMessage(), e);
        }
    }

    // ==================== MIME 检测 ====================

    private String detectImageMimeType(byte[] imageBytes) throws IOException {
        if (imageBytes.length >= 3
                && (imageBytes[0] & 0xFF) == 0xFF
                && (imageBytes[1] & 0xFF) == 0xD8
                && (imageBytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (imageBytes.length >= 8
                && (imageBytes[0] & 0xFF) == 0x89
                && imageBytes[1] == 0x50 && imageBytes[2] == 0x4E && imageBytes[3] == 0x47
                && imageBytes[4] == 0x0D && imageBytes[5] == 0x0A
                && imageBytes[6] == 0x1A && imageBytes[7] == 0x0A) {
            return "image/png";
        }
        if (imageBytes.length >= 12
                && imageBytes[0] == 'R' && imageBytes[1] == 'I'
                && imageBytes[2] == 'F' && imageBytes[3] == 'F'
                && imageBytes[8] == 'W' && imageBytes[9] == 'E'
                && imageBytes[10] == 'B' && imageBytes[11] == 'P') {
            return "image/webp";
        }
        throw new IOException("不支持的图片格式，仅支持 JPEG、PNG、WebP");
    }
}
