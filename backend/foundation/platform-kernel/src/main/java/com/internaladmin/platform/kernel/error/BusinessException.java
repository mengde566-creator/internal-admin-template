package com.internaladmin.platform.kernel.error;

/**
 * 业务异常。
 *
 * <p>业务拒绝、数据不存在、参数错误等可预期的业务失败统一抛出本异常，
 * 由 {@code platform-web} 的全局异常边界转换为明确的 API 错误响应。</p>
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    /**
     * 创建业务异常。
     *
     * @param errorCode 错误码
     * @param message   面向调用方的错误描述，禁止包含堆栈、SQL 与内部路径
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /** 返回错误码。 */
    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
