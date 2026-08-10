package com.internaladmin.platform.security.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * CSRF Cookie 过滤器（SPA 集成）。
 *
 * <p>Spring Security 6 的 CSRF token 延迟生成：只有非安全方法（POST/PUT/DELETE）
 * 请求才会生成 token。SPA 前端需要先读到 token 才能发起登录等 POST 请求，
 * 因此本过滤器在每个响应中把当前 token 写入 {@code XSRF-TOKEN} cookie
 * （HttpOnly=false，前端 JavaScript 可读）。</p>
 */
public final class CsrfCookieFilter extends OncePerRequestFilter {

    private final boolean secure;

    public CsrfCookieFilter(boolean secure) {
        this.secure = secure;
    }

    /**
     * 将 CSRF token 写入响应 cookie 后继续过滤器链。
     *
     * <p>方法：{@code doFilterInternal}</p>
     *
     * <p>执行链路（共 3 步）：</p>
     * 1. 从请求属性取 {@link CsrfToken}（访问属性会触发延迟生成）；
     * 2. token 存在时写入 {@code XSRF-TOKEN} cookie（Path=/、HttpOnly=false）；
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
            Cookie cookie = new Cookie("XSRF-TOKEN", csrfToken.getToken());
            cookie.setPath("/");
            cookie.setSecure(secure);
            cookie.setAttribute("SameSite", "Lax");
            response.addCookie(cookie);
        }
        filterChain.doFilter(request, response);
    }
}
