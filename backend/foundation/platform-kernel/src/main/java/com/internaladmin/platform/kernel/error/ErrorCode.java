package com.internaladmin.platform.kernel.error;

/**
 * 通用错误码。
 *
 * <p>业务模块的专属错误码由各模块自行定义并继承本接口；本枚举只承载基础模块共用的错误语义。</p>
 */
public enum ErrorCode {

    /** 请求参数不合法。 */
    PARAM_ERROR("PARAM_ERROR", "请求参数不合法"),

    /** 数据不存在。 */
    NOT_FOUND("NOT_FOUND", "数据不存在"),

    /** 业务规则拒绝。 */
    BUSINESS_REJECTED("BUSINESS_REJECTED", "业务规则不允许该操作"),

    /** 数据冲突（重复、并发等）。 */
    CONFLICT("CONFLICT", "数据冲突"),

    /** 未登录。 */
    UNAUTHORIZED("UNAUTHORIZED", "未登录或登录已失效"),

    /** 无权限。 */
    FORBIDDEN("FORBIDDEN", "没有操作权限"),

    /** 系统内部错误。 */
    INTERNAL_ERROR("INTERNAL_ERROR", "系统内部错误");

    private final String code;
    private final String message;

    ErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    /** 返回稳定的错误码字符串，供 API 响应使用。 */
    public String getCode() {
        return code;
    }

    /** 返回默认错误描述。 */
    public String getMessage() {
        return message;
    }
}
