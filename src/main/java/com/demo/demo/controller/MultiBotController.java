package com.demo.demo.controller;

import com.demo.demo.Service.BotInstance;
import com.demo.demo.Service.MultiBotManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 多Bot管理控制器
 * 提供多Bot实例的管理界面和API
 */
@Slf4j
@Controller
@RequestMapping("/multi-bot")
public class MultiBotController {

    @Resource
    private MultiBotManager multiBotManager;

    /**
     * 多Bot管理页面
     */
    @GetMapping
    public String multiBotPage() {
        log.info("[MultiBot] 访问多Bot管理页面");
        return "multi-bot";
    }

    /**
     * 获取所有Bot实例列表
     */
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

    /**
     * 创建新Bot实例
     */
    @PostMapping("/instances")
    @ResponseBody
    public Map<String, Object> createInstance(@RequestParam(required = false) String displayName) {
        BotInstance newBot = multiBotManager.createBot(displayName);
        Map<String, Object> map = new HashMap<>();
        map.put("ok", true);
        map.put("instanceId", newBot.getInstanceId());
        map.put("displayName", newBot.getDisplayName());
        log.info("[MultiBot] 创建新Bot实例: {}", newBot.getInstanceId());
        return map;
    }

    /**
     * 删除Bot实例
     */
    @DeleteMapping("/instances/{instanceId}")
    @ResponseBody
    public Map<String, Object> removeInstance(@PathVariable String instanceId) {
        multiBotManager.removeBot(instanceId);
        Map<String, Object> map = new HashMap<>();
        map.put("ok", true);
        log.info("[MultiBot] 删除Bot实例: {}", instanceId);
        return map;
    }

    /**
     * 获取指定Bot的状态
     */
    @GetMapping("/status")
    @ResponseBody
    public Map<String, Object> getBotStatus(@RequestParam String botId) {
        BotInstance bot = multiBotManager.getBot(botId);
        if (bot == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Bot实例不存在");
            return error;
        }

        Map<String, Object> map = new HashMap<>();
        map.put("instanceId", bot.getInstanceId());
        map.put("displayName", bot.getDisplayName());
        map.put("loggedIn", bot.isLoggedIn());
        map.put("status", bot.getStatusText());
        map.put("qrCodeBase64", bot.getQrCodeBase64());
        map.put("qrCodeUrl", bot.getQrCodeUrl());
        return map;
    }

    /**
     * 重启指定Bot的登录
     */
    @PostMapping("/restart")
    @ResponseBody
    public Map<String, Object> restartBot(@RequestParam String botId) {
        BotInstance bot = multiBotManager.getBot(botId);
        if (bot != null) {
            bot.restartLogin();
            log.info("[MultiBot] 重启Bot登录: {}", botId);
        }
        Map<String, Object> map = new HashMap<>();
        map.put("ok", true);
        return map;
    }

    /**
     * 获取指定Bot的消息
     */
    @GetMapping("/messages")
    @ResponseBody
    public Map<String, Object> getBotMessages(@RequestParam String botId) {
        BotInstance bot = multiBotManager.getBot(botId);
        if (bot == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Bot实例不存在");
            return error;
        }

        Map<String, Object> map = new HashMap<>();
        List<Map<String, Object>> safeMessages = bot.pollMessages().stream().map(msg -> {
            Map<String, Object> safeMessage = new HashMap<>();
            safeMessage.put("fromUser", msg.fromUser);
            safeMessage.put("replyId", msg.replyId);
            safeMessage.put("content", msg.content);
            safeMessage.put("time", msg.time);
            return safeMessage;
        }).collect(Collectors.toList());

        map.put("messages", safeMessages);
        map.put("logs", bot.getLogs());
        map.put("instanceId", bot.getInstanceId());
        return map;
    }

    /**
     * 手动发送消息
     */
    @PostMapping("/send")
    @ResponseBody
    public Map<String, Object> sendMessage(
            @RequestParam String replyId,
            @RequestParam String text,
            @RequestParam String botId) {
        BotInstance bot = multiBotManager.getBot(botId);
        if (bot == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("ok", false);
            error.put("error", "Bot实例不存在");
            return error;
        }

        boolean sent = bot.sendManualReply(replyId, text);
        Map<String, Object> map = new HashMap<>();
        map.put("ok", sent);
        log.info("[MultiBot] 手动发送消息 botId={} replyId={} success={}", botId, replyId, sent);
        return map;
    }

    /**
     * 启动所有未登录的Bot
     */
    @PostMapping("/start-all")
    @ResponseBody
    public Map<String, Object> startAllBots() {
        multiBotManager.startAllBots();
        Map<String, Object> map = new HashMap<>();
        map.put("ok", true);
        map.put("botCount", multiBotManager.getBotCount());
        log.info("[MultiBot] 启动所有Bot实例");
        return map;
    }
}
