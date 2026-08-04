package com.internaladmin.platform.web.exception;

import com.internaladmin.platform.kernel.error.BusinessException;
import com.internaladmin.platform.kernel.error.ErrorCode;
import com.internaladmin.platform.web.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 全局异常边界。
 *
 * <p>统一把异常转换为 {@link ApiResponse}：业务异常返回 400 级错误；
 * 参数校验失败返回参数错误；未预期的异常记录日志并返回内部错误，
 * 响应中不暴露堆栈、SQL 与内部路径。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理业务异常。
     *
     * <p>方法：{@code handleBusinessException}</p>
     *
     * <p>执行链路（共 3 步）：</p>
     * 1. 按错误码映射 HTTP 状态：UNAUTHORIZED→401、FORBIDDEN→403、其余业务异常→400；
     * 2. 将 {@link BusinessException} 转换为 {@link ApiResponse#error(ErrorCode, String)}；
     * 3. 返回对应状态码响应，不记录日志（业务拒绝是预期行为）。
     *
     * @param ex 业务异常
     * @return 401/403/400 + 统一错误响应
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        HttpStatus status = switch (ex.getErrorCode()) {
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .body(ApiResponse.error(ex.getErrorCode(), ex.getMessage()));
    }

    /**
     * 处理参数校验异常。
     *
     * <p>方法：{@code handleValidationException}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 从 {@link MethodArgumentNotValidException} 取首个字段错误信息；
     * 2. 返回 400 + 参数错误响应。
     *
     * @param ex 校验异常
     * @return 400 + 参数错误响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse(ErrorCode.PARAM_ERROR.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ErrorCode.PARAM_ERROR, message));
    }

    /**
     * 处理上传文件超限异常。
     *
     * <p>方法：{@code handleMaxUploadSizeExceeded}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 将 {@link MaxUploadSizeExceededException} 映射为参数错误；
     * 2. 返回 400 + 明确提示（上传文件不能超过 5MB，而非系统内部错误）。
     *
     * @param ex 上传超限异常
     * @return 400 + 参数错误响应
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ErrorCode.PARAM_ERROR, "上传文件大小不能超过 10MB"));
    }

    /**
     * 处理未预期异常。
     *
     * <p>方法：{@code handleUnexpectedException}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 记录完整堆栈日志（服务端定位用，不进入响应）；
     * 2. 返回 500 + 内部错误响应，不暴露异常细节。
     *
     * @param ex 未预期异常
     * @return 500 + 内部错误响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception ex) {
        log.error("未预期异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getMessage()));
    }
}
