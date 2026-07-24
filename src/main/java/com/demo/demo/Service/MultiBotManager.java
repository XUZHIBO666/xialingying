package com.demo.demo.Service;

import com.demo.demo.Service.voice.VoiceMessageHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 多Bot实例管理器 —— 支持多个微信号同时登录、独立运行
 *
 * <p>职责：
 * <ul>
 *   <li>创建和管理多个 BotInstance</li>
 *   <li>为所有Bot实例配置相同的业务逻辑处理器</li>
 *   <li>提供统一的查询和操作接口</li>
 * </ul>
 */
@Slf4j
@Service
public class MultiBotManager {

    private final Map<String, BotInstance> botInstances = new ConcurrentHashMap<>();
    private final List<String> botOrder = new CopyOnWriteArrayList<>();

    @Getter
    private volatile BotService.ReplyHandler sharedAutoReplyHandler;

    @Getter
    private volatile BotService.ImageReplyHandler sharedImageReplyHandler;

    @Getter
    private volatile VoiceMessageHandler sharedVoiceHandler;

    /**
     * 创建新的Bot实例
     * @param displayName 显示名称（可选）
     * @return 新创建的Bot实例
     */
    public BotInstance createBot(String displayName) {
        BotInstance bot = new BotInstance(displayName);
        botInstances.put(bot.getInstanceId(), bot);
        botOrder.add(bot.getInstanceId());

        // 自动配置相同的业务逻辑处理器
        applySharedHandlers(bot);

        log.info("[MultiBot] 创建新Bot实例: {} (总数: {})",
                bot.getInstanceId(), botInstances.size());
        return bot;
    }

    /**
     * 移除Bot实例
     */
    public void removeBot(String instanceId) {
        BotInstance bot = botInstances.remove(instanceId);
        if (bot != null) {
            botOrder.remove(instanceId);
            bot.shutdown();
            log.info("[MultiBot] 移除Bot实例: {} (剩余: {})", instanceId, botInstances.size());
        }
    }

    /**
     * 获取指定Bot实例
     */
    public BotInstance getBot(String instanceId) {
        return botInstances.get(instanceId);
    }

    /**
     * 获取所有Bot实例
     */
    public Collection<BotInstance> getAllBots() {
        return Collections.unmodifiableCollection(botInstances.values());
    }

    /**
     * 获取第一个可用的Bot实例（兼容旧代码）
     */
    public BotInstance getDefaultBot() {
        if (botOrder.isEmpty()) {
            return createBot("默认Bot");
        }
        return botInstances.get(botOrder.get(0));
    }

    /**
     * 设置共享的自动回复处理器（应用到所有Bot实例）
     */
    public void setSharedAutoReply(BotService.ReplyHandler handler) {
        this.sharedAutoReplyHandler = handler;
        botInstances.values().forEach(bot -> bot.setAutoReply(handler));
        log.info("[MultiBot] 设置共享自动回复处理器 (Bot数量: {})", botInstances.size());
    }

    /**
     * 设置共享的图片识别处理器
     */
    public void setSharedImageReply(BotService.ImageReplyHandler handler) {
        this.sharedImageReplyHandler = handler;
        botInstances.values().forEach(bot -> bot.setImageReply(handler));
        log.info("[MultiBot] 设置共享图片识别处理器");
    }

    /**
     * 设置共享的语音处理器
     */
    public void setSharedVoiceHandler(VoiceMessageHandler handler) {
        this.sharedVoiceHandler = handler;
        botInstances.values().forEach(bot -> bot.setVoiceMessageHandler(handler));
        log.info("[MultiBot] 设置共享语音处理器");
    }

    /**
     * 为指定Bot设置独立的处理器（覆盖共享配置）
     */
    public void setBotSpecificAutoReply(String instanceId, BotService.ReplyHandler handler) {
        BotInstance bot = botInstances.get(instanceId);
        if (bot != null) {
            bot.setAutoReply(handler);
            log.info("[MultiBot] 为 {} 设置独立自动回复处理器", instanceId);
        }
    }

    /**
     * 统计信息
     */
    public int getBotCount() {
        return botInstances.size();
    }

    public int getTotalLoggedInBots() {
        return (int) botInstances.values().stream().filter(BotInstance::isLoggedIn).count();
    }

    /**
     * 批量启动所有未登录的Bot
     */
    public void startAllBots() {
        botInstances.values().forEach(bot -> {
            if (!bot.isLoggedIn()) {
                bot.startLogin();
            }
        });
        log.info("[MultiBot] 已请求启动所有Bot实例");
    }

    /**
     * 批量关闭所有Bot
     */
    @PreDestroy
    public void shutdownAll() {
        log.info("[MultiBot] 开始关闭所有Bot实例...");
        botInstances.values().forEach(BotInstance::shutdown);
        botInstances.clear();
        botOrder.clear();
        log.info("[MultiBot] 所有Bot实例已关闭");
    }

    @PostConstruct
    public void init() {
        // 应用启动时创建一个默认Bot实例
        createBot("主账号");
        log.info("[MultiBot] 管理器初始化完成，默认Bot已创建");
    }

    // ==================== 私有方法 ====================

    private void applySharedHandlers(BotInstance bot) {
        if (sharedAutoReplyHandler != null) {
            bot.setAutoReply(sharedAutoReplyHandler);
        }
        if (sharedImageReplyHandler != null) {
            bot.setImageReply(sharedImageReplyHandler);
        }
        if (sharedVoiceHandler != null) {
            bot.setVoiceMessageHandler(sharedVoiceHandler);
        }
    }
}
