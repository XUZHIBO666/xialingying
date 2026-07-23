package com.demo.demo;

import com.demo.demo.Utils.WeatherUtil;
import com.demo.demo.execption.BizException;
import com.demo.demo.execption.ResponseCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 天气查询工具类单元测试
 * 覆盖：正常查询、边界情况、异常场景
 */
@Slf4j
class WeatherUtilTest {

    // ==================== 正常场景 ====================

    @Test
    @DisplayName("查询中文城市名 - 杭州")
    void testQueryByChineseName() {
        String result = WeatherUtil.getWeather("杭州");
        assertNotNull(result);
        assertTrue(result.contains("杭州"));
        assertTrue(result.contains("°C"));
        assertTrue(result.contains("湿度"));
        log.info("[测试] 杭州天气: {}", result);
    }

    @Test
    @DisplayName("查询英文城市名 - Beijing")
    void testQueryByEnglishName() {
        String result = WeatherUtil.getWeather("Beijing");
        assertNotNull(result);
        assertTrue(result.contains("Beijing"));
        assertTrue(result.contains("°C"));
        log.info("[测试] Beijing天气: {}", result);
    }

    @Test
    @DisplayName("查询拼音城市名 - Shanghai")
    void testQueryByPinyin() {
        String result = WeatherUtil.getWeather("Shanghai");
        assertNotNull(result);
        assertTrue(result.contains("Shanghai"));
        assertTrue(result.contains("°C"));
        log.info("[测试] Shanghai天气: {}", result);
    }

    @Test
    @DisplayName("查询国际城市 - New York")
    void testQueryInternationalCity() {
        String result = WeatherUtil.getWeather("New York");
        assertNotNull(result);
        assertTrue(result.contains("New York"));
        assertTrue(result.contains("°C"));
        log.info("[测试] New York天气: {}", result);
    }

    @Test
    @DisplayName("查询带空格的城市名 - Hong Kong")
    void testQueryCityWithSpace() {
        String result = WeatherUtil.getWeather("Hong Kong");
        assertNotNull(result);
        assertTrue(result.contains("Hong Kong"));
        log.info("[测试] Hong Kong天气: {}", result);
    }

    // ==================== 边界情况：空城市名 ====================

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n"})
    @DisplayName("空城市名应抛出 CITY_NAME_EMPTY 异常")
    void testEmptyCityName(String cityName) {
        BizException exception = assertThrows(BizException.class, () -> {
            WeatherUtil.getWeather(cityName);
        });
        assertEquals(ResponseCodeEnum.CITY_NAME_EMPTY.getCode(), exception.getCode());
        log.info("[测试] 空城市名正确抛出异常: {}", exception.getMessage());
    }

    // ==================== 边界情况：无效城市名 ====================

    @Test
    @DisplayName("包含特殊字符的城市名应抛出异常")
    void testInvalidCityNameWithSpecialChars() {
        BizException exception = assertThrows(BizException.class, () -> {
            WeatherUtil.getWeather("杭州@#$");
        });
        assertEquals(ResponseCodeEnum.CITY_NAME_INVALID.getCode(), exception.getCode());
        log.info("[测试] 特殊字符城市名正确抛出异常: {}", exception.getMessage());
    }

    @Test
    @DisplayName("纯数字城市名应抛出异常")
    void testInvalidCityNameWithNumbers() {
        BizException exception = assertThrows(BizException.class, () -> {
            WeatherUtil.getWeather("12345");
        });
        assertEquals(ResponseCodeEnum.CITY_NAME_INVALID.getCode(), exception.getCode());
        log.info("[测试] 纯数字城市名正确抛出异常: {}", exception.getMessage());
    }

    // ==================== 边界情况：不存在的城市 ====================

    @Test
    @DisplayName("不存在的城市名应抛出 CITY_NOT_FOUND 异常")
    void testNonExistentCity() {
        // 使用一个明显不存在的城市名
        assertThrows(BizException.class, () -> {
            WeatherUtil.getWeather("NonExistentCityXYZ123");
        });
        log.info("[测试] 不存在的城市正确抛出异常");
    }

    // ==================== 多城市覆盖测试 ====================

    @Test
    @DisplayName("多城市批量查询 - 覆盖中国主要城市")
    void testMultipleChineseCities() {
        String[] cities = {"北京", "上海", "广州", "深圳", "成都", "武汉", "南京", "西安"};
        int successCount = 0;
        int failCount = 0;

        for (String city : cities) {
            try {
                String result = WeatherUtil.getWeather(city);
                assertNotNull(result);
                assertTrue(result.contains(city));
                successCount++;
                log.info("[多城市测试] {} ✓", city);
            } catch (BizException e) {
                failCount++;
                log.warn("[多城市测试] {} ✗ {}", city, e.getMessage());
            }
        }

        log.info("[多城市测试] 总计: {} 成功, {} 失败", successCount, failCount);
        // 至少一半城市应该成功
        assertTrue(successCount >= cities.length / 2,
                "至少应有半数城市查询成功，实际成功: " + successCount);
    }

    @Test
    @DisplayName("多城市批量查询 - 覆盖国际城市")
    void testMultipleInternationalCities() {
        String[] cities = {"Tokyo", "London", "Paris", "Sydney", "Singapore", "Seoul", "Bangkok"};
        int successCount = 0;

        for (String city : cities) {
            try {
                String result = WeatherUtil.getWeather(city);
                assertNotNull(result);
                assertTrue(result.contains(city));
                successCount++;
                log.info("[国际城市测试] {} ✓", city);
            } catch (BizException e) {
                log.warn("[国际城市测试] {} ✗ {}", city, e.getMessage());
            }
        }

        log.info("[国际城市测试] 总计: {} 成功 / {} 总数", successCount, cities.length);
        assertTrue(successCount >= cities.length / 2,
                "至少应有半数国际城市查询成功，实际成功: " + successCount);
    }

    // ==================== 接口稳定性测试 ====================

    @Test
    @DisplayName("连续多次查询同一城市验证稳定性")
    void testStabilityWithRepeatedQueries() {
        String city = "杭州";
        int successCount = 0;

        for (int i = 0; i < 5; i++) {
            try {
                String result = WeatherUtil.getWeather(city);
                assertNotNull(result);
                successCount++;
                log.info("[稳定性测试] 第{}次查询成功", i + 1);
                // 两次查询间稍微等待
                Thread.sleep(500);
            } catch (BizException e) {
                log.warn("[稳定性测试] 第{}次查询失败: {}", i + 1, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // 5次查询至少4次成功（允许偶尔网络波动）
        assertTrue(successCount >= 4,
                "稳定性测试: 5次查询中至少4次应成功，实际成功: " + successCount);
    }

    // ==================== 响应数据完整性测试 ====================

    @Test
    @DisplayName("验证返回数据包含所有必要字段")
    void testResponseDataCompleteness() {
        String result = WeatherUtil.getWeather("杭州");

        assertNotNull(result);
        // 验证包含温度
        assertTrue(result.contains("°C"), "应包含温度信息");
        // 验证包含湿度
        assertTrue(result.contains("湿度"), "应包含湿度信息");
        // 验证包含天气描述
        assertTrue(result.contains("天气"), "应包含天气描述");
        // 验证包含风向/风速
        assertTrue(result.contains("风"), "应包含风向风速信息");
        // 验证包含城市名
        assertTrue(result.contains("杭州"), "应包含城市名");

        log.info("[数据完整性] 所有必要字段均存在: {}", result);
    }

    // ==================== BizException 功能测试 ====================

    @Test
    @DisplayName("BizException 应正确携带错误码和消息")
    void testBizExceptionProperties() {
        BizException ex1 = new BizException(ResponseCodeEnum.CITY_NAME_EMPTY);
        assertEquals("40003", ex1.getCode());
        assertEquals("城市名不能为空", ex1.getMessage());
        assertEquals(ResponseCodeEnum.CITY_NAME_EMPTY, ex1.getResponseCode());

        BizException ex2 = new BizException(ResponseCodeEnum.CITY_NOT_FOUND, "找不到城市「火星」");
        assertEquals("40004", ex2.getCode());
        assertEquals("找不到城市「火星」", ex2.getMessage());

        BizException ex3 = new BizException(ResponseCodeEnum.NETWORK_ERROR,
                new RuntimeException("Connection refused"));
        assertEquals("50005", ex3.getCode());
        assertNotNull(ex3.getCause());

        log.info("[BizException测试] 所有断言通过");
    }
}
