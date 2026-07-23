package com.weathercli.exception;

/**
 * 自定义 CLI 异常，用于统一包装所有错误。
 */
public class CLIException extends Exception {

    private final ErrorCode errorCode;

    public CLIException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public CLIException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * 错误码枚举
     */
    public enum ErrorCode {
        INVALID_COMMAND(1, "无效命令"),
        MISSING_ARGUMENT(2, "缺少参数"),
        NETWORK_ERROR(3, "网络错误"),
        API_ERROR(4, "API 调用失败"),
        PARSE_ERROR(5, "数据解析失败"),
        CONFIG_ERROR(6, "配置错误"),
        UNKNOWN_ERROR(99, "未知错误");

        private final int code;
        private final String description;

        ErrorCode(int code, String description) {
            this.code = code;
            this.description = description;
        }

        public int getCode() {
            return code;
        }

        public String getDescription() {
            return description;
        }
    }
}
