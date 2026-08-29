package com.internaladmin.platform.kernel.error;

/**
 * 业务异常。
 *
 * <p>业务拒绝、数据不存在、参数错误等可预期的业务失败统一抛出本异常，
 * 由 {@code platform-web} 的全局异常边界转换为明确的 API 错误响应。</p>
 */
public class BusinessException extends RuntimeException {

    private final ErrorCodeContract errorCode;

    /**
     * 创建业务异常。
     *
     * @param errorCode 错误码
     * @param message   面向调用方的错误描述，禁止包含堆栈、SQL 与内部路径
     */
    public BusinessException(ErrorCodeContract errorCode, String message) {
        super(message);
        if (errorCode == null) {
            throw new IllegalArgumentException("errorCode不能为空");
        }
        this.errorCode = errorCode;
    }

    /** 保留已编译模块对基础错误枚举构造签名的二进制兼容，语义仍归属于统一契约。 */
    public BusinessException(ErrorCode errorCode, String message) {
        this((ErrorCodeContract) errorCode, message);
    }

    /** 返回错误码。 */
    public ErrorCodeContract getErrorCode() {
        return errorCode;
    }
}
