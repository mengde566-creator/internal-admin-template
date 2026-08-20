package com.internaladmin.app;

import com.internaladmin.app.config.OpenApiContractConfig;
import com.internaladmin.module.agent.config.AgentConfiguration;
import com.internaladmin.module.agent.controller.AiCapabilitiesController;
import com.internaladmin.module.file.api.FileQueryApi;
import com.internaladmin.module.file.controller.FileController;
import com.internaladmin.module.file.service.FileStorageService;
import com.internaladmin.module.iam.controller.AuthController;
import com.internaladmin.module.iam.controller.DepartmentController;
import com.internaladmin.module.iam.controller.RoleController;
import com.internaladmin.module.iam.controller.SystemConfigController;
import com.internaladmin.module.iam.controller.UserController;
import com.internaladmin.module.iam.model.dto.SystemConfigDTO;
import com.internaladmin.module.iam.service.AuthService;
import com.internaladmin.module.iam.service.DepartmentService;
import com.internaladmin.module.iam.service.RoleService;
import com.internaladmin.module.iam.service.SystemConfigService;
import com.internaladmin.module.iam.service.UserService;
import com.internaladmin.module.site.controller.SiteController;
import com.internaladmin.module.site.service.SiteService;
import com.internaladmin.module.warehouse.controller.WarehouseController;
import com.internaladmin.module.warehouse.controller.WarehouseQueryController;
import com.internaladmin.module.warehouse.service.WarehouseService;
import com.internaladmin.module.iam.api.IamActorApi;
import com.internaladmin.platform.security.config.SecurityConfig;
import com.internaladmin.platform.security.exception.SecurityExceptionHandler;
import com.internaladmin.platform.web.exception.GlobalExceptionHandler;
import com.internaladmin.platform.web.response.ApiResponse;
import com.internaladmin.platform.web.response.IdResultDTO;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 无数据库 OpenAPI 契约导出与 Jackson 语义验证。
 *
 * <p>本测试只装配显式列出的 Controller、MVC/Security、异常处理、Jackson 与 springdoc 配置，
 * 不扫描主启动类、数据源配置、Mapper、Service 实现或初始化器。所有 Controller 协作者均为 Mock，
 * 并在导出前机械断言不存在数据库基础设施 Bean。</p>
 */
@ActiveProfiles("contract")
@SpringBootTest(
        classes = NoDatabaseOpenApiContractTest.ContractApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "springdoc.api-docs.enabled=true",
                "spring.liquibase.enabled=false",
                "app.ai.enabled=false",
                "app.storage-root=./build/no-database-contract-storage"
        })
@AutoConfigureMockMvc
class NoDatabaseOpenApiContractTest {

    private static final long CONTRACT_ID = 9_007_199_254_740_993L;

    @Resource
    private MockMvc mockMvc;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private ApplicationContext applicationContext;

    @Resource
    private UserService userService;

    @Resource
    private RoleService roleService;

    /**
     * 导出真实 Controller/DTO 的 springdoc 规范，并证明本上下文没有数据库基础设施。
     *
     * <p>方法：{@code exportOpenApiWithoutDatabase}</p>
     *
     * <p>执行链路（共 4 步）：</p>
     * 1. 调用 {@link #assertNoDatabaseInfrastructure()} 验证 DataSource、Liquibase 与 MyBatis Bean 均不存在；
     * 2. 经显式导入的 {@link SecurityConfig} 请求 {@code /v3/api-docs}，由 springdoc 解析真实 Controller 与 DTO；
     * 3. 解析规范以确认生成来源标记与路径集合已产生；
     * 4. 将原始 springdoc JSON 写入脚本提供的临时输出路径，后续由脚本规范化并生成 TypeScript。</p>
     *
     * @throws Exception MockMvc 或临时文件写入失败时抛出
     */
    @Test
    @DisplayName("无数据库上下文导出真实 OpenAPI，并机械证明没有数据库基础设施 Bean")
    void exportOpenApiWithoutDatabase() throws Exception {
        assertNoDatabaseInfrastructure();

        String json = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode specification = objectMapper.readTree(json);
        assertTrue(specification.path("openapi").asText().startsWith("3."));
        assertTrue(specification.path("x-generated-by").asText().contains("springdoc-openapi 3.1.0"));
        assertFalse(specification.path("paths").isMissingNode());

        writeRawSpecification(json);
    }

    /**
     * 验证真实 Jackson 序列化的 ID 与空响应语义。
     *
     * <p>方法：{@code serializesContractValuesWithoutDatabase}</p>
     *
     * <p>执行链路（共 4 步）：</p>
     * 1. Mock 用户与角色创建 Service 的返回 ID，并通过真实 Controller、Security 与 Jackson 响应链路验证字符串 ID；
     * 2. 直接序列化 {@link FileController.UploadResult}，验证文件 ID 的 getter 注解生效；
     * 3. 直接序列化 {@link SystemConfigDTO}，验证系统参数 ID 的 getter 注解生效；
     * 4. 序列化 {@link ApiResponse#ok(Object)} 的空成功结果，验证 data 实际为 JSON null。</p>
     *
     * @throws Exception MockMvc 或 JSON 断言失败时抛出
     */
    @Test
    @DisplayName("无数据库 Jackson 验证用户、角色、文件、系统参数 ID 与空响应语义")
    void serializesContractValuesWithoutDatabase() throws Exception {
        when(userService.create(any())).thenReturn(CONTRACT_ID);
        when(roleService.create(any())).thenReturn(CONTRACT_ID);

        JsonNode userResult = objectMapper.readTree(mockMvc.perform(post("/api/users")
                        .with(user("contract-user").authorities(
                                new SimpleGrantedAuthority("iam:user:manage")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"contract-user\",\"displayName\":\"契约用户\","
                                + "\"departmentId\":\"1\",\"password\":\"ContractPass123\",\"roleIds\":[\"1\"]}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertText(userResult.path("data").path("id"), "用户创建 ID 必须序列化为字符串");

        JsonNode roleResult = objectMapper.readTree(mockMvc.perform(post("/api/roles")
                        .with(user("contract-role").authorities(
                                new SimpleGrantedAuthority("iam:role:manage")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"CONTRACT_ROLE\",\"name\":\"契约角色\",\"permissionCodes\":[]}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertText(roleResult.path("data").path("id"), "角色创建 ID 必须序列化为字符串");

        assertText(objectMapper.readTree(objectMapper.writeValueAsString(
                        ApiResponse.ok(new FileController.UploadResult(CONTRACT_ID))))
                        .path("data").path("fileId"), "文件 ID 必须序列化为字符串");

        SystemConfigDTO systemConfig = new SystemConfigDTO();
        systemConfig.setId(CONTRACT_ID);
        assertText(objectMapper.readTree(objectMapper.writeValueAsString(ApiResponse.ok(systemConfig)))
                        .path("data").path("id"), "系统参数 ID 必须序列化为字符串");

        JsonNode emptyResult = objectMapper.readTree(objectMapper.writeValueAsString(ApiResponse.ok(null)));
        assertTrue(emptyResult.path("data").isNull(), "空成功响应的 data 必须是 JSON null");
    }

    @Test
    @DisplayName("Agent 关闭时能力发现仍受认证保护并返回空能力集合")
    void capabilitiesEndpointIsProtectedAndDisabledWithoutDatabase() throws Exception {
        mockMvc.perform(get("/api/ai/capabilities"))
                .andExpect(status().isUnauthorized());

        JsonNode response = objectMapper.readTree(mockMvc.perform(get("/api/ai/capabilities")
                        .with(user("contract-user")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertTrue(response.path("data").path("enabled").isBoolean());
        assertFalse(response.path("data").path("enabled").asBoolean());
        assertTrue(response.path("data").path("availableAdapters").isArray());
        assertTrue(response.path("data").path("availableAdapters").isEmpty());
        assertTrue(response.path("data").path("uiModes").isEmpty());
        assertTrue(response.path("data").path("features").isEmpty());
    }

    /**
     * 机械验证数据库基础设施没有被装配。
     *
     * <p>方法：{@code assertNoDatabaseInfrastructure}</p>
     *
     * <p>执行链路（共 3 步）：</p>
     * 1. 查询 {@link DataSource} Bean，确保没有数据源或连接池；
     * 2. 查询 {@link SpringLiquibase} Bean，确保没有迁移执行入口；
     * 3. 查询 {@link SqlSessionFactory} Bean，确保没有 MyBatis 会话工厂。</p>
     */
    private void assertNoDatabaseInfrastructure() {
        assertTrue(applicationContext.getBeanNamesForType(DataSource.class).length == 0,
                "无数据库契约上下文不得创建 DataSource Bean");
        assertTrue(applicationContext.getBeanNamesForType(SpringLiquibase.class).length == 0,
                "无数据库契约上下文不得创建 Liquibase Bean");
        assertTrue(applicationContext.getBeanNamesForType(SqlSessionFactory.class).length == 0,
                "无数据库契约上下文不得创建 MyBatis SqlSessionFactory Bean");
    }

    /**
     * 将 springdoc 原始结果写入导出器指定的临时文件。
     *
     * <p>方法：{@code writeRawSpecification}</p>
     *
     * <p>执行链路（共 3 步）：</p>
     * 1. 读取 {@code openapi.contract.output} 系统属性，缺失时立即失败；
     * 2. 创建输出文件所在的临时目录；
     * 3. 以 UTF-8 写入 MockMvc 返回的原始 springdoc JSON，不在 Java 侧手写或重建规范。</p>
     *
     * @param json springdoc 端点原始 JSON
     * @throws IOException 输出路径不可写时抛出
     */
    private void writeRawSpecification(String json) throws IOException {
        String output = System.getProperty("openapi.contract.output");
        assertNotNull(output, "无数据库导出器必须提供 openapi.contract.output 临时路径");
        Path outputPath = Path.of(output);
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, json, StandardCharsets.UTF_8);
    }

    /**
     * 断言节点是 ID 字符串。
     *
     * <p>方法：{@code assertText}</p>
     *
     * <p>执行链路（共 2 步）：</p>
     * 1. 验证 JSON 节点为字符串而非数值；
     * 2. 验证字符串值保持 64 位 ID 的完整十进制文本。</p>
     *
     * @param value   被验证的 JSON 节点
     * @param message 失败信息
     */
    private void assertText(JsonNode value, String message) {
        assertTrue(value.isTextual(), message);
        assertTrue(Long.toString(CONTRACT_ID).equals(value.asText()), message);
    }

    /**
     * 专用无数据库 MVC 测试应用。
     *
     * <p>禁止使用组件扫描；所有装配类均通过 {@link Import} 明确列出，数据库自动配置被逐项排除。</p>
     */
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
            OpenApiContractConfig.class,
            AgentConfiguration.class,
            AiCapabilitiesController.class,
            SecurityConfig.class,
            GlobalExceptionHandler.class,
            SecurityExceptionHandler.class,
            AuthController.class,
            DepartmentController.class,
            UserController.class,
            RoleController.class,
            SystemConfigController.class,
            FileController.class,
            SiteController.class,
            WarehouseController.class,
            WarehouseQueryController.class,
            ContractCollaborators.class
    })
    static class ContractApplication {
    }

    /**
     * 为 Controller 提供不访问数据库的协作者替身。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class ContractCollaborators {

        @Bean
        AuthService authService() {
            return mock(AuthService.class);
        }

        @Bean
        UserService userService() {
            return mock(UserService.class);
        }

        @Bean
        DepartmentService departmentService() {
            return mock(DepartmentService.class);
        }

        @Bean
        RoleService roleService() {
            return mock(RoleService.class);
        }

        @Bean
        SystemConfigService systemConfigService() {
            return mock(SystemConfigService.class);
        }

        @Bean
        FileStorageService fileStorageService() {
            return mock(FileStorageService.class);
        }

        @Bean
        FileQueryApi fileQueryApi() {
            return mock(FileQueryApi.class);
        }

        @Bean
        SiteService siteService() {
            return mock(SiteService.class);
        }

        @Bean
        WarehouseService warehouseService() {
            return mock(WarehouseService.class);
        }

        @Bean
        IamActorApi iamActorApi() {
            return mock(IamActorApi.class);
        }
    }
}
