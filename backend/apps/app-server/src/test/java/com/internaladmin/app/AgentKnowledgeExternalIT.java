package com.internaladmin.app;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.internaladmin.module.agent.api.AgentRunContext;
import com.internaladmin.module.agent.knowledge.KnowledgeToolProvider;
import com.internaladmin.module.agent.service.AgentConversationService;
import com.internaladmin.module.agent.service.AgentExecutionContext;
import com.internaladmin.module.agent.store.AgentStore;
import com.internaladmin.module.agent.warehouse.WarehouseInventoryToolProvider;
import com.internaladmin.module.agent.warehouse.WarehouseSearchSynchronizer;
import com.internaladmin.module.knowledge.api.KnowledgeQueryApi;
import com.internaladmin.module.agent.model.dto.MessageDTO;
import com.internaladmin.module.agent.model.dto.MessagePageDTO;
import com.internaladmin.module.iam.mapper.UserMapper;
import com.internaladmin.module.iam.model.entity.UserDO;
import com.internaladmin.module.warehouse.model.dto.InventoryLineDTO;
import com.internaladmin.module.warehouse.model.dto.InventoryRequestDTO;
import com.internaladmin.module.warehouse.model.dto.ItemCreateDTO;
import com.internaladmin.module.warehouse.model.dto.LocationCreateDTO;
import com.internaladmin.module.warehouse.model.dto.WarehouseCreateDTO;
import com.internaladmin.module.warehouse.service.WarehouseService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import liquibase.integration.spring.SpringLiquibase;

import javax.sql.DataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Explicit 04B production-chain gate. The *IT name and the system property are
 * both required, so ordinary Surefire runs never initialize the external gate.
 * The business Agent store is an owned temporary SQLite file; Knowledge uses
 * only the local project PostgreSQL configured by the operator environment.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"app.ai.enabled=true", "spring.main.allow-bean-definition-overriding=true"})
@Import(AgentKnowledgeExternalIT.GateAgentLiquibaseConfiguration.class)
@EnabledIfSystemProperty(named = "RUN_AGENT_KNOWLEDGE_GATE", matches = "true")
class AgentKnowledgeExternalIT {

    private static final String KNOWLEDGE_URL = "jdbc:postgresql://127.0.0.1:15432/internal_admin_knowledge";
    private static final Path BUSINESS_DB = Path.of(System.getProperty("java.io.tmpdir"),
            "agent-knowledge-gate-" + UUID.randomUUID() + ".db");
    private AgentRunContext actor;

    @Autowired
    private AgentConversationService service;
    @Autowired
    private AgentStore store;
    @Autowired
    private KnowledgeQueryApi knowledge;
    @Autowired
    private KnowledgeToolProvider knowledgeToolProvider;
    @Autowired
    private WarehouseInventoryToolProvider warehouseInventoryToolProvider;
    @Autowired
    private WarehouseService warehouseService;
    @Autowired
    private UserMapper userMapper;
    @Autowired(required = false)
    private WarehouseSearchSynchronizer warehouseSearchSynchronizer;
    @Autowired
    private ChatClient chatClient;

    @DynamicPropertySource
    static void gateProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + BUSINESS_DB + "?foreign_keys=on");
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.datasource.username", () -> "");
        registry.add("spring.datasource.password", () -> "");
        registry.add("app.ai.knowledge.datasource.url", () -> env("SPRING_DATASOURCE_URL"));
        registry.add("app.ai.knowledge.datasource.username", () -> env("SPRING_DATASOURCE_USERNAME"));
        registry.add("app.ai.knowledge.datasource.password", () -> env("SPRING_DATASOURCE_PASSWORD"));
    }

    @AfterAll
    static void removeOwnedBusinessDatabase() throws IOException {
        Files.deleteIfExists(BUSINESS_DB);
        Files.deleteIfExists(Path.of(BUSINESS_DB + "-journal"));
        Files.deleteIfExists(Path.of(BUSINESS_DB + "-wal"));
        Files.deleteIfExists(Path.of(BUSINESS_DB + "-shm"));
    }

    @Test
    void mixedWarehouseAndKnowledgeRunsUseOneInitialToolBatchAndPreserveOrder() {
        Assumptions.assumeTrue(KNOWLEDGE_URL.equals(env("SPRING_DATASOURCE_URL")),
                "知识库目标必须是本项目本机 127.0.0.1:15432/internal_admin_knowledge");
        Assumptions.assumeTrue(!env("SPRING_DATASOURCE_USERNAME").isBlank(), "知识库用户名未配置");
        Assumptions.assumeTrue(!env("SPRING_DATASOURCE_PASSWORD").isBlank(), "知识库密码未配置");
        assertThat(service).isNotNull();
        assertThat(store).isNotNull();
        assertThat(knowledge).isNotNull();
        assertThat(knowledgeToolProvider).isNotNull();
        assertThat(warehouseInventoryToolProvider).isNotNull();
        assertThat(chatClient).isNotNull();

        prepareA100Fixture();
        RunEvidence warehouseFirst = execute("查一下A100现在还有多少，低于制度阈值后应该怎么处理？");
        assertMixedRun(warehouseFirst, List.of("warehouse_current_stock", "knowledge_search"));
        RunEvidence knowledgeFirst = execute("按制度A100低库存该怎么办，再看看现在还有多少？");
        assertMixedRun(knowledgeFirst, List.of("knowledge_search", "warehouse_current_stock"));
    }

    private void assertMixedRun(RunEvidence evidence, List<String> expectedToolOrder) {
        assertThat(evidence.toolOrder()).containsExactlyElementsOf(expectedToolOrder);
        assertThat(evidence.knowledgeCalls()).hasSize(1);
        tools.jackson.databind.JsonNode knowledgeArguments = tools.jackson.databind.json.JsonMapper.builder()
                .build().readTree(evidence.knowledgeCalls().getFirst().arguments());
        assertThat(knowledgeArguments.get("queryText").asText())
                .isEqualTo(normalizeQuestion(evidence.question()));
        assertThat(knowledgeArguments.get("operation").asText()).isEqualTo("SEARCH");
        assertThat(evidence.knowledgeCalls().getFirst().success()).isTrue();
        assertThat(evidence.knowledgeCalls().getFirst().errorCode()).isNull();
        assertThat(evidence.warehouseCalls()).hasSize(1);
        assertThat(evidence.warehouseCalls().getFirst().success()).isTrue();
        assertThat(evidence.warehouseCalls().getFirst().errorCode()).isNull();
        List<String> expectedEvents = expectedToolOrder.getFirst().equals(KnowledgeToolProvider.TOOL_NAME)
                ? List.of("run.started", "citation.added", "card.replace", "card.replace", "message.completed", "run.completed")
                : List.of("run.started", "card.replace", "citation.added", "card.replace", "message.completed", "run.completed");
        assertThat(evidence.events().stream().map(AgentConversationService.StreamEvent::name).toList())
                .containsExactlyElementsOf(expectedEvents);
        assertThat(evidence.events().stream().map(AgentConversationService.StreamEvent::data))
                .anyMatch(data -> data.contains("\"documentCode\":\"low-stock-policy\"")
                        && data.contains("\"versionCode\":\"v1\""));
        assertThat(evidence.events().stream().map(AgentConversationService.StreamEvent::data))
                .noneMatch(data -> data.contains("itemId") || data.contains("locationId")
                        || data.contains("versionId") || data.contains("score") || data.contains("vector"));
        assertThat(evidence.events().stream().map(AgentConversationService.StreamEvent::data).toList())
                .anyMatch(data -> data.contains("A100"));
        assertThat(evidence.assistant().knowledgeAnswer()).isNotNull();
        assertThat(evidence.assistant().knowledgeAnswer().outcome()).isEqualTo("ANSWERED");
        assertThat(evidence.assistant().knowledgeAnswer().citations()).hasSize(1);
        assertThat(evidence.assistant().knowledgeAnswer().citations().getFirst().documentCode())
                .isEqualTo("low-stock-policy");
        assertThat(evidence.assistant().knowledgeAnswer().citations().getFirst().versionCode()).isEqualTo("v1");
        assertThat(evidence.assistant().state()).isEqualTo(AgentStore.COMPLETE);
        assertThat(evidence.historyAssistant().messageId()).isEqualTo(evidence.assistant().messageId());
        assertThat(evidence.historyAssistant().knowledgeAnswer()).isEqualTo(evidence.assistant().knowledgeAnswer());
        assertThat(evidence.taskStatus()).isEqualTo(AgentStore.TASK_COMPLETED);
        assertThat(evidence.taskRevision()).isGreaterThanOrEqualTo(1L);
        assertThat(evidence.lateWarehouseDenied()).isTrue();
        assertThat(evidence.warehouseCallsAfterLateAttempt()).isEqualTo(1);
    }

    private void prepareA100Fixture() {
        if (warehouseSearchSynchronizer != null) {
            // The fixture is created through the normal WarehouseService write path.  Stop the
            // adapter-owned derived-index worker first so this gate measures only the authorised
            // Knowledge query embedding, not an unrelated warehouse index update.
            warehouseSearchSynchronizer.stop();
        }
        UserDO admin = userMapper.selectOne(new LambdaQueryWrapper<UserDO>().eq(UserDO::getUsername, "admin"));
        assertThat(admin).as("临时业务SQLite应由正常入口初始化管理员").isNotNull();
        actor = new AgentRunContext(admin.getId(), admin.getDepartmentId(), true, List.of("warehouse:read"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin.getId(), "04c-gate"));
        try {
            ItemCreateDTO item = new ItemCreateDTO();
            item.setCode("A100");
            item.setName("A100合成物品");
            item.setBaseUnit("件");
            Long itemId = warehouseService.createItem(item);

            WarehouseCreateDTO warehouse = new WarehouseCreateDTO();
            warehouse.setCode("WH-04C-A100");
            warehouse.setName("04C合成仓库");
            warehouse.setDepartmentId(admin.getDepartmentId());
            Long warehouseId = warehouseService.createWarehouse(warehouse);

            LocationCreateDTO location = new LocationCreateDTO();
            location.setWarehouseId(warehouseId);
            location.setCode("A100-01");
            location.setName("A100合成库位");
            Long locationId = warehouseService.createLocation(location);

            InventoryLineDTO line = new InventoryLineDTO();
            line.setItemId(itemId);
            line.setLocationId(locationId);
            line.setQuantity("12");
            InventoryRequestDTO inbound = new InventoryRequestDTO();
            inbound.setRequestId("04C-A100-" + UUID.randomUUID());
            inbound.setLines(List.of(line));
            warehouseService.inbound(inbound);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private RunEvidence execute(String question) {
        var conversation = service.createConversation(actor.userId());
        AgentStore.StartRun run = service.start(conversation.conversationId(), UUID.randomUUID().toString(),
                question, actor);
        List<AgentConversationService.StreamEvent> events = new ArrayList<>();
        Set<String> cardKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();
        AtomicLong sequence = new AtomicLong();
        AgentExecutionContext execution = new AgentExecutionContext(actor, run.runId(),
                run.effectiveUserMessage() == null ? question : run.effectiveUserMessage(), card -> {
            AgentConversationService.CardIdentity identity = service.inspectCard(card);
            if (!cardKeys.add(identity.key())) return;
            AgentConversationService.PreparedCard prepared = service.recordCard(run, identity, actor.scopeFingerprint());
            if ("knowledge-answer".equals(identity.cardType())) {
                String citation = service.knowledgeCitationPayload(identity);
                if (citation != null) {
                    events.add(AgentConversationService.envelopedEvent("citation.added", run, sequence,
                            run.assistantMessageId(), citation));
                }
            }
            events.add(AgentConversationService.envelopedEvent("card.replace", run, sequence,
                    run.assistantMessageId(), prepared.json()));
        }, new AtomicBoolean(), sequence, run.assistantMessageId(), run.taskId(), run.taskRevision(),
                new AtomicBoolean());
        service.execute(run, execution, events::add, new AtomicBoolean());
        MessagePageDTO history = service.pageMessages(conversation.conversationId(), actor.userId(),
                actor.scopeFingerprint(), 1, 50);
        MessageDTO assistant = history.records().stream()
                .filter(message -> "ASSISTANT".equals(message.role()))
                .findFirst().orElseThrow(() -> new AssertionError("助手History缺失"));
        List<AgentExecutionContext.ToolOutcome> knowledgeCalls = execution.toolOutcomes().stream()
                .filter(outcome -> KnowledgeToolProvider.TOOL_NAME.equals(outcome.toolName()))
                .toList();
        List<AgentExecutionContext.ToolOutcome> warehouseCalls = execution.toolOutcomes().stream()
                .filter(outcome -> outcome.toolName() != null && outcome.toolName().startsWith("warehouse_"))
                .toList();
        boolean lateDenied = false;
        try {
            var stockCallback = java.util.Arrays.stream(warehouseInventoryToolProvider.getToolCallbacks())
                    .filter(callback -> WarehouseInventoryToolProvider.CURRENT_STOCK_TOOL
                            .equals(callback.getToolDefinition().name())).findFirst().orElseThrow();
            stockCallback.call("{\"itemMentions\":[\"A100\"],\"excludedItemMentions\":[],\"selectionPreference\":\"AUTO_IF_UNIQUE\",\"limit\":20}",
                    new org.springframework.ai.chat.model.ToolContext(java.util.Map.of("agent.execution", execution)));
        } catch (com.internaladmin.module.agent.api.AgentToolException denied) {
            lateDenied = "AI_BUSINESS_REJECTED".equals(denied.getErrorCode().getCode());
        }
        List<String> toolOrder = execution.toolOutcomes().stream().map(AgentExecutionContext.ToolOutcome::toolName).toList();
        MessageDTO historyAssistant = history.records().stream()
                .filter(message -> "ASSISTANT".equals(message.role()))
                .findFirst().orElseThrow(() -> new AssertionError("History助手缺失"));
        AgentStore.TaskRow task = store.task(run.taskId());
        long taskRevision = task == null ? -1L : task.revision();
        return new RunEvidence(question, events, assistant, historyAssistant, knowledgeCalls, warehouseCalls,
                toolOrder, task == null ? null : task.status(), taskRevision, lateDenied, warehouseCalls.size());
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value.trim();
    }

    private static String normalizeQuestion(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .trim().replaceAll("\\s+", " ");
    }

    private record RunEvidence(String question, List<AgentConversationService.StreamEvent> events, MessageDTO assistant,
                               MessageDTO historyAssistant,
                               List<AgentExecutionContext.ToolOutcome> knowledgeCalls,
                               List<AgentExecutionContext.ToolOutcome> warehouseCalls,
                               List<String> toolOrder, String taskStatus, long taskRevision,
                               boolean lateWarehouseDenied, int warehouseCallsAfterLateAttempt) {
    }

    /**
     * The production bootstrap changeSet uses addUniqueConstraint, which Liquibase
     * cannot validate for SQLite. This test-only SpringLiquibase entry keeps the
     * same production follow-up changeSets while bootstrapping the owned SQLite
     * tables with portable createTable/createIndex operations.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class GateAgentLiquibaseConfiguration {
        @Bean(name = "liquibase")
        SpringLiquibase businessLiquibase(@Qualifier("dataSource") DataSource dataSource) {
            SpringLiquibase liquibase = new SpringLiquibase();
            liquibase.setDataSource(dataSource);
            liquibase.setChangeLog("classpath:db/changelog-master.xml");
            liquibase.setShouldRun(true);
            return liquibase;
        }

        @Bean
        static BeanDefinitionRegistryPostProcessor replaceAgentLiquibase() {
            return registry -> {
                if (registry.containsBeanDefinition("agentLiquibase")) {
                    registry.removeBeanDefinition("agentLiquibase");
                }
                registry.registerBeanDefinition("agentLiquibase", new RootBeanDefinition(GateLiquibase.class));
            };
        }

        static final class GateLiquibase extends SpringLiquibase {
            @Autowired
            void configure(@Qualifier("dataSource") DataSource dataSource) {
                setDataSource(dataSource);
                setChangeLog("classpath:db/changelog/agent-gate-master.xml");
                setShouldRun(true);
            }
        }
    }
}
