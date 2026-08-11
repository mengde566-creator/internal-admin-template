package com.internaladmin.app;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 运行时 OpenAPI 契约回归测试。
 *
 * <p>测试启动隔离 SQLite 数据源并执行 Liquibase，仅允许在项目负责人提供的外部验证环境运行；
 * 当前研发环境严格禁止执行此类测试。</p>
 */
@ActiveProfiles("contract")
@SpringBootTest(classes = Application.class, properties = {
        "spring.datasource.url=jdbc:sqlite:./data/test-openapi-contract.db?foreign_keys=on",
        "app.admin-initial-password=TestPass123"
})
@AutoConfigureMockMvc
class OpenApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("contract profile 公开运行时规范，创建响应和 ID 为字符串")
    void contractProfileExposesExpectedRuntimeSchema() throws Exception {
        String content = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode specification = objectMapper.readTree(content);

        assertTrue(specification.path("openapi").asText().startsWith("3."));
        assertTrue(specification.path("x-generated-by").asText().contains("springdoc-openapi 3.1.0"));
        assertFalse(specification.path("paths").path("/api/users").isMissingNode());
        assertFalse(specification.path("paths").path("/api/roles").isMissingNode());

        assertCreateResponseIdIsString(specification, "/api/users");
        assertCreateResponseIdIsString(specification, "/api/roles");
    }

    private void assertCreateResponseIdIsString(JsonNode specification, String path) {
        JsonNode responseSchema = specification.path("paths").path(path).path("post")
                .path("responses").path("200").path("content").path("application/json").path("schema");
        JsonNode response = dereference(specification, responseSchema);
        JsonNode data = dereference(specification, response.path("properties").path("data"));
        JsonNode id = dereference(specification, data.path("properties").path("id"));
        assertEquals("string", id.path("type").asText(), path + " 创建响应 data.id 必须是 string");
    }

    private JsonNode dereference(JsonNode specification, JsonNode schema) {
        JsonNode current = schema;
        while (current.has("$ref")) {
            String reference = current.path("$ref").asText();
            assertTrue(reference.startsWith("#/components/schemas/"), "只允许组件 schema 引用");
            current = specification.path("components").path("schemas")
                    .path(reference.substring("#/components/schemas/".length()));
        }
        if (current.has("anyOf")) {
            for (JsonNode candidate : current.path("anyOf")) {
                JsonNode resolvedCandidate = dereference(specification, candidate);
                if (!isNullSchema(resolvedCandidate)) {
                    return resolvedCandidate;
                }
            }
        }
        assertNotNull(current);
        return current;
    }

    private boolean isNullSchema(JsonNode schema) {
        return "null".equals(schema.path("type").asText())
                || (schema.path("type").isArray() && schema.path("type").size() == 1
                && "null".equals(schema.path("type").get(0).asText()));
    }
}
