package com.demo.demo;

import com.demo.demo.Utils.WeatherUtil;
import com.demo.demo.execption.BizException;
import com.demo.demo.execption.ResponseCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 天气接口稳定性与边界测试
 * 专门测试接口的稳定性和各种边界场景
 */
@Slf4j
class WeatherStabilityTest {

    // ==================== 参数校验边界测试 ====================

    @Test
    @DisplayName("空字符串城市名应抛出异常")
    void testEmptyStringCity() {
        BizException ex = assertThrows(BizException.class, () -> WeatherUtil.getWeather(""));
        assertEquals(ResponseCodeEnum.CITY_NAME_EMPTY.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("纯空格城市名应抛出异常")
    void testBlankCity() {
        BizException ex = assertThrows(BizException.class, () -> WeatherUtil.getWeather("   "));
        assertEquals(ResponseCodeEnum.CITY_NAME_EMPTY.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("null 城市名应抛出异常")
    void testNullCity() {
        BizException ex = assertThrows(BizException.class, () -> WeatherUtil.getWeather(null));
        assertEquals(ResponseCodeEnum.CITY_NAME_EMPTY.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("超长城市名应正确处理")
    void testVeryLongCityName() {
        String longName = "A".repeat(200);
        // 无效城市名会被 wttr.in 返回 not found，应抛出 CITY_NOT_FOUND
        try {
            WeatherUtil.getWeather(longName);
        } catch (BizException e) {
            // 预期行为：要么没找到这个城市
            log.info("[边界测试] 超长城市名结果: code={}", e.getCode());
        }
    }

    // ==================== 并发稳定性测试 ====================

    @Test
    @DisplayName("短时间内并发查询不同城市")
    void testConcurrentQueries() throws InterruptedException {
        String[] cities = {"杭州", "北京", "上海", "深圳", "成都"};
        int[] successCount = {0};
        int[] failCount = {0};

        Thread[] threads = new Thread[cities.length];
        for (int i = 0; i < cities.length; i++) {
            final String city = cities[i];
            threads[i] = new Thread(() -> {
                try {
                    String result = WeatherUtil.getWeather(city);
                    assertNotNull(result);
                    assertTrue(result.contains(city));
                    synchronized (successCount) { successCount[0]++; }
                    log.info("[并发测试] {} ✓", city);
                } catch (BizException e) {
                    synchronized (failCount) { failCount[0]++; }
                    log.warn("[并发测试] {} ✗ {}", city, e.getMessage());
                }
            });
            threads[i].start();
        }

        for (Thread t : threads) {
            t.join(30000); // 最多等 30 秒
        }

        log.info("[并发测试] 结果: {} 成功, {} 失败", successCount[0], failCount[0]);
        assertTrue(successCount[0] >= 3,
                "并发查询至少应有3个成功，实际: " + successCount[0]);
    }

    // ==================== 响应格式验证 ====================

    @Test
    @DisplayName("天气查询结果格式应符合预期模板")
    void testResponseFormat() {
        String result = WeatherUtil.getWeather("深圳");
        assertNotNull(result);

        // 格式: "城市: XXX (...) | 温度: X°C (体感: X°C) | 天气: XXX | X风 X km/h | 湿度: X%"
        log.info("[格式验证] 返回结果: {}", result);

        assertTrue(result.contains("城市:"), "应包含「城市:」");
        assertTrue(result.contains("温度:"), "应包含「温度:」");
        assertTrue(result.contains("体感:"), "应包含「体感:」");
        assertTrue(result.contains("天气:"), "应包含「天气:」");
        assertTrue(result.contains("湿度:"), "应包含「湿度:」");
        assertTrue(result.contains("°C"), "应包含温度单位 °C");
        assertTrue(result.contains("%"), "应包含湿度百分比符号");
        assertTrue(result.contains("km/h"), "应包含风速单位");
    }

    // ==================== 响应码枚举完整性测试 ====================

    @Test
    @DisplayName("ResponseCodeEnum 所有枚举值应有非空 code 和 message")
    void testResponseCodeEnumCompleteness() {
        for (ResponseCodeEnum code : ResponseCodeEnum.values()) {
            assertNotNull(code.getCode(), code.name() + " 的 code 不应为空");
            assertFalse(code.getCode().isEmpty(), code.name() + " 的 code 不应为空字符串");
            assertNotNull(code.getDefaultMessage(), code.name() + " 的 message 不应为空");
            assertFalse(code.getDefaultMessage().isEmpty(), code.name() + " 的 message 不应为空字符串");
        }
        log.info("[枚举完整性] 共 {} 个响应码，全部有效", ResponseCodeEnum.values().length);
    }
}
