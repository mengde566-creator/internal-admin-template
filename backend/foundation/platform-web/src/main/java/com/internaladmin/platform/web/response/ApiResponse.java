package com.internaladmin.platform.web.response;

import com.internaladmin.platform.kernel.error.ErrorCodeContract;

/**
 * 统一 API 响应。
 *
 * <p>所有 Controller 接口返回本结构：{@code success} 表示结果是否成功，{@code code} 为稳定语义码，
 * {@code message} 为描述，{@code data} 为业务数据（失败时为 {@code null}）。</p>
 *
 * @param <T> 业务数据类型
 */
public class ApiResponse<T> {

    private final boolean success;
    private final String code;
    private final String message;
    private final T data;

    private ApiResponse(boolean success, String code, String message, T data) {
        this.success = success;
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
        return new ApiResponse<>(true, "SUCCESS", "处理成功", data);
    }

    /** 构造带有具体业务成功提示的响应，仍使用统一四字段外壳。 */
    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(true, "SUCCESS", message, data);
    }

    /**
     * 构造失败响应。
     *
     * @param errorCode 错误码
     * @param message   错误描述
     * @param <T>       数据类型
     * @return 失败响应
     */
    public static <T> ApiResponse<T> error(ErrorCodeContract errorCode, String message) {
        return new ApiResponse<>(false, errorCode.getCode(), message, null);
    }

    public boolean isSuccess() {
        return success;
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
