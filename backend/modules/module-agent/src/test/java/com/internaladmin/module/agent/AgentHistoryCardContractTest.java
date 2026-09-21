package com.internaladmin.module.agent;

import com.internaladmin.module.agent.api.AgentAdapter;
import com.internaladmin.module.agent.api.AgentAdapterDescriptor;
import com.internaladmin.module.agent.api.AgentAdapterRegistry;
import com.internaladmin.module.agent.model.dto.MessageDTO;
import com.internaladmin.module.agent.service.AgentConversationService;
import com.internaladmin.module.agent.store.AgentStore;
import com.internaladmin.module.knowledge.api.AiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentHistoryCardContractTest {
    private static final String WAREHOUSE_CARD = "{\"cardId\":\"stock-1\",\"revision\":0,\"cardType\":\"stock-summary\",\"resultCount\":1,\"truncated\":false,\"outcome\":\"ANSWERED\",\"queriedAt\":\"2026-09-20T00:00:00Z\",\"rows\":[]}";
    private static final String LEGACY_KNOWLEDGE_CARD = "{\"cardId\":\"knowledge-1\",\"revision\":0,\"cardType\":\"knowledge-answer\",\"outcome\":\"NO_EVIDENCE\",\"queriedAt\":\"2026-09-20T00:00:00Z\",\"resultCount\":0,\"truncated\":false,\"citations\":[]}";

    @Test
    void historyExposesAllValidatedCardsAndKeepsLegacyKnowledgeObjectCompatible() {
        AgentStore store = mock(AgentStore.class);
        when(store.pageMessages("conversation-1", 7L, 1, 50)).thenReturn(new AgentStore.MessagePage(List.of(
                new AgentStore.MessageRow("message-1", "run-1", "ASSISTANT", "COMPLETE", "库存结果", java.time.Instant.parse("2026-09-20T00:00:00Z"),
                        "[" + WAREHOUSE_CARD + "," + WAREHOUSE_CARD.replace("stock-1", "stock-2") + "]"),
                new AgentStore.MessageRow("message-2", "run-2", "ASSISTANT", "COMPLETE", "知识结果", java.time.Instant.parse("2026-09-20T00:00:00Z"),
                        LEGACY_KNOWLEDGE_CARD)), 2, 1, 50));

        AgentConversationService service = new AgentConversationService(store, mock(ChatClient.class), null,
                new AiProperties(), List.of(), new AgentAdapterRegistry(List.of(new TestCardAdapter())), null);

        List<MessageDTO> messages = service.pageMessages("conversation-1", 7L, 1, 50).records();

        assertEquals(2, messages.getFirst().cards().size());
        assertEquals("stock-summary", messages.getFirst().cards().getFirst().get("cardType"));
        assertEquals(1, messages.get(1).cards().size());
        assertNotNull(messages.get(1).knowledgeAnswer());
        assertEquals("knowledge-answer", messages.get(1).cards().getFirst().get("cardType"));
    }

    private static final class TestCardAdapter implements AgentAdapter {
        @Override
        public AgentAdapterDescriptor descriptor() {
            return new AgentAdapterDescriptor("test-history", List.of(), List.of(), "test-history",
                    List.of("stock-summary"), List.of(), Set.of(), false);
        }

        @Override
        public ToolCallback[] getToolCallbacks() {
            return new ToolCallback[0];
        }

        @Override
        public Optional<String> validateAndNormalizeCard(String cardType, String cardJson) {
            return "stock-summary".equals(cardType) ? Optional.of(cardJson) : Optional.empty();
        }
    }
}
