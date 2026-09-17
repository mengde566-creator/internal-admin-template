package com.internaladmin.module.knowledge.service;

import com.internaladmin.module.knowledge.api.AiProperties;
import com.internaladmin.module.knowledge.api.KnowledgeContentPack;
import com.internaladmin.module.knowledge.api.KnowledgeRetrievalEmbeddingClient;
import com.internaladmin.module.knowledge.api.KnowledgeRetrievalEmbeddingClient.RetrievalEmbedding;
import com.internaladmin.module.knowledge.api.KnowledgeRetrievalEmbeddingClient.SparseEntry;
import com.internaladmin.module.knowledge.mapper.KnowledgeMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeServiceRetrievalInstructionTest {
    @Test
    void coreInstructionReachesQueryEmbeddingClientWithContentPack() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeRetrievalEmbeddingClient embedding = mock(KnowledgeRetrievalEmbeddingClient.class);
        when(embedding.embedQuery("问题", KnowledgeRetrievalEmbeddingClient.DEFAULT_QUERY_INSTRUCTION)).thenReturn(embedding());
        when(mapper.findActiveSparseChunks(anyList(), anyDouble(), anyInt(), anyString(), anyInt()))
                .thenReturn(List.of());
        when(mapper.findActiveDenseChunks(any(), anyDouble(), anyInt(), anyString(), anyInt()))
                .thenReturn(List.of());

        KnowledgeService service = service(mapper, embedding);
        service.query("问题", 1);

        verify(embedding).embedQuery("问题", KnowledgeRetrievalEmbeddingClient.DEFAULT_QUERY_INSTRUCTION);
    }

    @Test
    void coreInstructionRemainsSameWithoutContentPack() {
        KnowledgeMapper mapper = mock(KnowledgeMapper.class);
        KnowledgeRetrievalEmbeddingClient embedding = mock(KnowledgeRetrievalEmbeddingClient.class);
        when(embedding.embedQuery("问题", KnowledgeRetrievalEmbeddingClient.DEFAULT_QUERY_INSTRUCTION)).thenReturn(embedding());
        when(mapper.findActiveSparseChunks(anyList(), anyDouble(), anyInt(), anyString(), anyInt()))
                .thenReturn(List.of());
        when(mapper.findActiveDenseChunks(any(), anyDouble(), anyInt(), anyString(), anyInt()))
                .thenReturn(List.of());

        KnowledgeService service = service(mapper, embedding, new KnowledgeContentPackRegistry(List.of()));
        service.query("问题", 1);

        verify(embedding).embedQuery("问题", KnowledgeRetrievalEmbeddingClient.DEFAULT_QUERY_INSTRUCTION);
    }

    private static KnowledgeService service(KnowledgeMapper mapper,
                                             KnowledgeRetrievalEmbeddingClient embedding) {
        return service(mapper, embedding, new KnowledgeContentPackRegistry(List.of(pack())));
    }

    private static KnowledgeService service(KnowledgeMapper mapper,
                                             KnowledgeRetrievalEmbeddingClient embedding,
                                             KnowledgeContentPackRegistry contentPacks) {
        AiProperties properties = new AiProperties();
        properties.getEmbedding().getQwen().setDimensions(1024);
        return new KnowledgeService(properties, embedding, mapper, mock(PlatformTransactionManager.class),
                contentPacks);
    }

    private static KnowledgeContentPack pack() {
        byte[] bytes = "# Heading\n\nBody".getBytes(StandardCharsets.UTF_8);
        return new KnowledgeContentPack() {
            @Override public String packId() { return "instruction-test-pack"; }
            @Override public String packVersion() { return "instruction-test-pack-v1"; }
            @Override public String compatibilityVersion() { return KnowledgeContentPackRegistry.COMPATIBILITY_VERSION; }
            @Override public List<Document> documents() {
                return List.of(new Document("instruction-doc", "v1", "Instruction doc", "ACTIVE", 1,
                        new ByteArrayResource(bytes), sha256(bytes)));
            }
        };
    }

    private static RetrievalEmbedding embedding() {
        return new RetrievalEmbedding(new float[1024], List.of(new SparseEntry(1, 1f)));
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(64);
            for (byte current : digest) result.append(String.format("%02x", current));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
