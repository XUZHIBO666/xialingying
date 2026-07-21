package com.demo.demo.Service;

import com.lth.wechat.ilink.ILinkClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class InboundMessageDeduplicationTest {

    @Test
    void duplicateItemMsgIdIsProcessedOnlyOnce() {
        BotService botService = new BotService(mock(ILinkClient.class));

        assertTrue(botService.markInboundMessageIfNew("outer-1", "item-1"));
        assertFalse(botService.markInboundMessageIfNew("outer-1", "item-1"));
    }

    @Test
    void itemMsgIdHasPriorityOverOuterMessageId() {
        BotService botService = new BotService(mock(ILinkClient.class));

        assertTrue(botService.markInboundMessageIfNew("outer-1", "item-1"));
        assertTrue(botService.markInboundMessageIfNew("outer-1", "item-2"));
        assertFalse(botService.markInboundMessageIfNew("outer-2", "item-1"));
    }

    @Test
    void outerMessageIdIsUsedWhenItemMsgIdIsMissing() {
        BotService botService = new BotService(mock(ILinkClient.class));

        assertTrue(botService.markInboundMessageIfNew("outer-1", ""));
        assertFalse(botService.markInboundMessageIfNew("outer-1", null));
    }

    @Test
    void missingBothIdsDoesNotDropMessage() {
        BotService botService = new BotService(mock(ILinkClient.class));

        assertTrue(botService.markInboundMessageIfNew(null, null));
        assertTrue(botService.markInboundMessageIfNew("", ""));
    }
}
