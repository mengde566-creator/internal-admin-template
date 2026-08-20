package com.internaladmin.app;

import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.knowledge.controller.KnowledgeController;
import com.internaladmin.module.knowledge.service.KnowledgeService;
import com.internaladmin.platform.security.config.SecurityConfig;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import jakarta.annotation.Resource;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP method-security proof for the public knowledge query boundary. */
@ActiveProfiles("contract")
@SpringBootTest(
        classes = KnowledgeControllerPermissionTest.ContractApplication.class,
        properties = {
                "app.ai.enabled=true",
                "spring.liquibase.enabled=false",
                "springdoc.api-docs.enabled=false"
        })
@AutoConfigureMockMvc
class KnowledgeControllerPermissionTest {

    @Resource
    private MockMvc mockMvc;

    @Resource
    private KnowledgeService knowledgeService;

    @Test
    void authenticatedUserWithoutWarehouseReadGetsForbidden() throws Exception {
        mockMvc.perform(get("/api/ai/knowledge/search")
                        .param("query", "仓储")
                        .with(user("no-warehouse-read")))
                .andExpect(status().isForbidden());
    }

    @Test
    void warehouseReadUserCanQueryKnowledge() throws Exception {
        when(knowledgeService.search("仓储", 5)).thenReturn(List.of());

        mockMvc.perform(get("/api/ai/knowledge/search")
                        .param("query", "仓储")
                        .with(user("warehouse-reader").authorities(
                                new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                        PermissionCodes.WAREHOUSE_READ))))
                .andExpect(status().isOk());
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
    @Import({SecurityConfig.class, KnowledgeController.class})
    static class ContractApplication {

        @Bean
        KnowledgeService knowledgeService() {
            return mock(KnowledgeService.class);
        }
    }
}
