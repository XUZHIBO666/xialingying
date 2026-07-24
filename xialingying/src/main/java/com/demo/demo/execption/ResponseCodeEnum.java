package com.demo.demo.execption;

import lombok.Getter;

/**
 * 统一响应状态码枚举
 * 所有业务异常和系统异常都使用此枚举定义错误码和消息
 */
@Getter
public enum ResponseCodeEnum {

    // ==================== 成功 ====================
    SUCCESS("200", "操作成功"),

    // ==================== 客户端错误 4xx ====================
    BAD_REQUEST("400", "请求参数错误"),
    PARAM_EMPTY("40001", "必填参数为空"),
    PARAM_INVALID("40002", "参数格式无效"),
    CITY_NAME_EMPTY("40003", "城市名不能为空"),
    CITY_NOT_FOUND("40004", "未找到该城市，请检查城市名拼写"),
    CITY_NAME_INVALID("40005", "城市名无效，请使用中文/拼音/英文城市名"),

    // ==================== 服务端错误 5xx ====================
    INTERNAL_ERROR("500", "服务器内部错误"),
    WEATHER_API_ERROR("50001", "天气服务暂时不可用，请稍后重试"),
    WEATHER_PARSE_ERROR("50002", "天气数据解析失败"),
    AI_SERVICE_ERROR("50003", "AI 服务调用失败"),
    ILINK_ERROR("50004", "iLink 通信异常"),
    NETWORK_ERROR("50005", "网络请求异常，请检查网络连接"),

    // ==================== 第三方服务错误 ====================
    THIRD_PARTY_TIMEOUT("50301", "第三方服务请求超时"),
    THIRD_PARTY_UNAVAILABLE("50302", "第三方服务不可用");

    /**
     * 状态码
     */
    private final String code;

    /**
     * 默认消息
     */
    private final String defaultMessage;

    ResponseCodeEnum(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }
}
