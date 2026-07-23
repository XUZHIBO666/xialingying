package com.demo.demo.controller;

import com.demo.demo.Service.AIService;
import com.demo.demo.Service.BotService;
import com.demo.demo.Service.ImageGenerationService;
import com.demo.demo.Service.ImageRecognitionService;
import com.demo.demo.Service.context.ContextManager;
import com.demo.demo.Service.voice.VoiceMessageHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bot 控制器：管理微信 Bot 页面、状态、消息收发
 */
@Slf4j
@Controller
@RequestMapping("/bot")
public class BotController {

    @Resource
    private BotService botService;

    @Resource
    private AIService aiService;

    @Resource
    private ImageGenerationService imageGenerationService;

    @Resource
    private ImageRecognitionService imageRecognitionService;

    @Resource
    private VoiceMessageHandler voiceMessageHandler;

    @Resource
    private ContextManager contextManager;

    // ==================== 初始化：设置自动回复 ====================

    @PostConstruct
    public void initAutoReply() {
        log.info("[BotController] 初始化自动回复处理器...");
        botService.setAutoReply((fromUser, contextToken, text) -> {
            log.info("[自动回复] 收到消息 from={} contextToken={} textLength={}",
                maskUserId(fromUser), maskToken(contextToken),
                text == null ? 0 : text.length());

            // 图片生成：AI 意图判断（同 others 的实现方式，比正则更灵活）
            if (imageGenerationService.isConfigured()) {
                try {
                    String intentPrompt = "用户说：「" + text + "」\n"
                            + "请判断用户是否想让你生成一张图片。\n"
                            + "如果想：回复格式为「YES|提示词」，例如「YES|一只可爱的小狗在草地上奔跑」。\n"
                            + "如果不想：只回复一个词「NO」。\n"
                            + "注意：必须以 YES| 或 NO 开头，不要加任何其他内容。";
                    String aiResponse = aiService.chat(fromUser + "_img_intent", intentPrompt);

                    if (aiResponse != null && aiResponse.startsWith("YES|") && aiResponse.length() > 4) {
                        String prompt = aiResponse.substring(4).trim();
                        try {
                            byte[] imageBytes = imageGenerationService.generateImage(prompt);
                            boolean sent = botService.sendImageReply(fromUser, contextToken, imageBytes);
                            return sent ? null : "图片已生成，但发送失败了，请稍后再试";
                        } catch (Exception e) {
                            log.error("[自动回复] 图片生成失败", e);
                            return "抱歉，图片生成失败了，请稍后再试";
                        }
                    }
                } catch (Exception e) {
                    log.warn("[自动回复] 图片意图判断失败，跳过: {}", e.getMessage());
                }
            }

            // 语音回复：先判断意图，再生成内容
            if (voiceMessageHandler != null && aiService.isConfigured()) {
                try {
                    String voiceIntentPrompt = "用户说：「" + text + "」\n"
                            + "请判断用户是否希望用语音/朗读/播报的方式听取回复。\n"
                            + "如果用户提到了「语音」「声音」「音频」「朗读」「读出来」「读一下」"
                            + "「讲给我听」「用语音」「播报」「念」「说给我听」「讲一段」"
                            + "「发语音」「念出来」「说出来」「讲出来」等听相关表达 → 回复 YES\n"
                            + "否则 → 回复 NO\n"
                            + "注意：只输出 YES 或 NO，不要解释。";
                    String aiVoiceResponse = aiService.chat(fromUser + "_voice_intent", voiceIntentPrompt);

                    if (aiVoiceResponse != null && aiVoiceResponse.trim().equalsIgnoreCase("YES")) {
                        log.info("[自动回复] AI判断用户想要语音回复");
                        // 第二步：用正常的 LLM 对话流生成完整回复（含工具调用）
                        String fullReply = aiService.chatWithTools(fromUser, text);
                        if (fullReply != null && !fullReply.isBlank()) {
                            try {
                                byte[] mp3Bytes = voiceMessageHandler.synthesize(fullReply);
                                if (mp3Bytes != null) {
                                    boolean sent = botService.sendVoiceReply(fromUser, contextToken, mp3Bytes);
                                    if (sent) {
                                        log.info("[自动回复] 语音回复发送成功 from={}", maskUserId(fromUser));
                                        return null; // 语音已发送
                                    }
                                    log.warn("[自动回复] 语音发送失败，降级文字");
                                }
                            } catch (Exception e) {
                                log.error("[自动回复] TTS 合成失败", e);
                            }
                            // TTS 失败降级：返回文字内容
                            return "🎵（语音生成失败，以下是文字版）\n\n" + fullReply;
                        }
                    }
                } catch (Exception e) {
                    log.warn("[自动回复] 语音意图判断失败，跳过: {}", e.getMessage());
                }
            }

            if (!aiService.isConfigured()) {
                log.warn("[自动回复] AI 未配置，返回提示");
                return "AI 未配置，请联系管理员";
            }

            // -- LLM 对话（含 Function Calling） --
            // LLM 自动决定是否调用工具：天气/时间/搜索/图片生成等
            String aiReply = aiService.chatWithTools(fromUser, text);
            if (aiReply != null) return aiReply;

            return "（AI 暂时无响应，请稍后再试）";
        });

        // 图片消息不进入普通文本对话，单独交给视觉模型识别后回复。
        botService.setImageReply((fromUser, contextToken, imageBytes) -> {
            log.info("[自动回复] 收到图片 from={} contextToken={} size={} bytes",
                    maskUserId(fromUser), maskToken(contextToken),
                    imageBytes == null ? 0 : imageBytes.length);
            if (!imageRecognitionService.isConfigured()) {
                return "图片识别未配置，请管理员设置 VISION_API_KEY 或 IMAGE_API_KEY";
            }
            String description = imageRecognitionService.recognize(imageBytes);
            if (description != null && !description.isBlank()) {
                contextManager.recordImage(fromUser, description.trim());
            }
            return description;
        });
        botService.setVoiceMessageHandler(voiceMessageHandler);
        log.info("[BotController] 自动回复处理器初始化完成");
    }

    // ==================== 页面 ====================

    @GetMapping
    public String botPage() {
        if (!botService.isLoggedIn()) {
            log.info("[BotController] 未登录，启动登录流程");
            botService.startLogin();
        }
        return "bot";
    }

    // ==================== REST API ====================

    @GetMapping("/status")
    @ResponseBody
    public Map<String, Object> status() {
        Map<String, Object> map = new HashMap<>();
        map.put("loggedIn", botService.isLoggedIn());
        map.put("status", botService.getStatusText());
        map.put("qrCodeBase64", botService.getQrCodeBase64());
        map.put("qrCodeUrl", botService.getQrCodeUrl());
        return map;
    }

    @GetMapping("/messages")
    @ResponseBody
    public Map<String, Object> messages() {
        Map<String, Object> map = new HashMap<>();
        List<Map<String, Object>> safeMessages = new ArrayList<>();
        for (BotService.Msg msg : botService.pollMessages()) {
            Map<String, Object> safeMessage = new HashMap<>();
            safeMessage.put("fromUser", msg.fromUser);
            safeMessage.put("replyId", msg.replyId);
            safeMessage.put("content", msg.content);
            safeMessage.put("time", msg.time);
            safeMessages.add(safeMessage);
        }
        map.put("messages", safeMessages);
        map.put("logs", botService.getLogs());
        return map;
    }

    @PostMapping("/send")
    @ResponseBody
    public Map<String, Object> send(@RequestParam String replyId,
                                     @RequestParam String text) {
        log.info("[BotController] 手动发送消息 replyId={} textLength={}",
                replyId, text == null ? 0 : text.length());
        boolean sent = botService.sendManualReply(replyId, text);
        Map<String, Object> map = new HashMap<>();
        map.put("ok", sent);
        return map;
    }

    @PostMapping("/restart")
    @ResponseBody
    public Map<String, Object> restart() {
        log.info("[BotController] 重新获取二维码");
        botService.restartLogin();
        Map<String, Object> map = new HashMap<>();
        map.put("ok", true);
        return map;
    }

    // ==================== 工具方法 ====================

    private static String maskToken(String token) {
        if (token == null || token.isBlank()) return "null";
        if (token.length() <= 8) return "***";
        return token.substring(0, 4) + "..." + token.substring(token.length() - 4);
    }

    private static String maskUserId(String userId) {
        if (userId == null || userId.length() < 9) return "***";
        return userId.substring(0, 4) + "..." + userId.substring(userId.length() - 4);
    }
}
