package com.demo.demo.Service;

import com.lth.wechat.ilink.ILinkClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class UserMessageSerializationTest {

    @Test
    void sameUserMessagesRunInArrivalOrder() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        BotService botService = new BotService(mock(ILinkClient.class), executor);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch allDone = new CountDownLatch(2);
        AtomicBoolean secondStarted = new AtomicBoolean(false);
        List<String> handled = new CopyOnWriteArrayList<>();

        botService.setAutoReply((fromUser, contextToken, text) -> {
            if ("A1".equals(text)) {
                firstStarted.countDown();
                await(releaseFirst);
            }
            if ("A2".equals(text)) {
                secondStarted.set(true);
            }
            handled.add(text);
            allDone.countDown();
            return null;
        });

        botService.processTextMessage("user-a", "ctx-a", "A1");
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
        botService.processTextMessage("user-a", "ctx-a", "A2");

        Thread.sleep(200);
        assertFalse(secondStarted.get());

        releaseFirst.countDown();
        assertTrue(allDone.await(1, TimeUnit.SECONDS));
        assertEquals(List.of("A1", "A2"), handled);
        executor.shutdownNow();
    }

    @Test
    void differentUsersCanRunInParallel() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        BotService botService = new BotService(mock(ILinkClient.class), executor);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondUserStarted = new CountDownLatch(1);

        botService.setAutoReply((fromUser, contextToken, text) -> {
            if ("A1".equals(text)) {
                firstStarted.countDown();
                await(releaseFirst);
            }
            if ("B1".equals(text)) {
                secondUserStarted.countDown();
            }
            return null;
        });

        botService.processTextMessage("user-a", "ctx-a", "A1");
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
        botService.processTextMessage("user-b", "ctx-b", "B1");

        assertTrue(secondUserStarted.await(1, TimeUnit.SECONDS));
        releaseFirst.countDown();
        executor.shutdownNow();
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
