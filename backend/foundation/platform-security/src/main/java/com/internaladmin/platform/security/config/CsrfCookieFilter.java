package com.internaladmin.platform.security.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * CSRF Cookie 过滤器（SPA 集成）。
 *
 * <p>Spring Security 的 CSRF token 延迟生成。SPA 前端需要在发起登录等 POST 请求前
 * 取得 token；本过滤器仅触发 {@code CookieCsrfTokenRepository} 对延迟 token 的标准
 * 持久化。Cookie 的序列化与写入始终由该 repository 独占，避免同一响应重复写入
 * {@code XSRF-TOKEN}（HttpOnly=false，前端 JavaScript 可读）。</p>
 */
public final class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "/api/auth/logout".equals(request.getRequestURI());
    }

    /**
     * 触发 CSRF token 的标准 Cookie 持久化后继续过滤器链。
     *
     * <p>方法：{@code doFilterInternal}</p>
     *
     * <p>执行链路（共 3 步）：</p>
     * 1. 从请求属性取 {@link CsrfToken}，取得 Spring Security 暴露的延迟 token；
     * 2. token 存在时调用 {@link CsrfToken#getToken()}，由 {@code CookieCsrfTokenRepository}
     *    以其唯一写入源将 token 持久化为 {@code XSRF-TOKEN} cookie；
     * 3. 调用 {@link FilterChain#doFilter} 继续后续处理。
     *
     * @param request      HTTP 请求
     * @param response     HTTP 响应
     * @param filterChain  过滤器链
     * @throws ServletException Servlet 异常
     * @throws IOException      IO 异常
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken != null) {
            csrfToken.getToken();
        }
        // 读取请求属性触发 CookieCsrfTokenRepository 的延迟 token 生成与唯一 Cookie 写入。
        filterChain.doFilter(request, response);
    }
}
