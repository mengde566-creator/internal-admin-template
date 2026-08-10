package com.internaladmin.app;

import com.internaladmin.module.iam.controller.AuthController;
import com.internaladmin.module.iam.mapper.RolePermissionMapper;
import com.internaladmin.module.iam.mapper.SystemConfigMapper;
import com.internaladmin.module.iam.mapper.UserMapper;
import com.internaladmin.module.iam.mapper.UserRoleMapper;
import com.internaladmin.module.iam.model.entity.SystemConfigDO;
import com.internaladmin.module.iam.model.entity.UserDO;
import com.internaladmin.module.iam.service.AuthService;
import com.internaladmin.module.iam.service.SystemConfigService;
import com.internaladmin.platform.security.config.SecurityConfig;
import com.internaladmin.platform.security.exception.SecurityExceptionHandler;
import com.internaladmin.platform.web.exception.GlobalExceptionHandler;
import jakarta.annotation.Resource;
import liquibase.integration.spring.SpringLiquibase;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceInitializationAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JndiDataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.XADataSourceAutoConfiguration;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V01-07 专用无数据库会话安全测试应用。
 *
 * <p>该测试显式导入 Security、认证 Controller 与异常处理，禁止组件扫描、主应用、
 * 数据库自动配置和业务 Service 实现参与装配。后续用例在这个真实嵌入式 HTTP 容器中验证
 * Cookie、会话轮换与退出行为。</p>
 */
@SpringBootTest(
        classes = NoDatabaseSessionSecurityTest.SessionSecurityApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.liquibase.enabled=false")
class NoDatabaseSessionSecurityTest {

    @Resource
    private ApplicationContext applicationContext;

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("无数据库会话安全测试应用启动真实 HTTP 服务器且不装配数据库基础设施")
    void startsWithoutDatabaseInfrastructure() throws Exception {
        assertTrue(applicationContext.getBeanNamesForType(DataSource.class).length == 0,
                "无数据库会话安全测试不得创建 DataSource Bean");
        assertTrue(applicationContext.getBeanNamesForType(SpringLiquibase.class).length == 0,
                "无数据库会话安全测试不得创建 Liquibase Bean");
        assertTrue(applicationContext.getBeanNamesForType(SqlSessionFactory.class).length == 0,
                "无数据库会话安全测试不得创建 MyBatis SqlSessionFactory Bean");

        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(200, response.statusCode());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            DataSourceInitializationAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            JndiDataSourceAutoConfiguration.class,
            XADataSourceAutoConfiguration.class,
            LiquibaseAutoConfiguration.class
    })
    @Import({
            SecurityConfig.class,
            GlobalExceptionHandler.class,
            SecurityExceptionHandler.class,
            AuthController.class,
            SessionSecurityCollaborators.class
    })
    static class SessionSecurityApplication {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SessionSecurityCollaborators {

        @Bean
        AuthService authService(SessionAuthenticationStrategy sessionAuthenticationStrategy,
                                SecurityContextRepository securityContextRepository,
                                LogoutHandler authLogoutHandler) {
            PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
            UserDO user = new UserDO();
            user.setId(7L);
            user.setUsername("session-user");
            user.setDisplayName("会话测试用户");
            user.setPasswordHash(passwordEncoder.encode("SessionPass123"));
            user.setPasswordChanged(true);

            UserMapper userMapper = mapperProxy(UserMapper.class, "selectOne", user);
            UserRoleMapper userRoleMapper = mapperProxy(UserRoleMapper.class, "selectList", List.of());
            RolePermissionMapper rolePermissionMapper = mapperProxy(RolePermissionMapper.class, "selectList", List.of());
            SystemConfigMapper systemConfigMapper = mapperProxy(SystemConfigMapper.class, "selectOne", falseConfig());
            return new AuthService(userMapper, userRoleMapper, rolePermissionMapper, passwordEncoder,
                    new SystemConfigService(systemConfigMapper), sessionAuthenticationStrategy,
                    securityContextRepository, authLogoutHandler);
        }

        private static SystemConfigDO falseConfig() {
            SystemConfigDO config = new SystemConfigDO();
            config.setParamKey(SystemConfigService.KEY_FORCE_PASSWORD_CHANGE);
            config.setParamValue("false");
            return config;
        }

        private static <T> T mapperProxy(Class<T> mapperType, String supportedMethod, Object result) {
            return mapperType.cast(Proxy.newProxyInstance(
                    mapperType.getClassLoader(),
                    new Class<?>[]{mapperType},
                    (proxy, method, arguments) -> method.getName().equals(supportedMethod) ? result : null));
        }
    }
}
