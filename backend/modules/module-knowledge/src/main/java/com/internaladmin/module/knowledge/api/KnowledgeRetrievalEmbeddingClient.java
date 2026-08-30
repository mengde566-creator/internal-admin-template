package com.internaladmin.module.knowledge.api;

import java.util.List;
import java.util.HashSet;

/**
 * Narrow asymmetric embedding contract used only by the knowledge retrieval path.
 * Implementations must preserve document/query semantics and validate provider output.
 */
public interface KnowledgeRetrievalEmbeddingClient {

    List<RetrievalEmbedding> embedDocuments(List<String> texts);

    RetrievalEmbedding embedQuery(String text);

    /** Provider output used by the knowledge-only asymmetric retrieval path. */
    record RetrievalEmbedding(float[] denseVector, List<SparseEntry> sparseEntries) {
        public RetrievalEmbedding {
            if (denseVector == null || sparseEntries == null || sparseEntries.isEmpty() || sparseEntries.size() > 4096) {
                throw new IllegalArgumentException("Embedding结果不完整");
            }
            if (new HashSet<>(sparseEntries.stream().map(SparseEntry::index).toList()).size() != sparseEntries.size()) {
                throw new IllegalArgumentException("稀疏向量项重复");
            }
            denseVector = denseVector.clone();
            sparseEntries = List.copyOf(sparseEntries);
        }

        @Override
        public float[] denseVector() {
            return denseVector.clone();
        }
    }

    record SparseEntry(int index, float weight) {
        public SparseEntry {
            if (index < 0 || !Float.isFinite(weight) || weight <= 0f) {
                throw new IllegalArgumentException("稀疏向量项无效");
            }
        }
    }
}
