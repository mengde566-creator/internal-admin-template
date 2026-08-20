package com.internaladmin.app;

import com.internaladmin.module.agent.config.AiConfigurationValidator;
import com.internaladmin.module.knowledge.service.KnowledgeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in Gate A proof. It is skipped unless the operator explicitly exports RUN_AI_GATE=true
 * together with the local, non-logged provider and PostgreSQL configuration.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "app.ai.enabled=true")
@EnabledIfEnvironmentVariable(named = "RUN_AI_GATE", matches = "true")
class AiGateAExternalTest {

    @Autowired
    private DeepSeekChatModel deepSeekChatModel;

    @Autowired
    private KnowledgeService knowledgeService;

    @Test
    void deepSeekOrdinaryStream() {
        List<ChatResponse> responses = Flux.from(deepSeekChatModel.stream(
                        new Prompt("Reply with one short word confirming the stream.")))
                .collectList()
                .block(Duration.ofSeconds(45));
        assertThat(responses).isNotNull().isNotEmpty();
        assertThat(responses.stream()
                .map(response -> response.getResult().getOutput().getText())
                .filter(text -> text != null && !text.isBlank())
                .count()).isPositive();
    }

    @Test
    void qwenKnowledgeLifecycle() {
        KnowledgeService.ImportSummary first = knowledgeService.importSyntheticSamples();
        KnowledgeService.ImportSummary repeat = knowledgeService.importSyntheticSamples();
        assertThat(first.chunksCreated() + first.skippedVersions()).isEqualTo(4);
        assertThat(repeat.chunksCreated()).isZero();
        assertThat(repeat.skippedVersions()).isEqualTo(4);

        List<KnowledgeService.KnowledgeSearchResult> results = knowledgeService.search("物品编码规则 库存出库可用余额", 20);
        assertThat(results).isNotEmpty();
        assertThat(results).allMatch(result -> !"v0".equals(result.versionCode()));
        assertThat(results).anyMatch(result -> "warehouse-rules".equals(result.documentCode())
                && "v1".equals(result.versionCode()));
        assertThat(results).anyMatch(result -> "item-codes".equals(result.documentCode())
                && "v1".equals(result.versionCode()));
        assertThat(AiConfigurationValidator.EMBEDDING_DIMENSIONS).isEqualTo(1024);
    }
}
