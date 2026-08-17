package com.internaladmin.app;

import com.internaladmin.module.iam.controller.AuthController;
import com.internaladmin.module.iam.mapper.RolePermissionMapper;
import com.internaladmin.module.iam.mapper.DepartmentMapper;
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
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
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
import java.net.HttpCookie;
import java.util.List;
import java.util.Optional;

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

    @Test
    @DisplayName("预登录会话轮换后旧会话失效、新会话保持，退出清理会话与 CSRF")
    void rotatesSessionAndClearsLogoutState() throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpResponse<String> bootstrap = send(client, "/api/public/session-bootstrap", "GET", null, null, null);
        assertEquals(200, bootstrap.statusCode());
        assertCookieAttributes(bootstrap, "JSESSIONID", true, false);
        assertCookieAttributes(bootstrap, "XSRF-TOKEN", false, false);
        String oldSessionId = cookieValue(bootstrap, "JSESSIONID").orElseThrow();
        String csrfToken = cookieValue(bootstrap, "XSRF-TOKEN").orElseThrow();

        HttpResponse<String> login = send(client, "/api/auth/login", "POST",
                "JSESSIONID=" + oldSessionId + "; XSRF-TOKEN=" + csrfToken, csrfToken,
                "{\"username\":\"session-user\",\"password\":\"SessionPass123\"}");
        assertEquals(200, login.statusCode());
        String newSessionId = cookieValue(login, "JSESSIONID").orElseThrow();
        assertTrue(!oldSessionId.equals(newSessionId), "登录必须轮换预登录 JSESSIONID");
        assertEquals(401, send(client, "/api/auth/me", "GET", "JSESSIONID=" + oldSessionId, null, null).statusCode());
        assertEquals(200, send(client, "/api/auth/me", "GET", "JSESSIONID=" + newSessionId, null, null).statusCode());

        HttpResponse<String> logout = send(client, "/api/auth/logout", "POST",
                "JSESSIONID=" + newSessionId + "; XSRF-TOKEN=" + csrfToken, csrfToken, null);
        assertEquals(200, logout.statusCode());
        assertEquals(401, send(client, "/api/auth/me", "GET", "JSESSIONID=" + newSessionId, null, null).statusCode());
        List<String> logoutXsrfCookies = logout.headers().allValues("Set-Cookie").stream()
                .filter(header -> header.startsWith("XSRF-TOKEN=")).toList();
        assertEquals(1, logoutXsrfCookies.size(), "退出不得同时写入有效与删除 XSRF-TOKEN cookie");
        assertTrue(logoutXsrfCookies.get(0).contains("Expires=Thu, 01 Jan 1970"),
                () -> "退出必须删除 XSRF-TOKEN cookie，实际 Set-Cookie=" + logout.headers().allValues("Set-Cookie"));
    }

    private HttpResponse<String> send(HttpClient client, String path, String method, String cookie,
                                      String csrfToken, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (cookie != null) request.header("Cookie", cookie);
        if (csrfToken != null) request.header("X-XSRF-TOKEN", csrfToken);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(method.equals("POST") ? request.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body)).build()
                : request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private Optional<String> cookieValue(HttpResponse<?> response, String name) {
        return response.headers().allValues("Set-Cookie").stream()
                .map(HttpCookie::parse).flatMap(List::stream)
                .filter(cookie -> cookie.getName().equals(name)).map(HttpCookie::getValue).findFirst();
    }

    private void assertCookieAttributes(HttpResponse<?> response, String name, boolean httpOnly, boolean secure) {
        List<String> cookies = response.headers().allValues("Set-Cookie").stream()
                .filter(header -> header.startsWith(name + "=")).toList();
        assertEquals(1, cookies.size(), name + " 不得重复或被覆盖");
        String cookie = cookies.get(0);
        assertTrue(cookie.contains("Path=/") && cookie.contains("SameSite=Lax"), name + " 必须为 Path=/、SameSite=Lax");
        assertEquals(httpOnly, cookie.contains("HttpOnly"), name + " HttpOnly 属性不正确");
        assertEquals(secure, cookie.contains("Secure"), name + " Secure 属性不正确");
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
            SessionBootstrapController.class,
            SessionSecurityCollaborators.class
    })
    static class SessionSecurityApplication {
    }

    @RestController
    static class SessionBootstrapController {
        @GetMapping("/api/public/session-bootstrap")
        String bootstrap(HttpServletRequest request) {
            return request.getSession(true).getId();
        }
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
            user.setDepartmentId(1L);
            user.setUsername("session-user");
            user.setDisplayName("会话测试用户");
            user.setPasswordHash(passwordEncoder.encode("SessionPass123"));
            user.setPasswordChanged(true);

            UserMapper userMapper = UserMapper.class.cast(Proxy.newProxyInstance(UserMapper.class.getClassLoader(),
                    new Class<?>[]{UserMapper.class}, (proxy, method, arguments) ->
                    method.getName().equals("selectOne") || method.getName().equals("selectById") ? user : null));
            DepartmentMapper departmentMapper = mapperProxy(DepartmentMapper.class, "selectById", department());
            UserRoleMapper userRoleMapper = mapperProxy(UserRoleMapper.class, "selectList", List.of());
            RolePermissionMapper rolePermissionMapper = mapperProxy(RolePermissionMapper.class, "selectList", List.of());
            SystemConfigMapper systemConfigMapper = mapperProxy(SystemConfigMapper.class, "selectOne", falseConfig());
            return new AuthService(userMapper, departmentMapper, userRoleMapper, rolePermissionMapper, passwordEncoder,
                    new SystemConfigService(systemConfigMapper), sessionAuthenticationStrategy,
                    securityContextRepository, authLogoutHandler);
        }

        private static SystemConfigDO falseConfig() {
            SystemConfigDO config = new SystemConfigDO();
            config.setParamKey(SystemConfigService.KEY_FORCE_PASSWORD_CHANGE);
            config.setParamValue("false");
            return config;
        }

        private static com.internaladmin.module.iam.model.entity.DepartmentDO department() {
            com.internaladmin.module.iam.model.entity.DepartmentDO department =
                    new com.internaladmin.module.iam.model.entity.DepartmentDO();
            department.setId(1L);
            department.setCode("ROOT");
            department.setName("根部门");
            department.setEnabled(1);
            return department;
        }

        private static <T> T mapperProxy(Class<T> mapperType, String supportedMethod, Object result) {
            return mapperType.cast(Proxy.newProxyInstance(
                    mapperType.getClassLoader(),
                    new Class<?>[]{mapperType},
                    (proxy, method, arguments) -> method.getName().equals(supportedMethod) ? result : null));
        }
    }
}
