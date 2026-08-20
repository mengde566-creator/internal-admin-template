package com.internaladmin.module.knowledge;

import com.internaladmin.module.knowledge.service.DimensionCheckingEmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DimensionCheckingEmbeddingModelTest {

    @Test
    void rejectsEveryProviderVectorThatDoesNotMatchTheConfiguredDimension() {
        EmbeddingModel provider = mock(EmbeddingModel.class);
        when(provider.embed(List.of("only the chunk text"))).thenReturn(List.of(new float[2], new float[1024]));

        DimensionCheckingEmbeddingModel model = new DimensionCheckingEmbeddingModel(provider, 1024);

        assertThatThrownBy(() -> model.embed(List.of("only the chunk text")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected dimension 1024 but received 2");
    }
}
