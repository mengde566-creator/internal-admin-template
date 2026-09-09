package com.internaladmin.module.knowledge.service;

import com.internaladmin.module.file.api.ControlledDocumentAsset;
import com.internaladmin.module.file.api.ControlledDocumentFileApi;
import com.internaladmin.module.file.api.ControlledDocumentRead;
import com.internaladmin.module.file.api.DocumentFileLimitSnapshot;
import com.internaladmin.module.file.api.DocumentFilePurpose;
import com.internaladmin.module.file.api.DocumentFileStatus;
import com.internaladmin.module.iam.api.IamActorApi;
import com.internaladmin.module.iam.api.IamActorDTO;
import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.iam.api.ScopeMode;
import com.internaladmin.module.knowledge.api.AiProperties;
import com.internaladmin.module.knowledge.api.KnowledgeDraftApi;
import com.internaladmin.module.knowledge.api.KnowledgeQueryApi;
import com.internaladmin.module.knowledge.api.KnowledgeRetrievalEmbeddingClient;
import com.internaladmin.module.knowledge.api.KnowledgeRetrievalEmbeddingClient.RetrievalEmbedding;
import com.internaladmin.module.knowledge.mapper.KnowledgeDraftMapper;
import com.internaladmin.module.knowledge.mapper.KnowledgeMapper;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Explicit, single-run Qwen publish gate.  The normal test suite skips this
 * class; the opt-in invocation must source the local project environment and
 * uses only a disposable tmpfs pgvector container.
 */
class KnowledgeDraftPublishExternalIT {
    private static final String RUN_PROPERTY = "RUN_KNOWLEDGE_PUBLISH_GATE";
    private static final String IMAGE = "pgvector/pgvector:0.8.6-pg17-bookworm";
    private static final String USER = "knowledge_06f_gate";
    private static final String PASSWORD = "knowledge_06f_gate_password";
    private static final String DATABASE = "knowledge_06f_gate";
    private static final DocumentFileLimitSnapshot LIMITS =
            new DocumentFileLimitSnapshot(10_000_000, 100_000, 20_000, 20, 7, 30);

    @Test
    void qwenPublishesOneUserDraftAndExposesItThroughActiveReadAndSearch() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getProperty(RUN_PROPERTY)));
        String apiKey = requiredEnv("APP_AI_EMBEDDING_QWEN_API_KEY");
        String baseUrl = requiredEnv("APP_AI_EMBEDDING_QWEN_BASE_URL");
        String model = requiredEnv("APP_AI_EMBEDDING_QWEN_MODEL");
        String dimensions = requiredEnv("APP_AI_EMBEDDING_QWEN_DIMENSIONS");
        Assumptions.assumeTrue("qwen3.7-text-embedding".equals(model));
        Assumptions.assumeTrue("1024".equals(dimensions));

        PgContainer container = PgContainer.start();
        try {
            DataSource dataSource = new DriverManagerDataSource(container.jdbcUrl(), USER, PASSWORD);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            SpringLiquibase liquibase = new SpringLiquibase();
            liquibase.setDataSource(dataSource);
            liquibase.setChangeLog("classpath:db/knowledge-changelog-master.xml");
            liquibase.setDatabaseChangeLogTable("knowledge_databasechangelog");
            liquibase.setDatabaseChangeLogLockTable("knowledge_databasechangeloglock");
            liquibase.afterPropertiesSet();
            PlatformTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
            KnowledgeMapper mapper = new KnowledgeMapper(jdbc);
            KnowledgeDraftMapper draftMapper = new KnowledgeDraftMapper(jdbc);

            AiProperties properties = new AiProperties();
            properties.getEmbedding().getQwen().setApiKey(apiKey);
            properties.getEmbedding().getQwen().setBaseUrl(baseUrl);
            properties.getEmbedding().getQwen().setModel(model);
            properties.getEmbedding().getQwen().setDimensions(1024);
            KnowledgeRetrievalEmbeddingClient delegate =
                    new DashScopeKnowledgeEmbeddingClient(properties.getEmbedding().getQwen());
            CountingEmbeddingClient embedding = new CountingEmbeddingClient(delegate);
            KnowledgeService queryService = new KnowledgeService(properties, embedding, mapper, transactionManager);
            ControlledDocumentFileApi files = fileFixture();
            IamActorApi actors = userId -> new IamActorDTO(userId, 1L, ScopeMode.ALL_DEPARTMENTS,
                    List.of(PermissionCodes.AI_KNOWLEDGE_MANAGE));
            KnowledgeDraftService drafts = new KnowledgeDraftService(files, queryService, draftMapper, actors,
                    transactionManager, java.time.Clock.systemUTC(), new KnowledgeDocumentParser(), embedding, mapper,
                    properties);

            String documentCode = "gate-06f-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            String markdown = "# 低库存处置\n\n库存低于阈值时，维护人员应先复核数量并提交补货申请。\n"
                    + "\n## 复核\n\n复核完成后记录处理结果。";
            KnowledgeDraftApi.DraftView draft = drafts.submit(7L,
                    new KnowledgeDraftApi.DraftRequest(documentCode, "v1", "低库存处置（Gate）", "gate-request-1"),
                    "gate-06f.md", new java.io.ByteArrayInputStream(markdown.getBytes(StandardCharsets.UTF_8)));
            assertThat(draft.status()).isEqualTo("PREVIEW_READY");

            KnowledgeDraftApi.DraftView published = drafts.publish(7L, draft.draftId(),
                    new KnowledgeDraftApi.PublishRequest(draft.revision(), "gate-publish-1", true));
            assertThat(published.status()).isEqualTo("PUBLISHED");
            assertThat(published.sourceType()).isEqualTo("USER_UPLOAD");
            assertThat(embedding.documentCalls.get()).isEqualTo(1);

            KnowledgeQueryApi.CatalogResult catalog = queryService.listActiveDocuments();
            assertThat(catalog.status()).isEqualTo(KnowledgeQueryApi.Status.FOUND);
            assertThat(catalog.documents()).anySatisfy(document -> {
                assertThat(document.documentCode()).isEqualTo(documentCode);
                assertThat(document.versionCode()).isEqualTo("v1");
                assertThat(document.synthetic()).isFalse();
                assertThat(document.sourceType()).isEqualTo("USER_UPLOAD");
            });
            KnowledgeQueryApi.DocumentResult read = queryService.readActiveDocument(documentCode, 20, 20_000);
            assertThat(read.status()).isEqualTo(KnowledgeQueryApi.Status.FOUND);
            assertThat(read.citations()).isNotEmpty();
            assertThat(read.citations()).allSatisfy(citation -> {
                assertThat(citation.documentCode()).isEqualTo(documentCode);
                assertThat(citation.versionCode()).isEqualTo("v1");
                assertThat(citation.synthetic()).isFalse();
                assertThat(citation.sourceType()).isEqualTo("USER_UPLOAD");
            });
            KnowledgeQueryApi.Result search = queryService.query("库存低于阈值时如何处理", 1);
            assertThat(search.status()).isEqualTo(KnowledgeQueryApi.Status.FOUND);
            assertThat(search.citations()).singleElement().satisfies(citation -> {
                assertThat(citation.documentCode()).isEqualTo(documentCode);
                assertThat(citation.versionCode()).isEqualTo("v1");
                assertThat(citation.synthetic()).isFalse();
            });
            assertThat(embedding.queryCalls.get()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_knowledge.ai_knowledge_version "
                    + "WHERE status='ACTIVE' AND document_id IN (SELECT id FROM ai_knowledge.ai_knowledge_document "
                    + "WHERE document_code=?)", Integer.class, documentCode)).isEqualTo(1);
        } finally {
            container.destroy();
        }
    }

    private static ControlledDocumentFileApi fileFixture() {
        ControlledDocumentFileApi files = Mockito.mock(ControlledDocumentFileApi.class);
        byte[] content = "# 低库存处置\n\n库存低于阈值时，维护人员应先复核数量并提交补货申请。\n\n## 复核\n\n复核完成后记录处理结果。"
                .getBytes(StandardCharsets.UTF_8);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ControlledDocumentAsset asset = new ControlledDocumentAsset("asset-gate-06f", "gate-06f.md",
                "text/markdown", content.length, sha256(content), 7L,
                DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT, DocumentFileStatus.AVAILABLE,
                now, now.plusDays(7), LIMITS);
        when(files.store(any())).thenReturn(asset);
        when(files.read("asset-gate-06f", 7L, DocumentFilePurpose.KNOWLEDGE_DOCUMENT_IMPORT))
                .thenReturn(new ControlledDocumentRead(asset, content));
        return files;
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) value = System.getProperty(name);
        Assumptions.assumeTrue(value != null && !value.isBlank(), name + " is required for the explicit gate");
        return value;
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder value = new StringBuilder();
            for (byte current : digest) value.append(String.format("%02x", current));
            return value.toString();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class CountingEmbeddingClient implements KnowledgeRetrievalEmbeddingClient {
        private final KnowledgeRetrievalEmbeddingClient delegate;
        private final AtomicInteger documentCalls = new AtomicInteger();
        private final AtomicInteger queryCalls = new AtomicInteger();

        private CountingEmbeddingClient(KnowledgeRetrievalEmbeddingClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<RetrievalEmbedding> embedDocuments(List<String> texts) {
            documentCalls.incrementAndGet();
            return delegate.embedDocuments(texts);
        }

        @Override
        public RetrievalEmbedding embedQuery(String text) {
            queryCalls.incrementAndGet();
            return delegate.embedQuery(text);
        }
    }

    private static final class PgContainer {
        private final String name;
        private final int port;
        private boolean destroyed;

        private PgContainer(String name, int port) {
            this.name = name;
            this.port = port;
        }

        static PgContainer start() throws Exception {
            String name = "knowledge-06f-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            try {
                docker("run", "-d", "--rm", "--name", name, "--tmpfs", "/var/lib/postgresql/data",
                        "-e", "POSTGRES_USER=" + USER, "-e", "POSTGRES_PASSWORD=" + PASSWORD,
                        "-e", "POSTGRES_DB=" + DATABASE, "-p", "127.0.0.1:0:5432", IMAGE);
                String mapping = "";
                for (int i = 0; i < 30 && mapping.isBlank(); i++) {
                    try {
                        mapping = docker("port", name, "5432/tcp").trim();
                    } catch (RuntimeException ignored) {
                        // The container is still publishing its random port.
                    }
                    if (mapping.isBlank()) Thread.sleep(200L);
                }
                if (mapping.isBlank()) throw new IllegalStateException("临时知识 PostgreSQL 端口未分配");
                int port = Integer.parseInt(mapping.substring(mapping.lastIndexOf(':') + 1));
                for (int i = 0; i < 60; i++) {
                    try {
                        if (docker("exec", name, "pg_isready", "-U", USER, "-d", DATABASE)
                                .contains("accepting connections")) {
                            String tmpfs = docker("inspect", "--format", "{{json .HostConfig.Tmpfs}}", name);
                            String mounts = docker("inspect", "--format", "{{json .Mounts}}", name);
                            if (!tmpfs.contains("/var/lib/postgresql/data") || mounts.contains("\"Type\":\"volume\"")) {
                                throw new IllegalStateException("知识 PostgreSQL 容器不是 tmpfs 无卷模式");
                            }
                            return new PgContainer(name, port);
                        }
                    } catch (RuntimeException ignored) {
                        // pg_isready has not accepted connections yet.
                    }
                    Thread.sleep(500L);
                }
                throw new IllegalStateException("临时知识 PostgreSQL 未在限定时间内就绪");
            } catch (Exception exception) {
                try {
                    docker("rm", "-f", name);
                } catch (Exception ignored) {
                    // Preserve the startup failure as the test result.
                }
                throw exception;
            }
        }

        String jdbcUrl() {
            return "jdbc:postgresql://127.0.0.1:" + port + "/" + DATABASE;
        }

        void destroy() throws Exception {
            if (!destroyed) {
                docker("rm", "-f", name);
                destroyed = true;
            }
        }

        private static String docker(String... args) throws Exception {
            List<String> command = new ArrayList<>();
            command.add("docker");
            command.addAll(List.of(args));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("docker命令超时");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) throw new IllegalStateException("docker命令失败");
            return output;
        }
    }
}
