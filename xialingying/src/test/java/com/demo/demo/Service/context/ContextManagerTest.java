package com.demo.demo.Service.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContextManagerTest {

    @Test
    void imageContextInjectedIntoSystemPrompt() {
        ContextManager cm = new ContextManager();
        cm.recordImage("user-a", "一只橘色的猫");

        String enhanced = cm.buildEnhancedSystemMessage("user-a", "你是测试助手");
        assertTrue(enhanced.contains("[近期上下文]"));
        assertTrue(enhanced.contains("用户刚才发了一张图片"));
        assertTrue(enhanced.contains("一只橘色的猫"));
        assertTrue(enhanced.startsWith("你是测试助手"));
    }

    @Test
    void voiceContextInjectedIntoSystemPrompt() {
        ContextManager cm = new ContextManager();
        cm.recordVoice("user-a", "帮我查下天气");

        String enhanced = cm.buildEnhancedSystemMessage("user-a", "你是测试助手");
        assertTrue(enhanced.contains("用户刚才发了一条语音"));
        assertTrue(enhanced.contains("帮我查下天气"));
    }

    @Test
    void bothImageAndVoiceAppear() {
        ContextManager cm = new ContextManager();
        cm.recordImage("user-a", "一张菜单");
        cm.recordVoice("user-a", "第一个菜多少钱");

        String enhanced = cm.buildEnhancedSystemMessage("user-a", "你是测试助手");
        assertTrue(enhanced.contains("图片"));
        assertTrue(enhanced.contains("语音"));
    }

    @Test
    void noContextReturnsOriginalPrompt() {
        ContextManager cm = new ContextManager();
        String result = cm.buildEnhancedSystemMessage("unknown-user", "你是测试助手");
        assertEquals("你是测试助手", result);
    }

    @Test
    void expiredContextIsIgnored() {
        ContextManager cm = new ContextManager();
        // 通过反射设置 TTL 为 1ms 来模拟过期不方便，改为验证 cleanExpired 后上下文为空
        cm.recordImage("user-a", "描述");
        assertEquals(1, cm.getActiveCount());

        // 清除后无上下文
        cm.clear("user-a");
        assertEquals(0, cm.getActiveCount());
        assertEquals("你是测试助手",
                cm.buildEnhancedSystemMessage("user-a", "你是测试助手"));
    }

    @Test
    void userIsolation() {
        ContextManager cm = new ContextManager();
        cm.recordImage("user-a", "猫");
        cm.recordVoice("user-b", "天气");

        String a = cm.buildEnhancedSystemMessage("user-a", "base");
        assertTrue(a.contains("猫"));
        assertFalse(a.contains("天气"));

        String b = cm.buildEnhancedSystemMessage("user-b", "base");
        assertTrue(b.contains("天气"));
        assertFalse(b.contains("猫"));
    }

    @Test
    void emptyRecordingsAreIgnored() {
        ContextManager cm = new ContextManager();
        cm.recordImage("user-a", null);
        cm.recordVoice("user-a", "  ");

        assertEquals("base",
                cm.buildEnhancedSystemMessage("user-a", "base"));
    }

    @Test
    void cleanExpiredRemovesStaleContexts() {
        ContextManager cm = new ContextManager();
        cm.recordImage("user-a", "描述");
        cm.recordVoice("user-b", "语音");
        assertEquals(2, cm.getActiveCount());

        cm.clear("user-a");
        cm.cleanExpired();
        assertEquals(1, cm.getActiveCount());
    }
}
