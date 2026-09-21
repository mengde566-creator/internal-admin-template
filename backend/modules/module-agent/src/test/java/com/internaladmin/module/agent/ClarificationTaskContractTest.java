package com.internaladmin.module.agent;

import com.internaladmin.module.agent.model.dto.ClarificationTaskDTO;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Keeps the persisted FAILED_RETRYABLE contract shared with the frontend fixture. */
class ClarificationTaskContractTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void realDtoSerializationMatchesSharedFixture() throws Exception {
        var dto = new ClarificationTaskDTO("task-1", 4, "FAILED_RETRYABLE", "warehouse", "ITEM",
                "CURRENT_STOCK", "ITEM-6204", "深沟球轴承", "WH-01", "一号仓", java.util.List.of());
        var actual = JSON.readTree(JSON.writeValueAsString(dto));
        var fixture = JSON.readTree(Files.readString(projectRoot().resolve(
                "frontend/src/modules/agent/contracts/failed-clarification.json")));
        assertEquals(fixture, actual);
    }

    private static Path projectRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("frontend/src/modules/agent/contracts/failed-clarification.json"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("project root not found");
        return current;
    }
}
