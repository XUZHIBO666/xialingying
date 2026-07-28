package com.demo.demo.Service.scheduling.adapter;

import com.demo.demo.Service.BotInstance;
import com.demo.demo.Service.MultiBotManager;
import com.demo.demo.Service.scheduling.application.DeliveryTargetResolved;
import com.demo.demo.Service.scheduling.application.DeliveryTargetService;
import com.demo.demo.Service.scheduling.application.SchedulingException;
import com.demo.demo.Service.scheduling.execution.MessagePushGateway;
import com.demo.demo.Service.scheduling.execution.PushRequest;
import com.demo.demo.Service.scheduling.execution.PushResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Bridges {@link MessagePushGateway} to the default iLink Bot.
 *
 * <p>Resolves the delivery target (decrypting contextToken only at the
 * send boundary), then delegates to {@link BotInstance#sendTextWithResult}.
 * Plaintext token is never logged.
 */
@Slf4j
@Component
public class ILinkMessagePushGateway implements MessagePushGateway {

    private final MultiBotManager botManager;
    private final DeliveryTargetService targetService;

    public ILinkMessagePushGateway(MultiBotManager botManager, DeliveryTargetService targetService) {
        this.botManager = botManager;
        this.targetService = targetService;
    }

    @Override
    public PushResult pushText(PushRequest request) {
        // 1. Resolve target (decrypt token at the boundary)
        DeliveryTargetResolved resolved;
        try {
            resolved = targetService.resolve(request.targetId());
        } catch (SchedulingException e) {
            log.warn("[PushGateway] Target resolution failed targetId={}: {}",
                    request.targetId(), e.getMessage());
            return PushResult.failed("TARGET_NOT_FOUND");
        }

        // 2. Get default Bot (MVP scope)
        BotInstance bot = botManager.getDefaultBot();

        // 3. Send via observable API
        BotInstance.PushResult botResult = bot.sendTextWithResult(
                resolved.userId(), resolved.contextToken(), request.text());

        log.debug("[PushGateway] Send result targetId={} success={} error={}",
                request.targetId(), botResult.success(), botResult.errorCode());

        return botResult.success()
                ? PushResult.ok()
                : PushResult.failed(botResult.errorCode());
    }
}
