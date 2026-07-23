package com.demo.demo;

import com.demo.demo.execption.GlobalExpectionHandler;
import com.demo.demo.execption.BizException;
import com.demo.demo.execption.ResponseCodeEnum;
import com.demo.demo.Utils.Response;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 全局异常处理器单元测试
 * 验证各种异常类型都能被正确捕获并返回友好的错误提示
 */
@Slf4j
class GlobalExceptionHandlerTest {

    private GlobalExpectionHandler handler;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExpectionHandler();
        request = new MockHttpServletRequest();
        request.setRequestURI("/api/weather");
    }

    // ==================== 业务异常处理 ====================

    @Test
    @DisplayName("BizException 应返回对应的错误码和消息")
    void testHandleBizException() {
        BizException ex = new BizException(ResponseCodeEnum.CITY_NOT_FOUND);
        Response<?> response = handler.handleBizException(ex, request);

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertEquals(ResponseCodeEnum.CITY_NOT_FOUND.getCode(), response.getCode());
        assertEquals(ResponseCodeEnum.CITY_NOT_FOUND.getDefaultMessage(), response.getMessage());
        log.info("[异常处理测试] BizException 响应: code={}, message={}", response.getCode(), response.getMessage());
    }

    @Test
    @DisplayName("BizException 自定义消息应正确返回")
    void testHandleBizExceptionWithCustomMessage() {
        String customMsg = "找不到城市「火星」，请检查输入";
        BizException ex = new BizException(ResponseCodeEnum.CITY_NOT_FOUND, customMsg);
        Response<?> response = handler.handleBizException(ex, request);

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertEquals(ResponseCodeEnum.CITY_NOT_FOUND.getCode(), response.getCode());
        assertEquals(customMsg, response.getMessage());
        log.info("[异常处理测试] 自定义消息: {}", response.getMessage());
    }

    @Test
    @DisplayName("BizException 参数为空异常")
    void testHandleParamEmptyException() {
        BizException ex = new BizException(ResponseCodeEnum.CITY_NAME_EMPTY);
        Response<?> response = handler.handleBizException(ex, request);

        assertFalse(response.isSuccess());
        assertEquals("40003", response.getCode());
        assertTrue(response.getMessage().contains("不能为空"));
    }

    // ==================== 参数校验异常 ====================

    @Test
    @DisplayName("缺少必填参数异常应返回友好提示")
    void testHandleMissingParam() {
        // 创建 MissingServletRequestParameterException
        Exception ex = new org.springframework.web.bind.MissingServletRequestParameterException(
                "city", "String");
        Response<?> response = handler.handleMissingParam(
                (org.springframework.web.bind.MissingServletRequestParameterException) ex,
                request);

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertTrue(response.getMessage().contains("city"),
                "错误消息应包含缺失的参数名");
        log.info("[异常处理测试] 缺少参数: {}", response.getMessage());
    }

    @Test
    @DisplayName("非法参数异常应返回友好提示")
    void testHandleIllegalArgument() {
        IllegalArgumentException ex = new IllegalArgumentException("城市名格式不正确");
        Response<?> response = handler.handleIllegalArgument(ex, request);

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertTrue(response.getMessage().contains("城市名格式不正确"));
        log.info("[异常处理测试] 非法参数: {}", response.getMessage());
    }

    // ==================== 网络异常处理 ====================

    @Test
    @DisplayName("SocketTimeoutException 应返回超时提示")
    void testHandleTimeout() {
        SocketTimeoutException ex = new SocketTimeoutException("connect timed out");
        Response<?> response = handler.handleTimeout(ex, request);

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertEquals(ResponseCodeEnum.THIRD_PARTY_TIMEOUT.getCode(), response.getCode());
        assertTrue(response.getMessage().contains("超时"));
        log.info("[异常处理测试] 超时: {}", response.getMessage());
    }

    @Test
    @DisplayName("ConnectException 应返回连接失败提示")
    void testHandleConnectFailed() {
        ConnectException ex = new ConnectException("Connection refused");
        Response<?> response = handler.handleConnectFailed(ex, request);

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertEquals(ResponseCodeEnum.NETWORK_ERROR.getCode(), response.getCode());
        assertTrue(response.getMessage().contains("网络连接失败"));
        log.info("[异常处理测试] 连接失败: {}", response.getMessage());
    }

    @Test
    @DisplayName("UnknownHostException 应返回 DNS 解析失败提示")
    void testHandleUnknownHost() {
        UnknownHostException ex = new UnknownHostException("api.weather.com");
        Response<?> response = handler.handleUnknownHost(ex, request);

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertEquals(ResponseCodeEnum.NETWORK_ERROR.getCode(), response.getCode());
        assertTrue(response.getMessage().contains("无法解析"));
        log.info("[异常处理测试] DNS失败: {}", response.getMessage());
    }

    // ==================== 兜底异常处理 ====================

    @Test
    @DisplayName("未预期的 Exception 应返回通用错误提示，不暴露堆栈")
    void testHandleGenericException() {
        Exception ex = new RuntimeException("数据库连接池耗尽");
        Response<?> response = handler.handleException(ex, request);

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertEquals(ResponseCodeEnum.INTERNAL_ERROR.getCode(), response.getCode());
        // 兜底消息不应暴露内部细节
        assertEquals("服务器繁忙，请稍后重试", response.getMessage());
        log.info("[异常处理测试] 兜底异常: {}", response.getMessage());
    }

    @Test
    @DisplayName("NullPointerException 应被兜底处理，不暴露堆栈")
    void testHandleNullPointer() {
        Exception ex = new NullPointerException("Cannot invoke method on null");
        Response<?> response = handler.handleException(ex, request);

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertEquals("500", response.getCode());
        // 不应暴露 "NullPointerException" 或 "null" 给前端
        assertFalse(response.getMessage().contains("NullPointer"));
        assertFalse(response.getMessage().contains("null"));
        log.info("[异常处理测试] NPE 兜底: {}", response.getMessage());
    }

    // ==================== Response 包装类测试 ====================

    @Test
    @DisplayName("Response.success() 应返回成功状态")
    void testResponseSuccess() {
        Response<String> resp = Response.success("测试数据");
        assertTrue(resp.isSuccess());
        assertEquals("200", resp.getCode());
        assertEquals("操作成功", resp.getMessage());
        assertEquals("测试数据", resp.getData());
        assertNotNull(resp.getTimestamp());
    }

    @Test
    @DisplayName("Response.fail() 应返回失败状态")
    void testResponseFail() {
        Response<?> resp = Response.fail(ResponseCodeEnum.CITY_NAME_EMPTY);
        assertFalse(resp.isSuccess());
        assertEquals("40003", resp.getCode());
        assertEquals("城市名不能为空", resp.getMessage());
        assertNull(resp.getData());
    }

    @Test
    @DisplayName("所有 ResponseCodeEnum 错误码的响应都应正确")
    void testAllErrorCodes() {
        for (ResponseCodeEnum code : ResponseCodeEnum.values()) {
            Response<?> resp = Response.fail(code);
            assertEquals(code.getCode(), resp.getCode());
            assertEquals(code.getDefaultMessage(), resp.getMessage());
            assertFalse(resp.isSuccess());
            assertNotNull(resp.getTimestamp());

            if (!code.getCode().equals("200")) {
                assertNotEquals("200", code.getCode(),
                        code.name() + " 非成功码不应为 200");
            }
        }
        log.info("[Response测试] 全部 {} 个响应码验证通过", ResponseCodeEnum.values().length);
    }
}
