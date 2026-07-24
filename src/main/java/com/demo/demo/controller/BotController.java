package com.demo.demo.controller;

import com.demo.demo.Service.*;
import com.demo.demo.Service.context.ContextManager;
import com.demo.demo.Service.tool.ImageGenerationTool;
import com.demo.demo.Service.tool.VoiceReplyTool;
import com.demo.demo.Service.voice.VoiceMessageHandler;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Bot 控制器：管理微信 Bot 页面、状态、消息收发
 */
@Slf4j
@Controller
@RequestMapping("/bot")
public class BotController {
    @Resource
    private ImageGenerationTool imageGenerationTool;

    @Resource
    private VoiceReplyTool voiceReplyTool;

    @Resource
    private BotService botService;

    @Resource
    private MultiBotManager multiBotManager;

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
        log.info("[BotController] 初始化多Bot自动回复处理器...");

        multiBotManager.setSharedAutoReply((fromUser, contextToken, text) -> {
            log.info("[自动回复] 收到消息 from={} contextToken={} textLength={}",
                    maskUserId(fromUser), maskToken(contextToken),
                    text == null ? 0 : text.length());

            if (!aiService.isConfigured()) {
                return "AI 未配置，请联系管理员";
            }

            // -- 工具1：查时间 --
            if (text.contains("几点") || text.contains("时间") || text.contains("日期")) {
                String now = LocalDateTime.now()
                        .format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss"));
                String prompt = "用户问: \"" + text + "\"\n"
                        + "当前精确时间是: " + now + "\n"
                        + "请用一句话告诉用户现在的时间。";
                String reply = aiService.chat(fromUser, prompt);
                if (reply != null) return reply;
            }

            // -- 普通对话（ReactAgent 内置工具调用） --
            String aiReply = aiService.chat(fromUser, text);
            if (aiReply == null || aiReply.isBlank()) {
                return "AI回复为空，请稍后再试";
            }
            byte[] imageBytes = imageGenerationTool.takeLastImage();
            if (imageBytes != null) {
                multiBotManager.getDefaultBot().sendImageReply(fromUser, contextToken, imageBytes);
            }

            byte[] audioBytes = voiceReplyTool.takeLastAudio();
            if (audioBytes != null) {
                boolean sent = multiBotManager.getDefaultBot().sendVoiceReply(fromUser, contextToken, audioBytes);
                if (sent) {
                    log.info("[自动回复] 语音回复发送成功 from={}", maskUserId(fromUser));
                    return null;
                }
                log.warn("[自动回复] 语音发送失败，降级文字");
            }

            return aiReply;
        });

 multiBotManager.setSharedImageReply((fromUser, contextToken, imageBytes) -> {
        log.info("[自动回复] 收到图片 from={} contextToken={} size={} bytes",
                maskUserId(fromUser), maskToken(contextToken),
                imageBytes == null ? 0 : imageBytes.length);
        if (!imageRecognitionService.isConfigured()) {
            return "图片识别未配置，请管理员设置 VISION_API_KEY 或 IMAGE_API_KEY";
        }
        String description = imageRecognitionService.recognize(imageBytes);
        if (description == null || description.isBlank()) {
            return "抱歉，我没有识别到这张图片的内容";
        }
        contextManager.recordImage(fromUser, description.trim());
        if (aiService.isConfigured()){
            String prompt = "用户发了一张图片，图片内容是：「" + description + "」\n"
                    + "请根据图片内容用中文简短回复用户。如果用户之前问了关于图片的问题，请一并回答。";
            String aiReply = aiService.chat(fromUser,prompt);
            if (aiReply!=null && !aiReply.isBlank()){
                byte[] newImage = imageGenerationTool.takeLastImage();
                if (newImage !=null){
                    multiBotManager.getDefaultBot().sendImageReply(fromUser,contextToken,newImage);
                }
                byte[] audio = voiceReplyTool.takeLastAudio();
                if (audio != null) {
                    multiBotManager.getDefaultBot().sendVoiceReply(fromUser, contextToken, audio);
                }
                return aiReply;
            }

        }
        return  "📷 " + description;
    });

        multiBotManager.setSharedVoiceHandler(voiceMessageHandler);
        log.info("[BotController] 多Bot自动回复处理器初始化完成");
}

    // ==================== 页面 ====================


    @GetMapping
    public String botPage() {
        BotInstance defaultBot = multiBotManager.getDefaultBot();
        if (!defaultBot.isLoggedIn()) {
            log.info("[BotController] 默认Bot未登录，启动登录流程");
            defaultBot.startLogin();
        }
        return "bot";
    }


    // ==================== REST API ====================

    @GetMapping("/status")
    @ResponseBody
    public Map<String, Object> status(@RequestParam(required = false) String botId) {
        BotInstance bot = resolveBot(botId);
        Map<String, Object> map = new HashMap<>();
        map.put("loggedIn", bot.isLoggedIn());
        map.put("status", bot.getStatusText());
        map.put("qrCodeBase64", bot.getQrCodeBase64());
        map.put("qrCodeUrl", bot.getQrCodeUrl());
        map.put("instanceId", bot.getInstanceId());
        map.put("displayName", bot.getDisplayName());
        return map;
    }

    @GetMapping("/instances")
    @ResponseBody
    public List<Map<String, Object>> listInstances() {
        return multiBotManager.getAllBots().stream().map(bot -> {
            Map<String, Object> info = new HashMap<>();
            info.put("instanceId", bot.getInstanceId());
            info.put("displayName", bot.getDisplayName());
            info.put("loggedIn", bot.isLoggedIn());
            info.put("status", bot.getStatusText());
            info.put("replyQueueSize", bot.getReplyQueueSize());
            return info;
        }).collect(Collectors.toList());
    }

    @PostMapping("/instances")
    @ResponseBody
    public Map<String, Object> createInstance(@RequestParam(required = false) String displayName) {
        BotInstance newBot = multiBotManager.createBot(displayName);
        Map<String, Object> map = new HashMap<>();
        map.put("ok", true);
        map.put("instanceId", newBot.getInstanceId());
        map.put("displayName", newBot.getDisplayName());
        return map;
    }

    @DeleteMapping("/instances/{instanceId}")
    @ResponseBody
    public Map<String, Object> removeInstance(@PathVariable String instanceId) {
        multiBotManager.removeBot(instanceId);
        Map<String, Object> map = new HashMap<>();
        map.put("ok", true);
        return map;
    }

    @GetMapping("/messages")
    @ResponseBody
    public Map<String, Object> messages(@RequestParam(required = false) String botId) {
        BotInstance bot = resolveBot(botId);
        Map<String, Object> map = new HashMap<>();
        List<Map<String, Object>> safeMessages = new ArrayList<>();
        for (BotInstance.Msg msg : bot.pollMessages()) {
            Map<String, Object> safeMessage = new HashMap<>();
            safeMessage.put("fromUser", msg.fromUser);
            safeMessage.put("replyId", msg.replyId);
            safeMessage.put("content", msg.content);
            safeMessage.put("time", msg.time);
            safeMessages.add(safeMessage);
        }
        map.put("messages", safeMessages);
        map.put("logs", bot.getLogs());
        map.put("instanceId", bot.getInstanceId());
        return map;
    }

    @PostMapping("/send")
    @ResponseBody
    public Map<String, Object> send(@RequestParam String replyId,
                                    @RequestParam String text,
                                    @RequestParam(required = false) String botId) {
        log.info("[BotController] 手动发送消息 replyId={} textLength={}",
                replyId, text == null ? 0 : text.length());
        BotInstance bot = resolveBot(botId);
        boolean sent = bot.sendManualReply(replyId, text);
        Map<String, Object> map = new HashMap<>();
        map.put("ok", sent);
        return map;
    }

    @PostMapping("/restart")
    @ResponseBody
    public Map<String, Object> restart(@RequestParam(required = false) String botId) {
        log.info("[BotController] 重新获取二维码");
        BotInstance bot = resolveBot(botId);
        bot.restartLogin();
        Map<String, Object> map = new HashMap<>();
        map.put("ok", true);
        return map;
    }

    @PostMapping("/start-all")
    @ResponseBody
    public Map<String, Object> startAll() {
        log.info("[BotController] 启动所有Bot实例");
        multiBotManager.startAllBots();
        Map<String, Object> map = new HashMap<>();
        map.put("ok", true);
        map.put("botCount", multiBotManager.getBotCount());
        return map;
    }

    // ==================== 工具方法 ====================

    private BotInstance resolveBot(String botId) {
        if (botId == null || botId.isBlank()) {
            return multiBotManager.getDefaultBot();
        }
        BotInstance bot = multiBotManager.getBot(botId);
        if (bot == null) {
            throw new IllegalArgumentException("Bot实例不存在: " + botId);
        }
        return bot;
    }

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
