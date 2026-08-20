package com.internaladmin.module.knowledge.service;

import com.internaladmin.module.knowledge.api.AiProperties;
import com.internaladmin.module.knowledge.mapper.KnowledgeMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeServiceSearchTest {

    @Test
    void activeVersionFilterIsSentToVectorStoreBeforeDefensiveFiltering() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        VectorStore vectorStore = mock(VectorStore.class);
        DimensionCheckingEmbeddingModel embeddingModel = mock(DimensionCheckingEmbeddingModel.class);
        KnowledgeService service = service(mapper, vectorStore, embeddingModel);

        when(mapper.findActiveVersionIds()).thenReturn(Set.of("active-v1"));
        Document inactive = document("失效", "inactive-v0", 0.99);
        Document active = document("生效", "active-v1", 0.50);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(inactive, active));

        List<KnowledgeService.KnowledgeSearchResult> result = service.search("仓储", 1);

        assertThat(result).extracting(KnowledgeService.KnowledgeSearchResult::content)
                .containsExactly("生效");
        var request = org.mockito.ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(request.capture());
        assertThat(request.getValue().hasFilterExpression()).isTrue();
        assertThat(request.getValue().getFilterExpression().toString()).contains("active-v1");
    }

    @Test
    void noActiveVersionReturnsEmptyWithoutEmbeddingOrVectorRequest() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        VectorStore vectorStore = mock(VectorStore.class);
        DimensionCheckingEmbeddingModel embeddingModel = mock(DimensionCheckingEmbeddingModel.class);
        KnowledgeService service = service(mapper, vectorStore, embeddingModel);

        when(mapper.findActiveVersionIds()).thenReturn(Set.of());

        assertThat(service.search("仓储", 5)).isEmpty();
        verifyNoInteractions(vectorStore, embeddingModel);
    }

    private static KnowledgeService service(KnowledgeMapper mapper, VectorStore vectorStore,
                                             DimensionCheckingEmbeddingModel embeddingModel) {
        return new KnowledgeService(new AiProperties(), embeddingModel, vectorStore, mapper,
                mock(PlatformTransactionManager.class));
    }

    private static Document document(String text, String versionId, double score) {
        return Document.builder().text(text)
                .metadata(Map.of("versionId", versionId, "documentCode", "rules", "versionCode", "v1", "chunkNo", 1))
                .score(score)
                .build();
    }
}
