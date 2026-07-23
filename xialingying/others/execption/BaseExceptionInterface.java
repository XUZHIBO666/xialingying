package com.demo.demo.execption;

/**
 * 基础异常接口
 * 所有自定义业务异常必须实现此接口，便于全局异常处理器统一处理
 */
public interface BaseExceptionInterface {

    /**
     * 获取异常码
     */
    String getCode();

    /**
     * 获取异常消息
     */
    String getMessage();

    /**
     * 获取响应码枚举
     */
    ResponseCodeEnum getResponseCode();
}
