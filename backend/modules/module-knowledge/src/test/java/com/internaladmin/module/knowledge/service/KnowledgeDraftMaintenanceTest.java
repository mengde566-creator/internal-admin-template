package com.internaladmin.module.knowledge.service;

import com.internaladmin.module.file.api.ControlledDocumentFileApi;
import com.internaladmin.module.file.api.DocumentFilePurpose;
import com.internaladmin.module.iam.api.IamActorApi;
import com.internaladmin.module.knowledge.api.AiProperties;
import com.internaladmin.module.knowledge.api.KnowledgeQueryApi;
import com.internaladmin.module.knowledge.mapper.KnowledgeDraftMapper;
import com.internaladmin.module.knowledge.mapper.KnowledgeMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 06F陈旧发布只标记可显式重试、到期草稿先释放后删除的服务边界测试。 */
class KnowledgeDraftMaintenanceTest {
    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");

    @Test
    void stalePublishingIsInterruptedWithoutEmbeddingOrPublication() {
        KnowledgeDraftMapper drafts = mock(KnowledgeDraftMapper.class);
        KnowledgeDraftMapper.DraftRow row = draft("publishing", "PUBLISHING", 3, NOW.plus(1, ChronoUnit.DAYS));
        when(drafts.pageStalePublishing(any(), eq(50))).thenReturn(List.of(row));
        when(drafts.pageExpiredForMaintenance(any(), anyInt())).thenReturn(List.of());
        when(drafts.markPublishingInterrupted(eq("publishing"), eq(7L), eq(3), any())).thenReturn(1);

        KnowledgeDraftService service = service(mock(ControlledDocumentFileApi.class), drafts);
        KnowledgeDraftService.MaintenanceResult result = service.maintainOnce(NOW, 50);

        assertEquals(1, result.interrupted());
        verify(drafts).markPublishingInterrupted(eq("publishing"), eq(7L), eq(3), any());
    }

    @Test
    void expiredDraftDiscardsSourceBeforeDeletingDraft() {
        ControlledDocumentFileApi files = mock(ControlledDocumentFileApi.class);
        KnowledgeDraftMapper drafts = mock(KnowledgeDraftMapper.class);
        KnowledgeDraftMapper.DraftRow row = draft("expired", "PREVIEW_READY", 1, NOW.minusSeconds(1));
        when(drafts.pageStalePublishing(any(), eq(50))).thenReturn(List.of());
        when(drafts.pageExpiredForMaintenance(any(), anyInt())).thenReturn(List.of(row));
        when(drafts.findOwned("expired", 7L)).thenReturn(row);
        when(drafts.claimExpiredForMaintenance(eq("expired"), eq(7L), eq(1), any())).thenReturn(1);
        when(drafts.deleteExpiredDraft(eq("expired"), eq(7L), eq(2))).thenReturn(1);

        KnowledgeDraftService service = service(files, drafts);
        KnowledgeDraftService.MaintenanceResult result = service.maintainOnce(NOW, 50);

        assertEquals(1, result.deleted());
        var order = inOrder(files, drafts);
        order.verify(files).discard("asset-expired", 7L, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT);
        order.verify(drafts).deleteSections("expired");
        order.verify(drafts).deleteExpiredDraft("expired", 7L, 2);
    }

    private static KnowledgeDraftService service(ControlledDocumentFileApi files, KnowledgeDraftMapper drafts) {
        return new KnowledgeDraftService(files, mock(KnowledgeQueryApi.class), drafts, mock(IamActorApi.class),
                new NoopTransactionManager(), java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC),
                new KnowledgeDocumentParser(), null, mock(KnowledgeMapper.class), new AiProperties());
    }

    private static KnowledgeDraftMapper.DraftRow draft(String id, String status, int revision, Instant expiresAt) {
        return new KnowledgeDraftMapper.DraftRow(id, "warehouse-rules", "v3", "规则", 7L, "asset-" + id,
                "USER_UPLOAD", status, KnowledgeDocumentParser.PARSER_VERSION, "hash", 10, 1, 0, false,
                "v2", "base", null, NOW.minus(1, ChronoUnit.DAYS), NOW.minus(10, ChronoUnit.MINUTES), expiresAt, revision, null);
    }

    private static final class NoopTransactionManager implements PlatformTransactionManager {
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
            return new DefaultTransactionStatus(null, null, false, false, false, false, false, null);
        }
        @Override public void commit(TransactionStatus status) throws TransactionException { }
        @Override public void rollback(TransactionStatus status) throws TransactionException { }
    }
}
