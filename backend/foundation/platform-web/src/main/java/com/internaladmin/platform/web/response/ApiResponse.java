package com.internaladmin.platform.web.response;

import com.internaladmin.platform.kernel.error.ErrorCode;

/**
 * 统一 API 响应。
 *
 * <p>所有 Controller 接口返回本结构：{@code code} 为稳定错误码、{@code message} 为描述、
 * {@code data} 为业务数据（失败时为 {@code null}）。</p>
 *
 * @param <T> 业务数据类型
 */
public class ApiResponse<T> {

    private final String code;
    private final String message;
    private final T data;

    private ApiResponse(String code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /**
     * 构造成功响应。
     *
     * @param data 业务数据
     * @param <T>  数据类型
     * @return 成功响应
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>("OK", "成功", data);
    }

    /**
     * 构造失败响应。
     *
     * @param errorCode 错误码
     * @param message   错误描述
     * @param <T>       数据类型
     * @return 失败响应
     */
    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.getCode(), message, null);
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }
}
