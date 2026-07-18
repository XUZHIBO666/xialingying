package com.demo.demo.controller;

import com.demo.demo.Service.AIService;
import com.demo.demo.Service.BotService;
import com.demo.demo.Service.ImageGenerationService;
import com.demo.demo.Service.ImageRecognitionService;
import com.demo.demo.Utils.WeatherUtil;
import com.demo.demo.execption.BizException;
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

    // ==================== 初始化：设置自动回复 ====================

    @PostConstruct
    public void initAutoReply() {
        log.info("[BotController] 初始化自动回复处理器...");
        botService.setAutoReply((fromUser, contextToken, text) -> {
            log.info("[自动回复] 收到消息 from={} contextToken={} text={}", fromUser, maskToken(contextToken), text);

            // 图片生成优先级最高，避免“生成图片...”被普通聊天模型当成闲聊处理。
            if (imageGenerationService.isImageRequest(text)) {
                String prompt = imageGenerationService.extractPrompt(text);
                if (prompt == null) {
                    return "请告诉我想画什么，例如：生成图片：一只穿宇航服的猫";
                }
                if (!imageGenerationService.isConfigured()) {
                    return "图片生成未配置，请管理员设置 IMAGE_API_KEY";
                }
                try {
                    byte[] imageBytes = imageGenerationService.generateImage(prompt);
                    boolean sent = botService.sendImageReply(fromUser, contextToken, imageBytes);
                    return sent ? null : "图片已生成，但发送失败了，请稍后再试";
                } catch (Exception e) {
                    log.error("[自动回复] 图片生成失败", e);
                    return "抱歉，图片生成失败了：" + e.getMessage();
                }
            }

            if (!aiService.isConfigured()) {
                log.warn("[自动回复] AI 未配置，返回提示");
                return "AI 未配置，请联系管理员";
            }

            // -- 工具1：查天气 --
            if (text.contains("天气")) {
                String city = extractCity(text);
                if (city != null) {
                    try {
                        log.info("[自动回复] 查询天气，城市: {}", city);
                        String weather = WeatherUtil.getWeather(city);
                        String prompt = "用户问: \"" + text + "\"\n"
                                + "以下是实时天气数据: " + weather + "\n"
                                + "请用自然的中文把这天气数据告诉用户，两句话以内。";
                        String reply = aiService.chat(fromUser, prompt);
                        if (reply != null) return reply;
                    } catch (BizException e) {
                        log.warn("[自动回复] 天气查询失败: code={}, msg={}", e.getCode(), e.getMessage());
                        return "抱歉，" + e.getMessage();
                    } catch (Exception e) {
                        log.error("[自动回复] 天气查询异常", e);
                        return "抱歉，查询「" + city + "」的天气时出错了，请稍后再试";
                    }
                }
            }

            // -- 工具2：查时间 --
            if (text.contains("几点") || text.contains("时间") || text.contains("日期")) {
                String now = LocalDateTime.now()
                        .format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss"));
                String prompt = "用户问: \"" + text + "\"\n"
                        + "当前精确时间是: " + now + "\n"
                        + "请用一句话告诉用户现在的时间。";
                String reply = aiService.chat(fromUser, prompt);
                if (reply != null) return reply;
            }

            // -- 普通对话，直接丢给 AI --
            String aiReply = aiService.chat(fromUser, text);
            if (aiReply != null) return aiReply;

            return "（AI 暂时无响应，请稍后再试）";
        });

        // 图片消息不进入普通文本对话，单独交给视觉模型识别后回复。
        botService.setImageReply((fromUser, contextToken, imageBytes) -> {
            log.info("[自动回复] 收到图片 from={} contextToken={} size={} bytes",
                    fromUser, maskToken(contextToken), imageBytes == null ? 0 : imageBytes.length);
            if (!imageRecognitionService.isConfigured()) {
                return "图片识别未配置，请管理员设置 VISION_API_KEY 或 IMAGE_API_KEY";
            }
            return imageRecognitionService.recognize(imageBytes);
        });
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
        log.info("[BotController] 手动发送消息 replyId={} text={}", replyId, text);
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

    /**
     * 从消息中提取城市名
     * 如 "今天杭州天气怎么样" → "杭州"
     */
    private String extractCity(String text) {
        int idx = text.indexOf("天气");
        if (idx <= 0) return null;

        String before = text.substring(0, idx);

        // 逐步去掉前缀/动词/标点
        before = before.replaceAll("(?s).*?(查一下|帮我查|我想知道|我想了解|给我查|请问)", "");
        before = before.replaceAll("(?s).*?(今天|明天|后天|昨天|现在|这周|下周|本周)", "");
        before = before.replaceAll("(?s).*?(在|的|了|呢|吗|啊|呀|吧|一下|下)", "");
        before = before.replaceAll("[，。！？、,.\\s]", "");
        before = before.trim();

        // 太长则只取后2-3字
        if (before.length() > 6) {
            before = before.substring(before.length() - 3);
        }

        return before.isEmpty() ? null : before;
    }

    private static String maskToken(String token) {
        if (token == null || token.isBlank()) return "null";
        if (token.length() <= 8) return "***";
        return token.substring(0, 4) + "..." + token.substring(token.length() - 4);
    }
}
