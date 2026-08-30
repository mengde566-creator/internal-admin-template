package com.internaladmin.app;

import com.internaladmin.module.agent.api.AgentRunContext;
import com.internaladmin.module.agent.knowledge.KnowledgeToolProvider;
import com.internaladmin.module.agent.service.AgentConversationService;
import com.internaladmin.module.agent.service.AgentExecutionContext;
import com.internaladmin.module.agent.store.AgentStore;
import com.internaladmin.module.agent.warehouse.WarehouseInventoryToolProvider;
import com.internaladmin.module.knowledge.api.KnowledgeQueryApi;
import com.internaladmin.module.agent.model.dto.MessageDTO;
import com.internaladmin.module.agent.model.dto.MessagePageDTO;
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
    private static final AgentRunContext ACTOR = new AgentRunContext(700001L, 700002L, true,
            List.of("warehouse:read"));

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
    void knowledgeAnswersUseTheRealToolStoreAndSseChain() {
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

        runNoEvidence("叉车轮胎气压设置多少");
        runFound("错误的库存流水可以直接改掉吗？", "warehouse-rules", "v2");
        runFound("业务编码是不是数据库内部编号？", "item-codes", "v2");
        runFound("查询位置时需要提交数据库ID吗？", "warehouse-codes", "v2");
    }

    private void runFound(String question, String documentCode, String versionCode) {
        RunEvidence evidence = execute(question);
        assertThat(evidence.knowledgeCalls()).hasSize(1);
        assertThat(evidence.knowledgeCalls().getFirst().arguments()).isEqualTo(normalizeQuestion(question));
        assertThat(evidence.knowledgeCalls().getFirst().success()).isTrue();
        assertThat(evidence.knowledgeCalls().getFirst().errorCode()).isNull();
        assertThat(evidence.events().stream().map(AgentConversationService.StreamEvent::name).toList())
                .containsExactly("run.started", "citation.added", "card.replace", "message.completed", "run.completed");
        assertThat(evidence.events().stream().map(AgentConversationService.StreamEvent::data))
                .anyMatch(data -> data.contains("\"documentCode\":\"" + documentCode + "\"")
                        && data.contains("\"versionCode\":\"" + versionCode + "\""));
        assertThat(evidence.events().stream().map(AgentConversationService.StreamEvent::data))
                .noneMatch(data -> data.contains("itemId") || data.contains("departmentId") || data.contains("userId"));
        assertThat(evidence.warehouseCalls()).isEmpty();
        assertThat(evidence.assistant().knowledgeAnswer()).isNotNull();
        assertThat(evidence.assistant().knowledgeAnswer().outcome()).isEqualTo("ANSWERED");
        assertThat(evidence.assistant().knowledgeAnswer().citations()).hasSize(1);
        assertThat(evidence.assistant().state()).isEqualTo(AgentStore.COMPLETE);
    }

    private void runNoEvidence(String question) {
        RunEvidence evidence = execute(question);
        assertThat(evidence.knowledgeCalls()).hasSize(1);
        assertThat(evidence.knowledgeCalls().getFirst().arguments()).isEqualTo(normalizeQuestion(question));
        assertThat(evidence.events().stream().map(AgentConversationService.StreamEvent::name).toList())
                .containsExactly("run.started", "card.replace", "message.completed", "run.completed");
        assertThat(evidence.events()).noneMatch(event -> "citation.added".equals(event.name()));
        assertThat(evidence.events().stream().map(AgentConversationService.StreamEvent::data))
                .anyMatch(data -> data.contains("没有找到可引用依据"));
        assertThat(evidence.warehouseCalls()).isEmpty();
        assertThat(evidence.assistant().knowledgeAnswer()).isNotNull();
        assertThat(evidence.assistant().knowledgeAnswer().outcome()).isEqualTo("NO_EVIDENCE");
        assertThat(evidence.assistant().knowledgeAnswer().citations()).isEmpty();
        assertThat(evidence.assistant().state()).isEqualTo(AgentStore.COMPLETE);
    }

    private RunEvidence execute(String question) {
        var conversation = service.createConversation(ACTOR.userId());
        AgentStore.StartRun run = service.start(conversation.conversationId(), UUID.randomUUID().toString(),
                question, ACTOR);
        List<AgentConversationService.StreamEvent> events = new ArrayList<>();
        Set<String> cardKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();
        AtomicLong sequence = new AtomicLong();
        AgentExecutionContext execution = new AgentExecutionContext(ACTOR, run.runId(),
                run.effectiveUserMessage() == null ? question : run.effectiveUserMessage(), card -> {
            AgentConversationService.CardIdentity identity = service.inspectCard(card);
            if (!cardKeys.add(identity.key())) return;
            AgentConversationService.PreparedCard prepared = service.recordCard(run, identity, ACTOR.scopeFingerprint());
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
        MessagePageDTO history = service.pageMessages(conversation.conversationId(), ACTOR.userId(),
                ACTOR.scopeFingerprint(), 1, 50);
        MessageDTO assistant = history.records().stream()
                .filter(message -> "ASSISTANT".equals(message.role()))
                .findFirst().orElseThrow(() -> new AssertionError("助手History缺失"));
        List<AgentExecutionContext.ToolOutcome> knowledgeCalls = execution.toolOutcomes().stream()
                .filter(outcome -> KnowledgeToolProvider.TOOL_NAME.equals(outcome.toolName()))
                .toList();
        List<AgentExecutionContext.ToolOutcome> warehouseCalls = execution.toolOutcomes().stream()
                .filter(outcome -> outcome.toolName() != null && outcome.toolName().startsWith("warehouse_"))
                .toList();
        return new RunEvidence(events, assistant, knowledgeCalls, warehouseCalls);
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value.trim();
    }

    private static String normalizeQuestion(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .trim().replaceAll("\\s+", " ");
    }

    private record RunEvidence(List<AgentConversationService.StreamEvent> events, MessageDTO assistant,
                               List<AgentExecutionContext.ToolOutcome> knowledgeCalls,
                               List<AgentExecutionContext.ToolOutcome> warehouseCalls) {
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
