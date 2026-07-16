package com.demo.demo.controller;

import com.demo.demo.Service.AIService;
import com.demo.demo.Service.BotService;
import com.demo.demo.Utils.WeatherUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Controller
@RequestMapping("/bot")
public class BotController {

    @Resource
    private BotService botService;

    @Resource
    private AIService aiService;

    // ==================== 初始化：应用一启动就设置好自动回复 ====================

    @PostConstruct
    public void initAutoReply() {
        botService.setAutoReply((fromUser, text) -> {
            if (!aiService.isConfigured()) {
                return "AI 未配置，请联系管理员";
            }

            // -- 工具1：查天气 --
            if (text.contains("天气")) {
                String city = extractCity(text);
                if (city != null) {
                    try {
                        String weather = WeatherUtil.getWeather(city);
                        String prompt = "用户问: \"" + text + "\"\n"
                                + "以下是实时天气数据: " + weather + "\n"
                                + "请用自然的中文把这天气数据告诉用户，两句话以内。";
                        String reply = aiService.chat(fromUser, prompt);
                        if (reply != null) return reply;
                    } catch (Exception e) {
                        System.err.println("[天气] 查询失败: " + e.getMessage());
                        return "抱歉，查询「" + city + "」的天气失败了：" + e.getMessage();
                    }
                }
                // 没提取到城市，但提到了天气 → 交给 AI 处理
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
    }

    // ==================== 页面 ====================

    /** 主页面：访问时自动触发登录 */
    @GetMapping
    public String botPage() {
        if (!botService.isLoggedIn()) {
            botService.startLogin();
        }
        return "bot";
    }

    // ==================== REST API ====================

    /** 获取二维码和状态 */
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

    /** 拉取新消息 */
    @GetMapping("/messages")
    @ResponseBody
    public Map<String, Object> messages() {
        Map<String, Object> map = new HashMap<>();
        map.put("messages", botService.pollMessages());
        map.put("logs", botService.getLogs());
        return map;
    }

    /** 发送回复 */
    @PostMapping("/send")
    @ResponseBody
    public Map<String, Object> send(@RequestParam String toUserId,
                                     @RequestParam String clientId,
                                     @RequestParam String text) {
        botService.sendReply(toUserId, clientId, text);
        Map<String, Object> map = new HashMap<>();
        map.put("ok", true);
        return map;
    }

    /** 重新获取二维码 */
    @PostMapping("/restart")
    @ResponseBody
    public Map<String, Object> restart() {
        botService.restartLogin();
        Map<String, Object> map = new HashMap<>();
        map.put("ok", true);
        return map;
    }

    /** 从消息中提取城市名（如 "今天杭州天气怎么样" -> "杭州"） */
    private String extractCity(String text) {
        int idx = text.indexOf("天气");
        if (idx <= 0) return null;

        // 取"天气"前面的部分，逐步去掉前缀/动词/标点
        String before = text.substring(0, idx);

        // 去掉常见前缀：查一下、帮我查、我想知道 等
        before = before.replaceAll("(?s).*?(查一下|帮我查|我想知道|我想了解|给我查|请问)", "");
        // 去掉时间词
        before = before.replaceAll("(?s).*?(今天|明天|后天|昨天|现在|这周|下周|本周)", "");
        // 去掉介词/动词前缀
        before = before.replaceAll("(?s).*?(在|的|了|呢|吗|啊|呀|吧|一下|下)", "");
        // 去掉标点
        before = before.replaceAll("[，。！？、,.\s]", "");
        before = before.trim();

        // 如果剩下的文本太长（>6字），可能没清理干净，只取后2-3字
        if (before.length() > 6) {
            before = before.substring(before.length() - 3);
        }

        return before.isEmpty() ? null : before;
    }
}
