package com.internaladmin.app;

import liquibase.integration.spring.SpringLiquibase;
import jakarta.annotation.Resource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V01-07 prod profile 的无数据库 Cookie 属性测试。
 *
 * <p>该测试复用 {@link NoDatabaseSessionSecurityTest.SessionSecurityApplication}，不扫描主应用或
 * 数据库组件；仅验证 HTTP 响应中的 Secure 属性。因为 HTTP 客户端不会回传 Secure Cookie，
 * 本测试不将 prod profile 的登录保持作为失败条件。</p>
 */
@ActiveProfiles("prod")
@SpringBootTest(
        classes = NoDatabaseSessionSecurityTest.SessionSecurityApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.liquibase.enabled=false")
class NoDatabaseSessionSecurityProductionTest {

    @Resource
    private ApplicationContext applicationContext;

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("prod 响应仅写入一条具有 Secure 属性的 Session 与 XSRF Cookie，且不装配数据库设施")
    void writesSecureCookiesWithoutDatabaseInfrastructure() throws Exception {
        assertEquals(0, applicationContext.getBeanNamesForType(DataSource.class).length);
        assertEquals(0, applicationContext.getBeanNamesForType(SpringLiquibase.class).length);
        assertEquals(0, applicationContext.getBeanNamesForType(SqlSessionFactory.class).length);

        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/public/session-bootstrap"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(200, response.statusCode());
        assertCookieAttributes(response, "JSESSIONID", true);
        assertCookieAttributes(response, "XSRF-TOKEN", false);
    }

    private void assertCookieAttributes(HttpResponse<?> response, String name, boolean httpOnly) {
        List<String> cookies = response.headers().allValues("Set-Cookie").stream()
                .filter(header -> header.startsWith(name + "=")).toList();
        assertEquals(1, cookies.size(), name + " 不得重复或被覆盖");
        String cookie = cookies.get(0);
        assertTrue(cookie.contains("Path=/") && cookie.contains("SameSite=Lax"), name + " 必须为 Path=/、SameSite=Lax");
        assertEquals(httpOnly, cookie.contains("HttpOnly"), name + " HttpOnly 属性不正确");
        assertTrue(cookie.contains("Secure"), name + " 在 prod profile 必须具有 Secure 属性");
    }
}
