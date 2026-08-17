package com.internaladmin.app;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 应用启动入口。
 *
 * <p>唯一装配模块：扫描 {@code com.internaladmin} 全部包（基础模块 + 业务模块组件）；
 * Mapper 按各业务模块的 mapper 包显式扫描。新增业务模块时在此追加其 mapper 包。</p>
 */
@SpringBootApplication(scanBasePackages = "com.internaladmin")
@MapperScan({
        "com.internaladmin.module.iam.mapper",
        "com.internaladmin.module.file.mapper",
        "com.internaladmin.module.site.mapper",
        "com.internaladmin.module.audit.mapper",
        "com.internaladmin.module.warehouse.mapper"
})
public class Application {

    /**
     * 启动 Spring Boot 应用。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
