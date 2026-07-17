package com.demo.demo.Utils;

import com.demo.demo.execption.ResponseCodeEnum;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 统一响应包装类
 * 所有 API 接口统一使用此类返回，确保前端能获得一致的响应格式
 *
 * 成功示例：
 *   Response.success(data)
 *   Response.success("操作完成")
 *
 * 失败示例：
 *   Response.fail(ResponseCodeEnum.CITY_NOT_FOUND)
 *   Response.fail("40001", "城市名不能为空")
 */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Response<T> {

    /**
     * 状态码：200 表示成功，其他表示失败
     */
    private final String code;

    /**
     * 提示消息
     */
    private final String message;

    /**
     * 响应数据
     */
    private final T data;

    /**
     * 响应时间戳
     */
    private final String timestamp;

    /**
     * 是否成功
     */
    private final boolean success;

    private Response(String code, String message, T data, boolean success) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.success = success;
        this.timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    // ==================== 成功响应 ====================

    /**
     * 成功（无数据）
     */
    public static <T> Response<T> success() {
        return new Response<>(ResponseCodeEnum.SUCCESS.getCode(),
                ResponseCodeEnum.SUCCESS.getDefaultMessage(), null, true);
    }

    /**
     * 成功（带数据）
     */
    public static <T> Response<T> success(T data) {
        return new Response<>(ResponseCodeEnum.SUCCESS.getCode(),
                ResponseCodeEnum.SUCCESS.getDefaultMessage(), data, true);
    }

    /**
     * 成功（自定义消息 + 数据）
     */
    public static <T> Response<T> success(String message, T data) {
        return new Response<>(ResponseCodeEnum.SUCCESS.getCode(), message, data, true);
    }

    // ==================== 失败响应 ====================

    /**
     * 失败（使用枚举）
     */
    public static <T> Response<T> fail(ResponseCodeEnum responseCode) {
        return new Response<>(responseCode.getCode(), responseCode.getDefaultMessage(), null, false);
    }

    /**
     * 失败（使用枚举 + 自定义消息）
     */
    public static <T> Response<T> fail(ResponseCodeEnum responseCode, String customMessage) {
        return new Response<>(responseCode.getCode(), customMessage, null, false);
    }

    /**
     * 失败（自定义 code + 消息）
     */
    public static <T> Response<T> fail(String code, String message) {
        return new Response<>(code, message, null, false);
    }
}
