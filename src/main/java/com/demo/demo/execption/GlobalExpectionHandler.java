package com.demo.demo.execption;

import com.demo.demo.Utils.Response;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * 全局异常处理器
 * 统一捕获所有 Controller 层抛出的异常，返回友好的错误提示
 *
 * 处理优先级（Spring 按方法匹配精确度选择）：
 *   1. 具体异常（BizException、MethodArgumentTypeMismatchException...）
 *   2. Exception（兜底）
 */
@Slf4j
@RestControllerAdvice
public class GlobalExpectionHandler {

    // ==================== 业务异常 ====================

    /**
     * 处理自定义业务异常
     * 这是最主要的异常处理入口，所有业务逻辑都应抛出 BizException
     */
    @ExceptionHandler(BizException.class)
    @ResponseStatus(HttpStatus.OK)
    public Response<?> handleBizException(BizException e, HttpServletRequest request) {
        log.warn("[业务异常] 路径: {} | 错误码: {} | 消息: {}",
                request.getRequestURI(), e.getCode(), e.getMessage());

        if (e.getCause() != null) {
            log.debug("[业务异常] 原始异常: ", e.getCause());
        }

        return Response.fail(e.getResponseCode(), e.getMessage());
    }

    // ==================== 参数校验异常 ====================

    /**
     * 缺少必填参数
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Response<?> handleMissingParam(MissingServletRequestParameterException e,
                                          HttpServletRequest request) {
        String message = String.format("缺少必填参数: %s", e.getParameterName());
        log.warn("[参数缺失] 路径: {} | {}", request.getRequestURI(), message);
        return Response.fail(ResponseCodeEnum.PARAM_EMPTY, message);
    }

    /**
     * 参数类型不匹配
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Response<?> handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                          HttpServletRequest request) {
        String message = String.format("参数 '%s' 类型错误，期望 %s",
                e.getName(),
                e.getRequiredType() != null ? e.getRequiredType().getSimpleName() : "未知");
        log.warn("[参数类型错误] 路径: {} | {}", request.getRequestURI(), message);
        return Response.fail(ResponseCodeEnum.PARAM_INVALID, message);
    }

    /**
     * 非法参数（如 @RequestParam 校验）
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Response<?> handleIllegalArgument(IllegalArgumentException e,
                                             HttpServletRequest request) {
        log.warn("[非法参数] 路径: {} | {}", request.getRequestURI(), e.getMessage());
        return Response.fail(ResponseCodeEnum.BAD_REQUEST, e.getMessage());
    }

    // ==================== 网络相关异常 ====================

    /**
     * 网络连接超时
     */
    @ExceptionHandler(SocketTimeoutException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Response<?> handleTimeout(SocketTimeoutException e, HttpServletRequest request) {
        log.error("[请求超时] 路径: {} | {}", request.getRequestURI(), e.getMessage());
        return Response.fail(ResponseCodeEnum.THIRD_PARTY_TIMEOUT,
                "请求超时，请稍后重试");
    }

    /**
     * 网络连接失败
     */
    @ExceptionHandler(ConnectException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Response<?> handleConnectFailed(ConnectException e, HttpServletRequest request) {
        log.error("[连接失败] 路径: {} | {}", request.getRequestURI(), e.getMessage());
        return Response.fail(ResponseCodeEnum.NETWORK_ERROR,
                "网络连接失败，请检查网络后重试");
    }

    /**
     * 未知主机（DNS 解析失败）
     */
    @ExceptionHandler(UnknownHostException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Response<?> handleUnknownHost(UnknownHostException e, HttpServletRequest request) {
        log.error("[DNS解析失败] 路径: {} | {}", request.getRequestURI(), e.getMessage());
        return Response.fail(ResponseCodeEnum.NETWORK_ERROR,
                "无法解析服务地址，请检查网络配置");
    }

    // ==================== 兜底处理 ====================

    /**
     * 处理所有未预期的异常（兜底）
     * 确保任何未捕获的异常都能返回友好提示，不会将堆栈信息暴露给客户端
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Response<?> handleException(Exception e, HttpServletRequest request) {
        log.error("[系统异常] 路径: {} | 类型: {} | 消息: {}",
                request.getRequestURI(),
                e.getClass().getSimpleName(),
                e.getMessage(), e);

        // 生产环境不暴露详细错误信息
        String message = "服务器繁忙，请稍后重试";
        return Response.fail(ResponseCodeEnum.INTERNAL_ERROR, message);
    }
}
