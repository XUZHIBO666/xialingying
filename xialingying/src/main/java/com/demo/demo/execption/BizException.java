package com.demo.demo.execption;

import lombok.Getter;

/**
 * 统一业务异常类
 * 所有业务逻辑异常统一使用此类抛出，由 GlobalExceptionHandler 统一处理
 *
 * 使用示例：
 *   throw new BizException(ResponseCodeEnum.CITY_NOT_FOUND);
 *   throw new BizException(ResponseCodeEnum.PARAM_INVALID, "年龄必须在 1-150 之间");
 */
@Getter
public class BizException extends RuntimeException implements BaseExceptionInterface {

    /**
     * 响应码枚举
     */
    private final ResponseCodeEnum responseCode;

    /**
     * 自定义消息（可选，为空时使用枚举默认消息）
     */
    private final String customMessage;

    /**
     * 原始异常（可选，用于日志追踪）
     */
    private final Throwable cause;

    // ==================== 构造方法 ====================

    /**
     * 仅使用枚举，消息取枚举默认值
     */
    public BizException(ResponseCodeEnum responseCode) {
        this(responseCode, null, null);
    }

    /**
     * 使用枚举 + 自定义消息
     */
    public BizException(ResponseCodeEnum responseCode, String customMessage) {
        this(responseCode, customMessage, null);
    }

    /**
     * 使用枚举 + 原始异常（消息取枚举默认值）
     */
    public BizException(ResponseCodeEnum responseCode, Throwable cause) {
        this(responseCode, null, cause);
    }

    /**
     * 使用枚举 + 自定义消息 + 原始异常
     */
    public BizException(ResponseCodeEnum responseCode, String customMessage, Throwable cause) {
        super(customMessage != null ? customMessage : responseCode.getDefaultMessage(), cause);
        this.responseCode = responseCode;
        this.customMessage = customMessage;
        this.cause = cause;
    }

    // ==================== 接口实现 ====================

    @Override
    public String getCode() {
        return responseCode.getCode();
    }

    @Override
    public String getMessage() {
        return customMessage != null ? customMessage : responseCode.getDefaultMessage();
    }
}
