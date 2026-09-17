package com.internaladmin.app;

import com.internaladmin.module.agent.config.AiConfigurationValidator;
import com.internaladmin.module.knowledge.service.KnowledgeService;
import com.internaladmin.module.knowledge.service.KnowledgeContentPackRegistry;
import com.internaladmin.module.knowledge.api.KnowledgeQueryApi;
import org.junit.jupiter.api.Assumptions;
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

    @Autowired(required = false)
    private DeepSeekChatModel deepSeekChatModel;

    @Autowired
    private KnowledgeService knowledgeService;

    @Autowired
    private KnowledgeContentPackRegistry knowledgeContentPackRegistry;

    @Test
    void deepSeekOrdinaryStream() {
        Assumptions.assumeTrue(deepSeekChatModel != null,
                "DeepSeek chat model is unavailable; skip the optional external stream check");
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
        long expectedChunks = knowledgeContentPackRegistry.chunks().size();
        long expectedVersions = knowledgeContentPackRegistry.chunks().stream()
                .map(chunk -> chunk.documentCode() + "\u0000" + chunk.versionCode())
                .distinct().count();
        assertThat(first.chunksCreated() + first.chunksSkipped()).isEqualTo(expectedChunks);
        assertThat(repeat.chunksCreated()).isZero();
        assertThat(repeat.skippedVersions()).isEqualTo(expectedVersions);

        assertCitation("错误的库存流水可以直接改掉吗", "warehouse-rules", "v2");
        assertCitation("业务编码是不是数据库内部编号", "item-codes", "v2");
        assertCitation("查询位置时需要提交数据库ID吗", "warehouse-codes", "v2");
        assertCitation("助手能不能直接帮忙补货", "low-stock-policy", "v1");
        assertThat(knowledgeService.query("今天午餐吃什么", 1).status())
                .isEqualTo(KnowledgeQueryApi.Status.NO_EVIDENCE);
        assertThat(AiConfigurationValidator.EMBEDDING_DIMENSIONS).isEqualTo(1024);
    }

    private void assertCitation(String query, String documentCode, String versionCode) {
        KnowledgeQueryApi.Result result = knowledgeService.query(query, 1);
        assertThat(result.status()).isEqualTo(KnowledgeQueryApi.Status.FOUND);
        assertThat(result.citations()).anyMatch(citation -> documentCode.equals(citation.documentCode())
                && versionCode.equals(citation.versionCode())
                && citation.synthetic());
    }
}
