package com.internaladmin.module.agent.store;

import com.internaladmin.platform.kernel.error.BusinessException;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract evidence for server-generated Conversation IDs, ownership and stable History paging. */
class AgentStoreConversationContractTest {

    @TempDir
    Path tempDir;

    @Test
    void createsServerIdAndPagesOnlyOwnedConversationsByLastActivity() throws Exception {
        AgentStore store = store("conversation-page");
        AgentStore.ConversationRow first = store.createConversation(7L);
        AgentStore.ConversationRow second = store.createConversation(7L);
        store.createConversation(8L);

        assertNotNull(first.conversationId());
        assertFalse(first.conversationId().isBlank());
        assertNotEquals(first.conversationId(), second.conversationId());

        AgentStore.StartRun run = store.startRun(first.conversationId(), "contract-page-run", "库存", 7L);
        store.appendAssistant(first.conversationId(), run.runId(), "assistant-message", "库存结果", "COMPLETE");
        assertEquals(true, store.complete(run.runId()));

        AgentStore.ConversationPage page = store.pageConversations(7L, 1, 100);
        assertEquals(2, page.total());
        assertEquals(2, page.records().size());
        assertEquals(first.conversationId(), page.records().getFirst().conversationId(),
                "最近有消息的 Conversation 必须排在前面");
        assertThrows(BusinessException.class, () -> store.pageConversations(7L, 1, 101));
    }

    @Test
    void historyIsStableAndCannotCrossOwnerOrLazilyCreateConversation() throws Exception {
        AgentStore store = store("conversation-history");
        String conversationId = store.createConversation(7L).conversationId();
        AgentStore.MessagePage emptyHistory = store.pageMessages(conversationId, 7L, 1, 20);
        assertEquals(0, emptyHistory.total());
        assertEquals(0, emptyHistory.records().size());
        appendCompletedRun(store, conversationId, "contract-history-run-1", "第一条", "assistant-message-1", "第二条");
        appendCompletedRun(store, conversationId, "contract-history-run-2", "第三条", "assistant-message-2", "第四条");
        appendCompletedRun(store, conversationId, "contract-history-run-3", "第五条", "assistant-message-3", "第六条");

        AgentStore.MessagePage history = store.pageMessages(conversationId, 7L, 1, 2);
        assertEquals(6, history.total());
        assertEquals(2, history.records().size());
        assertEquals("第五条", history.records().get(0).content());
        assertEquals("第六条", history.records().get(1).content());
        assertEquals(1L, history.page());
        assertEquals(2L, history.size());

        AgentStore.MessagePage middle = store.pageMessages(conversationId, 7L, 2, 2);
        assertEquals("第三条", middle.records().get(0).content());
        assertEquals("第四条", middle.records().get(1).content());

        AgentStore.MessagePage oldest = store.pageMessages(conversationId, 7L, 3, 2);
        assertEquals("第一条", oldest.records().get(0).content());
        assertEquals("第二条", oldest.records().get(1).content());

        assertEquals("USER", oldest.records().get(0).role());
        assertEquals("ASSISTANT", oldest.records().get(1).role());
        assertEquals(3L, oldest.page());
        assertEquals(2L, oldest.size());

        assertThrows(BusinessException.class, () -> store.pageMessages(conversationId, 8L, 1, 20));
        assertThrows(BusinessException.class, () -> store.startRun("missing-conversation", "unknown", "不会懒创建", 7L));
    }

    @Test
    void historicalRowsAreBackfilledByMigrationBeforeTheNotNullContractIsUsed() throws Exception {
        JdbcTemplate jdbc = database("conversation-legacy-upgrade");
        AgentStore store = new AgentStore(jdbc);

        AgentStore.ConversationPage conversations = store.pageConversations(99L, 1, 20);
        assertEquals(1, conversations.total());
        assertNotNull(conversations.records().getFirst().updatedAt());

        AgentStore.MessagePage messages = store.pageMessages("legacy-conversation", 99L, 1, 20);
        assertEquals(1, messages.total());
        assertEquals("历史消息", messages.records().getFirst().content());

        String conversationSchema = jdbc.queryForObject(
                "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'ai_conversation'",
                String.class);
        String messageSchema = jdbc.queryForObject(
                "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'ai_message'",
                String.class);
        assertTrue(isNotNull(jdbc, "ai_conversation", "updated_at"), conversationSchema);
        assertTrue(isNotNull(jdbc, "ai_message", "sequence_no"), messageSchema);
    }

    private void appendCompletedRun(AgentStore store, String conversationId, String requestId,
                                    String userMessage, String assistantMessageId,
                                    String assistantMessage) {
        AgentStore.StartRun run = store.startRun(conversationId, requestId, userMessage, 7L);
        store.appendAssistant(conversationId, run.runId(), assistantMessageId, assistantMessage, "COMPLETE");
        assertEquals(true, store.complete(run.runId()));
    }

    private AgentStore store(String name) throws Exception {
        return new AgentStore(database(name));
    }

    private JdbcTemplate database(String name) throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:file:" + tempDir.resolve(name + ".db")
                + "?cache=shared&busy_timeout=5000");
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:/db/changelog/agent-concurrency-master.xml");
        liquibase.setShouldRun(true);
        liquibase.afterPropertiesSet();
        return new JdbcTemplate(dataSource);
    }

    private boolean isNotNull(JdbcTemplate jdbc, String tableName, String columnName) {
        String pragma = switch (tableName) {
            case "ai_conversation", "ai_message" -> "'" + tableName + "'";
            default -> throw new IllegalArgumentException("unexpected test table: " + tableName);
        };
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pragma_table_info(" + pragma + ") WHERE name = ? AND \"notnull\" = 1",
                Integer.class, columnName);
        return count != null && count == 1;
    }
}
