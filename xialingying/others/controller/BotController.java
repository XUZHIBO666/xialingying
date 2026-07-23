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
import java.util.HashMap;
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
    private com.demo.demo.Service.VoiceService voiceService;

    @Resource
    private com.demo.demo.Service.WechatMpService wechatMpService;

    // ==================== 初始化：设置自动回复 ====================

    @PostConstruct
    public void initAutoReply() {
        log.info("[BotController] 初始化自动回复处理器...");

        // ==================== 文本消息处理 ====================
        botService.setAutoReply((fromUser, contextToken, text) -> {
            log.info("[自动回复] 收到消息 from={} contextToken={} text={}", fromUser, contextToken, text);

            // -- 工具1：图片生成 --
            // AI 判断用户是否想生图，同时生成提示词
            String intentPrompt1 = "用户说：「" + text + "」\n"
                    + "请判断用户是否想让你生成一张图片。\n"
                    + "如果想：回复格式为「YES|提示词」，例如「YES|一只可爱的小狗在草地上奔跑」。\n"
                    + "如果不想：只回复一个词「NO」。\n"
                    + "注意：必须以 YES| 或 NO 开头，不要加任何其他内容。";
            String aiResponse1 = aiService.chat(fromUser + "_img_intent", intentPrompt1);

            if (aiResponse1 != null && aiResponse1.startsWith("YES|") && aiResponse1.length() > 4) {
                String prompt = aiResponse1.substring(4).trim();
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

            // -- 工具2：语音生成 --
            // AI 判断用户是否想要生成语音
            String intentPromptVoice = "用户说：「" + text + "」\n"
                    + "你是一个可以生成语音的助手（TTS文字转语音）。\n"
                    + "请判断：用户是否希望你把回复内容用语音/朗读/讲出来的方式交付？\n"
                    + "只要用户提到了「语音」「声音」「音频」「朗读」「读出来」「读一下」"
                    + "「讲给我听」「用语音说」「用声音告诉我」「念」「说给我听」「讲一段」"
                    + "「讲个故事」「播报」「念出来」「说出来」等任何与'听'相关的表达，\n"
                    + "就说明用户想要语音输出 → 回复 YES|内容。\n"
                    + "格式：想听语音 → 「YES|要朗读的内容」\n"
                    + "      不想听 → 「NO」\n"
                    + "注意：只输出 YES|... 或 NO，不要解释。";
            String aiResponseVoice = aiService.chat(fromUser + "_voice_intent", intentPromptVoice);

            if (aiResponseVoice != null && aiResponseVoice.startsWith("YES|") && aiResponseVoice.length() > 4) {
                String voiceContent = aiResponseVoice.substring(4).trim();
                if (!voiceService.isConfigured()) {
                    return "语音服务未配置，请管理员设置 VOICE_API_KEY";
                }
                try {
                    byte[] mp3Bytes = voiceService.textToSpeech(voiceContent, null);
                    if (mp3Bytes != null) {
                        String url = voiceService.uploadToQiniu(mp3Bytes);
                        if (url != null) {
                            return voiceContent + "\n\n👇 语音下载（点击播放）：\n" + url;
                        }
                        return voiceContent + "\n（语音生成成功但上传失败）";
                    }
                    return "🎵（语音生成成功但发送失败）\n\n" + voiceContent;
                } catch (Exception e) {
                    log.error("[自动回复] 语音生成失败", e);
                }
            }

            if (!aiService.isConfigured()) {
                log.warn("[自动回复] AI 未配置，返回提示");
                return "AI 未配置，请联系管理员";
            }

            // -- 工具3：查天气 --
            String intentPrompt2 = "用户说：「" + text + "」\n"
                    + "请判断用户是否想询问你天气。\n"
                    + "如果想：回复格式为「YES|城市名」，例如「YES|杭州」。\n"
                    + "如果不想：只回复一个词「NO」。\n"
                    + "注意：必须以 YES| 或 NO 开头，不要加任何其他内容。";
            String aiResponse2 = aiService.chat(fromUser + "_weather_intent", intentPrompt2);
            if (aiResponse2 != null && aiResponse2.startsWith("YES|") && aiResponse2.length() > 4) {
                String city = aiResponse2.substring(4).trim();
                if (city != null) {
                    try {
                        log.info("[自动回复] 查询天气，城市: {}", city);
                        String weather = WeatherUtil.getWeather(city);
                        String prompt1 = "用户问: \"" + text + "\"\n"
                                + "以下是实时天气数据: " + weather + "\n"
                                + "请用自然的中文把这天气数据告诉用户，两句话以内。";
                        String reply = aiService.chat(fromUser, prompt1);
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

            // -- 工具4：查时间 --
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

        // ==================== 图片消息处理 ====================
        botService.setImageReply((fromUser, contextToken, imageBytes) -> {
            log.info("[自动回复] 收到图片 from={} contextToken={} size={} bytes",
                    fromUser, contextToken, imageBytes == null ? 0 : imageBytes.length);
            if (!imageRecognitionService.isConfigured()) {
                return "图片识别未配置，请管理员设置 VISION_API_KEY 或 IMAGE_API_KEY";
            }
            return imageRecognitionService.recognize(imageBytes);
        });

        // ==================== 语音消息处理 ====================
        botService.setVoiceReply((fromUser, contextToken, voiceBytes, format) -> {
            log.info("[自动回复] 收到语音 from={} contextToken={} format={}", fromUser, contextToken, format);
            if (!voiceService.isConfigured()) {
                return "语音服务未配置，请管理员设置 VOICE_API_KEY";
            }

            // 1. STT：语音转文字
            String text = voiceService.speechToText(voiceBytes, format);
            if (text == null || text.isBlank()) {
                return "抱歉，我没有听清楚你说什么";
            }

            log.info("[Voice] 语音识别结果: {}", text);

            // 2. 调用 AI 处理（复用文本消息的自动回复逻辑）
            BotService.ReplyHandler handler = botService.getAutoReplyHandler();
            if (handler != null) {
                String reply = handler.onMessage(fromUser, contextToken, text);

                // 3. TTS：把回复转成语音
                if (reply != null) {
                    byte[] replyVoice = voiceService.textToSpeech(reply, null);
                    if (replyVoice != null) {
                        botService.sendVoiceReply(fromUser, contextToken, replyVoice);
                        return null;
                    }
                }
                return reply;
            }
            return "处理失败";
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
        map.put("messages", botService.pollMessages());
        map.put("logs", botService.getLogs());
        return map;
    }

    /** 本地音频下载接口 —— 语音文件通过此 URL 提供给微信用户下载 */
    @GetMapping(value = "/audio/{token}.mp3", produces = "audio/mpeg")
    @ResponseBody
    public byte[] serveAudio(@PathVariable String token, jakarta.servlet.http.HttpServletResponse response) {
        byte[] audio = voiceService.getAudio(token);
        if (audio == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "音频不存在或已过期");
        }
        response.setContentType("audio/mpeg");
        response.setHeader("Content-Disposition", "attachment; filename=\"voice_reply.mp3\"");
        response.setContentLength(audio.length);
        return audio;
    }

    @PostMapping("/send")
    @ResponseBody
    public Map<String, Object> send(@RequestParam String toUserId,
                                     @RequestParam String contextToken,
                                     @RequestParam String text) {
        log.info("[BotController] 手动发送消息 to={} text={}", toUserId, text);
        botService.sendReply(toUserId, contextToken, text);
        Map<String, Object> map = new HashMap<>();
        map.put("ok", true);
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

    /** 获取本机局域网 IP，用于构造音频下载链接 */
    private String getLocalIp() {
        try {
            var interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                var iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                var addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    var addr = addresses.nextElement();
                    if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[BotController] 获取本机IP失败: {}", e.getMessage());
        }
        return "127.0.0.1";
    }

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
}
