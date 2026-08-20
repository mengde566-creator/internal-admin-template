package com.internaladmin.module.knowledge.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.List;

/** EmbeddingModel decorator that turns a provider dimension drift into a visible failure. */
public final class DimensionCheckingEmbeddingModel extends AbstractEmbeddingModel {

    private final EmbeddingModel delegate;
    private final int expectedDimensions;

    public DimensionCheckingEmbeddingModel(EmbeddingModel delegate, int expectedDimensions) {
        this.delegate = delegate;
        this.expectedDimensions = expectedDimensions;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        EmbeddingResponse response = delegate.call(request);
        validate(response);
        return response;
    }

    @Override
    public float[] embed(Document document) {
        float[] result = delegate.embed(document);
        validate(result);
        return result;
    }

    @Override
    public List<float[]> embed(List<String> instructions) {
        List<float[]> result = delegate.embed(instructions);
        result.forEach(this::validate);
        return result;
    }

    @Override
    public List<float[]> embed(List<Document> documents,
                               EmbeddingOptions options,
                               BatchingStrategy batchingStrategy) {
        List<float[]> result = delegate.embed(documents, options, batchingStrategy);
        result.forEach(this::validate);
        return result;
    }

    @Override
    public int dimensions() {
        return expectedDimensions;
    }

    private void validate(EmbeddingResponse response) {
        if (response == null || response.getResults() == null) {
            throw new IllegalStateException("AI_EMBEDDING_UNAVAILABLE: provider returned no vectors");
        }
        response.getResults().forEach(result -> validate(result.getOutput()));
    }

    private void validate(float[] vector) {
        if (vector == null || vector.length != expectedDimensions) {
            int actual = vector == null ? 0 : vector.length;
            throw new IllegalStateException("AI_EMBEDDING_UNAVAILABLE: expected dimension "
                    + expectedDimensions + " but received " + actual);
        }
    }
}
