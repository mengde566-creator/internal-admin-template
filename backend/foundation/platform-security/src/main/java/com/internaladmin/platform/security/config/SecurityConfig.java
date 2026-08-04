package com.internaladmin.platform.security.config;

import tools.jackson.databind.ObjectMapper;
import com.internaladmin.platform.kernel.error.ErrorCode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;
import java.util.Map;

/**
 * Spring Security 基础配置。
 *
 * <p>0.1 认证基线：服务端 Session + HttpOnly Cookie + CSRF 防护。
 * 公开接口（登录、健康检查、公开主页与公开文件）放行，其余接口需认证；
 * 未登录返回 401、无权限返回 403，不重定向到登录页。</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * 配置安全过滤链。
     *
     * <p>方法：{@code securityFilterChain}</p>
     *
     * <p>执行链路（共 6 步）：</p>
     * 1. 启用 CSRF，使用 {@link CookieCsrfTokenRepository}（HttpOnly=false，前端可读 XSRF-TOKEN 并回传）；
     * 2. 放行公开路径：登录、健康检查、公开主页与公开文件（/api/public/**）；
     * 3. 其余请求要求认证（会话由服务端 Session 维持）；
     * 4. 关闭默认表单登录与 httpBasic（统一走 /api/auth/login）；
     * 5. 关闭默认退出（退出由业务接口销毁 Session）；
     * 6. 配置 401/403 JSON 响应（未登录 401、无权限 403）。
     *
     * @param http HttpSecurity
     * @return 安全过滤链
     * @throws Exception 配置异常
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf
                        // SPA + cookie 直读：前端从 XSRF-TOKEN cookie 取值放入 X-XSRF-TOKEN 请求头
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .addFilterAfter(new CsrfCookieFilter(),
                        org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/api/public/**",
                                "/error").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(401);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding("UTF-8");
                            response.getWriter().write(objectMapper.writeValueAsString(
                                    Map.of("code", ErrorCode.UNAUTHORIZED.getCode(),
                                            "message", ErrorCode.UNAUTHORIZED.getMessage(),
                                            "data", "")));
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            response.setStatus(403);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding("UTF-8");
                            response.getWriter().write(objectMapper.writeValueAsString(
                                    Map.of("code", ErrorCode.FORBIDDEN.getCode(),
                                            "message", ErrorCode.FORBIDDEN.getMessage(),
                                            "data", "")));
                        }));
        return http.build();
    }

    /**
     * 开发模式 CORS 配置（前后端分离：Vite 5173 → 后端 8080）。
     *
     * <p>允许携带凭据（Session Cookie），origin 使用明确值而非通配符；
     * 生产环境同源部署时应收紧或移除本配置。</p>
     *
     * @return CORS 配置源
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://127.0.0.1:5173", "http://localhost:5173"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
