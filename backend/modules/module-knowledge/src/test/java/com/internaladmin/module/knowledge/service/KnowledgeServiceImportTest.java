package com.internaladmin.module.knowledge.service;

import com.internaladmin.module.knowledge.api.AiProperties;
import com.internaladmin.module.knowledge.api.KnowledgeRetrievalEmbeddingClient;
import com.internaladmin.module.knowledge.api.KnowledgeRetrievalEmbeddingClient.RetrievalEmbedding;
import com.internaladmin.module.knowledge.api.KnowledgeRetrievalEmbeddingClient.SparseEntry;
import com.internaladmin.module.knowledge.api.KnowledgeContentPack;
import com.internaladmin.module.knowledge.mapper.KnowledgeMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.core.io.ByteArrayResource;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

class KnowledgeServiceImportTest {

    @Test
    void importSuccessEmitsStartedAndCompletedLifecycleEvents() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeRetrievalEmbeddingClient embedding = mock(KnowledgeRetrievalEmbeddingClient.class);
        when(embedding.embedDocuments(anyList())).thenAnswer(invocation -> {
            List<String> batch = invocation.getArgument(0);
            return batch.stream().map(ignored -> embedding()).toList();
        });
        KnowledgeService service = service(mapper, embedding);
        ListAppender<ILoggingEvent> appender = attachImportAppender();
        try {
            service.importSyntheticSamples();
            List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(messages.stream().filter(value -> value.contains("knowledge_import stage=started")).count())
                    .isEqualTo(1);
            assertThat(messages.stream().filter(value -> value.contains("knowledge_import stage=completed")).count())
                    .isEqualTo(1);
            assertThat(messages.stream().filter(value -> value.contains("knowledge_import stage=failed")).count())
                    .isZero();
            assertThat(messages).noneMatch(value -> value.contains("warehouse-rules-v1") || value.contains("pack.md"));
        } finally {
            detachImportAppender(appender);
        }
    }

    @Test
    void importFailureEmitsSanitizedFailedLifecycleEvent() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeRetrievalEmbeddingClient embedding = mock(KnowledgeRetrievalEmbeddingClient.class);
        when(embedding.embedDocuments(anyList())).thenThrow(new IllegalStateException("AI_EMBEDDING_UNAVAILABLE: provider body"));
        KnowledgeService service = service(mapper, embedding);
        ListAppender<ILoggingEvent> appender = attachImportAppender();
        try {
            assertThatThrownBy(service::importSyntheticSamples).hasMessageContaining("AI_EMBEDDING_UNAVAILABLE");
            List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(messages.stream().filter(value -> value.contains("knowledge_import stage=started")).count())
                    .isEqualTo(1);
            assertThat(messages.stream().filter(value -> value.contains("knowledge_import stage=completed")).count())
                    .isZero();
            assertThat(messages.stream().filter(value -> value.contains("knowledge_import stage=failed")).count())
                    .isEqualTo(1);
            assertThat(messages).anyMatch(value -> value.contains("knowledge_import stage=failed")
                    && value.contains("errorCode=AI_EMBEDDING_UNAVAILABLE"));
            assertThat(messages).noneMatch(value -> value.contains("provider body") || value.contains("pack.md"));
        } finally {
            detachImportAppender(appender);
        }
    }

    @Test
    void parseFailureEmitsExactlyOneStartedAndFailedLifecycleEvent() {
        byte[] empty = new byte[0];
        KnowledgeContentPack pack = new KnowledgeContentPack() {
            @Override public String packId() { return "parse-failure-pack"; }
            @Override public String packVersion() { return "v1"; }
            @Override public String compatibilityVersion() { return KnowledgeContentPackRegistry.COMPATIBILITY_VERSION; }
            @Override public List<Document> documents() {
                return List.of(new Document("parse-failure", "v1", "Parse failure", "ACTIVE", 1,
                        new ByteArrayResource(empty), sha256(empty)));
            }
        };
        KnowledgeService service = service(mock(KnowledgeMapper.class), mock(KnowledgeRetrievalEmbeddingClient.class),
                new KnowledgeContentPackRegistry(List.of(pack)));
        ListAppender<ILoggingEvent> appender = attachImportAppender();
        try {
            assertThatThrownBy(service::importSyntheticSamples).hasMessageContaining("KNOWLEDGE_DRAFT_EMPTY");
            List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(messages.stream().filter(value -> value.contains("knowledge_import stage=started")).count())
                    .isEqualTo(1);
            assertThat(messages.stream().filter(value -> value.contains("knowledge_import stage=completed")).count())
                    .isZero();
            assertThat(messages.stream().filter(value -> value.contains("knowledge_import stage=failed")).count())
                    .isEqualTo(1);
            assertThat(messages).anyMatch(value -> value.contains("errorCode=KNOWLEDGE_DRAFT_EMPTY"));
        } finally {
            detachImportAppender(appender);
        }
    }

    private static ListAppender<ILoggingEvent> attachImportAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(KnowledgeService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachImportAppender(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(KnowledgeService.class)).detachAppender(appender);
        appender.stop();
    }

    @Test
    void fixedCatalogIsLargeEnoughToExerciseMultipleProviderBatches() {
        assertThat(SyntheticKnowledgeCatalog.load()).hasSizeGreaterThan(20);
    }

    @Test
    void providerFailureInSecondBatchLeavesPersistenceUntouched() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeRetrievalEmbeddingClient embedding = mock(KnowledgeRetrievalEmbeddingClient.class);
        when(embedding.embedDocuments(anyList())).thenAnswer(invocation -> {
            List<String> batch = invocation.getArgument(0);
            if (batch.size() < 20) {
                throw new IllegalStateException("AI_EMBEDDING_UNAVAILABLE: second batch failed");
            }
            return batch.stream().map(ignored -> embedding()).toList();
        });
        KnowledgeService service = service(mapper, embedding);

        assertThatThrownBy(service::importSyntheticSamples)
                .hasMessageContaining("AI_EMBEDDING_UNAVAILABLE");
        verify(mapper, never()).insertDocument(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(mapper, never()).insertVersion(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(mapper, never()).insertVector(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void successfulImportUsesOnlyBoundedEmbeddingBatches() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeRetrievalEmbeddingClient embedding = mock(KnowledgeRetrievalEmbeddingClient.class);
        when(embedding.embedDocuments(anyList())).thenAnswer(invocation -> {
            List<String> batch = invocation.getArgument(0);
            return batch.stream().map(ignored -> embedding()).toList();
        });
        KnowledgeService service = service(mapper, embedding);

        KnowledgeService.ImportSummary summary = service.importSyntheticSamples();

        assertThat(summary.chunksCreated()).isGreaterThan(20);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> batches = ArgumentCaptor.forClass((Class) List.class);
        verify(embedding, atLeastOnce()).embedDocuments(batches.capture());
        assertThat(batches.getAllValues()).allSatisfy(batch -> assertThat(batch).hasSizeLessThanOrEqualTo(20));
    }

    @Test
    void freshImportCountsSharedDocumentOnceAcrossItsVersions() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        Map<String, String> documentIds = new HashMap<>();
        when(mapper.findDocumentId(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> documentIds.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
            documentIds.put(invocation.getArgument(1), invocation.getArgument(0));
            return null;
        }).when(mapper).insertDocument(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        KnowledgeRetrievalEmbeddingClient embedding = mock(KnowledgeRetrievalEmbeddingClient.class);
        when(embedding.embedDocuments(anyList())).thenAnswer(invocation -> {
            List<String> batch = invocation.getArgument(0);
            return batch.stream().map(ignored -> embedding()).toList();
        });

        KnowledgeService.ImportSummary summary = service(mapper, embedding).importSyntheticSamples();

        assertThat(summary.documentsCreated()).isEqualTo(4);
        assertThat(summary.documentsSkipped()).isZero();
    }

    @Test
    void incompatibleExistingVersionIsRejectedBeforeEmbedding() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        when(mapper.findDocumentId("warehouse-rules")).thenReturn("document-1");
        when(mapper.findVersion("document-1", "v0"))
                .thenReturn(new KnowledgeMapper.VersionRow("version-1", "wrong", "qwen3.7-text-embedding", 1024));
        KnowledgeRetrievalEmbeddingClient embedding = mock(KnowledgeRetrievalEmbeddingClient.class);
        KnowledgeService service = service(mapper, embedding);

        assertThatThrownBy(service::importSyntheticSamples)
                .hasMessageContaining("AI_KNOWLEDGE_IMPORT_CONFLICT");
        verify(embedding, never()).embedDocuments(anyList());
    }

    @Test
    void legacyDocumentProfileCannotMasqueradeAsDenseSparseVersion() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        when(mapper.findDocumentId("warehouse-rules")).thenReturn("document-1");
        when(mapper.findVersion("document-1", "v2"))
                .thenReturn(new KnowledgeMapper.VersionRow("version-2", "legacy-hash", "dashscope-document-v1", 1024));
        KnowledgeRetrievalEmbeddingClient embedding = mock(KnowledgeRetrievalEmbeddingClient.class);

        assertThatThrownBy(() -> service(mapper, embedding).importSyntheticSamples())
                .hasMessageContaining("AI_KNOWLEDGE_IMPORT_CONFLICT");
        verify(embedding, never()).embedDocuments(anyList());
    }

    @Test
    void lowStockV1LegacyProfileIsRejectedBeforeEmbedding() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        when(mapper.findDocumentId("low-stock-policy")).thenReturn("document-low-stock");
        String legacyHash = legacyHash("low-stock-policy", "v1");
        when(mapper.findVersion("document-low-stock", "v1"))
                .thenReturn(new KnowledgeMapper.VersionRow("version-low-stock", legacyHash,
                        "qwen3.7-text-embedding", 1024));
        KnowledgeRetrievalEmbeddingClient embedding = mock(KnowledgeRetrievalEmbeddingClient.class);

        assertThatThrownBy(() -> service(mapper, embedding).importSyntheticSamples())
                .hasMessageContaining("AI_KNOWLEDGE_IMPORT_CONFLICT");
        verify(embedding, never()).embedDocuments(anyList());
    }

    private static String legacyHash(String documentCode, String versionCode) {
        String content = SyntheticKnowledgeCatalog.load().stream()
                .filter(chunk -> documentCode.equals(chunk.documentCode())
                        && versionCode.equals(chunk.versionCode()))
                .map(SyntheticKnowledgeCatalog.Chunk::content)
                .reduce("", String::concat);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte current : digest) {
                result.append(String.format("%02x", current));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static KnowledgeService service(KnowledgeMapper mapper,
                                             KnowledgeRetrievalEmbeddingClient embedding) {
        return service(mapper, embedding,
                new KnowledgeContentPackRegistry(List.of(SyntheticKnowledgeCatalog.pack())));
    }

    private static KnowledgeService service(KnowledgeMapper mapper,
                                             KnowledgeRetrievalEmbeddingClient embedding,
                                             KnowledgeContentPackRegistry registry) {
        AiProperties properties = new AiProperties();
        properties.getEmbedding().getQwen().setDimensions(1024);
        return new KnowledgeService(properties, embedding, mapper,
                new NoopTransactionManager(),
                registry);
    }

    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte current : digest) result.append(String.format("%02x", current));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static RetrievalEmbedding embedding() {
        return new RetrievalEmbedding(new float[1024], List.of(new SparseEntry(1, 1f)));
    }

    private static final class NoopTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
            return new DefaultTransactionStatus(null, null, false, false, false, false, false, null);
        }

        @Override
        public void commit(TransactionStatus status) throws TransactionException {
        }

        @Override
        public void rollback(TransactionStatus status) throws TransactionException {
        }
    }
}
