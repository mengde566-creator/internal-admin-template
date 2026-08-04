package com.internaladmin.platform.security.exception;

import com.internaladmin.platform.kernel.error.ErrorCode;
import com.internaladmin.platform.web.response.ApiResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 安全异常边界。
 *
 * <p>方法级权限校验（@PreAuthorize）拒绝时抛出 {@link AuthorizationDeniedException}（Spring Security 7）
 * 或其父类 {@link AccessDeniedException}，本处理器将其转换为 403 统一响应
 * （认证基线：已登录但无权限返回 403）。</p>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityExceptionHandler {

    /**
     * 处理方法级权限不足异常（Spring Security 7）。
     *
     * <p>方法：{@code handleAuthorizationDenied}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 将 {@link AuthorizationDeniedException} 映射为 {@link ErrorCode#FORBIDDEN}；
     * 2. 返回 403 + 统一错误响应（不记录日志，越权拒绝是预期行为）。
     *
     * @param ex 权限不足异常
     * @return 403 + 统一错误响应
     */
    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthorizationDenied(AuthorizationDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.getMessage()));
    }

    /**
     * 处理权限不足异常（兼容旧版本异常类型）。
     *
     * <p>方法：{@code handleAccessDenied}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 将 {@link AccessDeniedException} 映射为 {@link ErrorCode#FORBIDDEN}；
     * 2. 返回 403 + 统一错误响应（不记录日志，越权拒绝是预期行为）。
     *
     * @param ex 权限不足异常
     * @return 403 + 统一错误响应
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.getMessage()));
    }
}
