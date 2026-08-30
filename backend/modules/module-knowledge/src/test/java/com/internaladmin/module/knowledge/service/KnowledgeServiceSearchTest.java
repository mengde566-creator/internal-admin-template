package com.internaladmin.module.knowledge.service;

import com.internaladmin.module.knowledge.api.AiProperties;
import com.internaladmin.module.knowledge.api.KnowledgeQueryApi;
import com.internaladmin.module.knowledge.api.KnowledgeRetrievalEmbeddingClient;
import com.internaladmin.module.knowledge.api.KnowledgeRetrievalEmbeddingClient.RetrievalEmbedding;
import com.internaladmin.module.knowledge.api.KnowledgeRetrievalEmbeddingClient.SparseEntry;
import com.internaladmin.module.knowledge.mapper.KnowledgeMapper;
import com.pgvector.PGvector;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeServiceSearchTest {

    @Test
    void queryUsesOneQueryEmbeddingAndBoundedActiveJoin() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeRetrievalEmbeddingClient client = mock(KnowledgeRetrievalEmbeddingClient.class);
        when(client.embedQuery("出库前要检查什么")).thenReturn(embedding(1));
        when(mapper.findActiveSparseChunks(any(), eq(KnowledgeService.SPARSE_SIMILARITY_THRESHOLD),
                eq(KnowledgeService.SEARCH_TOP_K + 1),
                eq(KnowledgeService.EMBEDDING_PROFILE), eq(1024))).thenReturn(List.of(
                new KnowledgeMapper.SearchRow("warehouse-rules", "仓储规则", "v2",
                        Instant.parse("2026-08-30T00:00:00Z"), Instant.parse("2026-08-30T00:01:00Z"),
                        "# 出库校验\n\n检查可用余额", 0.83d, 2)));
        KnowledgeService service = service(mapper, client);

        KnowledgeQueryApi.Result result = service.query("出库前要检查什么", 3);

        assertThat(result.status()).isEqualTo(KnowledgeQueryApi.Status.FOUND);
        assertThat(result.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.documentCode()).isEqualTo("warehouse-rules");
            assertThat(citation.versionCode()).isEqualTo("v2");
            assertThat(citation.section()).isEqualTo("出库校验");
        });
        verify(client).embedQuery("出库前要检查什么");
        verify(mapper).findActiveSparseChunks(any(), eq(KnowledgeService.SPARSE_SIMILARITY_THRESHOLD),
                eq(KnowledgeService.SEARCH_TOP_K + 1),
                eq(KnowledgeService.EMBEDDING_PROFILE), eq(1024));
        verify(mapper, org.mockito.Mockito.never()).findActiveDenseChunks(any(PGvector.class), anyDouble(), anyInt(),
                anyString(), anyInt());
    }

    @Test
    void emptyBoundedRowsAreSuccessfulNoEvidence() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeRetrievalEmbeddingClient client = mock(KnowledgeRetrievalEmbeddingClient.class);
        when(client.embedQuery(anyString())).thenReturn(embedding(1));
        when(mapper.findActiveSparseChunks(any(), anyDouble(), anyInt(), anyString(), anyInt()))
                .thenReturn(List.of());
        when(mapper.findActiveDenseChunks(any(PGvector.class), anyDouble(), anyInt(), anyString(), anyInt()))
                .thenReturn(List.of());

        assertThat(service(mapper, client).query("无关问题", 5).status())
                .isEqualTo(KnowledgeQueryApi.Status.NO_EVIDENCE);
    }

    @Test
    void sparseMissFallsBackToDenseExactlyOnce() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeRetrievalEmbeddingClient client = mock(KnowledgeRetrievalEmbeddingClient.class);
        when(client.embedQuery(anyString())).thenReturn(embedding(1));
        when(mapper.findActiveSparseChunks(any(), anyDouble(), anyInt(), anyString(), anyInt()))
                .thenReturn(List.of());
        when(mapper.findActiveDenseChunks(any(PGvector.class), eq(KnowledgeService.SIMILARITY_THRESHOLD),
                eq(KnowledgeService.SEARCH_TOP_K + 1),
                eq(KnowledgeService.EMBEDDING_PROFILE), eq(1024))).thenReturn(List.of(
                new KnowledgeMapper.SearchRow("low-stock-policy", "低库存", "v1", null, null,
                        "# 低库存\n\n阈值20", 0.81d, 1)));

        assertThat(service(mapper, client).query("助手能补货吗", 5).status())
                .isEqualTo(KnowledgeQueryApi.Status.FOUND);
        verify(mapper).findActiveDenseChunks(any(PGvector.class), eq(KnowledgeService.SIMILARITY_THRESHOLD),
                eq(KnowledgeService.SEARCH_TOP_K + 1),
                eq(KnowledgeService.EMBEDDING_PROFILE), eq(1024));
    }

    @Test
    void sparseFailureIsUnavailableAndNeverMasqueradesAsDense() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeRetrievalEmbeddingClient client = mock(KnowledgeRetrievalEmbeddingClient.class);
        when(client.embedQuery(anyString())).thenReturn(embedding(1));
        when(mapper.findActiveSparseChunks(any(), anyDouble(), anyInt(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("sparse database unavailable"));

        assertThat(service(mapper, client).query("位置编码", 5).status())
                .isEqualTo(KnowledgeQueryApi.Status.UNAVAILABLE);
        verify(mapper, org.mockito.Mockito.never()).findActiveDenseChunks(any(PGvector.class), anyDouble(), anyInt(),
                anyString(), anyInt());
    }

    @Test
    void apiLimitOnlyControlsReturnedCitationsAndReportsRealHasMore() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeRetrievalEmbeddingClient client = mock(KnowledgeRetrievalEmbeddingClient.class);
        when(client.embedQuery(anyString())).thenReturn(embedding(1));
        List<KnowledgeMapper.SearchRow> rows = List.of(
                new KnowledgeMapper.SearchRow("rules", "规则", "v2", null, null, "# 一\n\n甲", .9d, 1),
                new KnowledgeMapper.SearchRow("codes", "编码", "v2", null, null, "# 二\n\n乙", .8d, 1),
                new KnowledgeMapper.SearchRow("locations", "位置", "v2", null, null, "# 三\n\n丙", .7d, 1),
                new KnowledgeMapper.SearchRow("stock", "库存", "v1", null, null, "# 四\n\n丁", .6d, 1));
        when(mapper.findActiveSparseChunks(any(), anyDouble(), eq(KnowledgeService.SEARCH_TOP_K + 1),
                anyString(), anyInt())).thenReturn(rows);

        KnowledgeQueryApi.Result result = service(mapper, client).query("规则", 1);

        assertThat(result.citations()).hasSize(1);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void embeddingOrDatabaseFailureIsUnavailable() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeRetrievalEmbeddingClient client = mock(KnowledgeRetrievalEmbeddingClient.class);
        when(client.embedQuery(anyString())).thenThrow(new IllegalStateException("AI_EMBEDDING_UNAVAILABLE"));

        KnowledgeQueryApi.Result result = service(mapper, client).query("仓储", 5);

        assertThat(result.status()).isEqualTo(KnowledgeQueryApi.Status.UNAVAILABLE);
        assertThat(result.errorCode()).isEqualTo("AI_KNOWLEDGE_UNAVAILABLE");
        verifyNoInteractions(mapper);
    }

    @Test
    void invalidSearchRowIsDroppedWithoutLeakingCitation() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeRetrievalEmbeddingClient client = mock(KnowledgeRetrievalEmbeddingClient.class);
        when(client.embedQuery(anyString())).thenReturn(embedding(1));
        when(mapper.findActiveSparseChunks(any(), anyDouble(), anyInt(), anyString(), anyInt()))
                .thenReturn(List.of());
        when(mapper.findActiveDenseChunks(any(PGvector.class), anyDouble(), anyInt(), anyString(), anyInt()))
                .thenReturn(List.of(new KnowledgeMapper.SearchRow("rules", "规则", "v2", null, null,
                        "", 0.99d, null)));

        assertThat(service(mapper, client).query("规则", 5).status())
                .isEqualTo(KnowledgeQueryApi.Status.NO_EVIDENCE);
    }

    @Test
    void queryRejectsBlankControlTextAndOutOfRangeLimit() {
        KnowledgeService service = service(mock(KnowledgeMapper.class), mock(KnowledgeRetrievalEmbeddingClient.class));
        assertThatThrownBy(() -> service.query(" ", 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.query("仓储\u0001", 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.query("仓储", 6)).isInstanceOf(IllegalArgumentException.class);
    }

    private static KnowledgeService service(KnowledgeMapper mapper, KnowledgeRetrievalEmbeddingClient client) {
        return new KnowledgeService(new AiProperties(), client, mapper, mock(PlatformTransactionManager.class));
    }

    private static RetrievalEmbedding embedding(int index) {
        float[] dense = new float[1024];
        dense[0] = 1f;
        return new RetrievalEmbedding(dense, List.of(new SparseEntry(index, 1f)));
    }
}
