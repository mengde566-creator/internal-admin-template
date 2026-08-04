package com.internaladmin.platform.security.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码编码器配置。
 *
 * <p>使用 BCrypt 自适应哈希；密码只保存哈希，禁止保存或记录明文。</p>
 */
@Configuration
public class PasswordConfig {

    /**
     * 提供 BCrypt 密码编码器。
     *
     * @return BCryptPasswordEncoder
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
