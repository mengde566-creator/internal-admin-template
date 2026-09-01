package com.internaladmin.app;

import com.internaladmin.module.file.api.ControlledDocumentFileApi;
import com.internaladmin.module.file.api.ControlledDocumentStoreRequest;
import com.internaladmin.module.file.api.DocumentFilePurpose;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 通过 app-server 正常 Liquibase 入口证明 06A 文档元数据表装配到隔离 SQLite。 */
@SpringBootTest(classes = Application.class, properties = "app.admin-initial-password=TestPass123")
class ControlledDocumentLiquibaseTest {

    private static final Path DATABASE = createDatabasePath();
    private static final Path STORAGE_ROOT = createStorageRoot();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ControlledDocumentFileApi documentFiles;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE + "?foreign_keys=on");
        registry.add("app.storage-root", () -> STORAGE_ROOT.toString());
    }

    @AfterAll
    static void cleanup() throws IOException {
        Files.deleteIfExists(DATABASE);
        Files.deleteIfExists(Path.of(DATABASE + "-wal"));
        Files.deleteIfExists(Path.of(DATABASE + "-shm"));
        if (Files.exists(STORAGE_ROOT)) {
            try (var paths = Files.walk(STORAGE_ROOT)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // Test-owned storage cleanup is best effort after the assertions complete.
                    }
                });
            }
        }
    }

    @Test
    void controlledDocumentTableAndImportLimitRowsAreMigrated() {
        Set<String> columns = jdbcTemplate.queryForList("PRAGMA table_info(file_document_asset)").stream()
                .map(row -> String.valueOf(row.get("name"))).collect(Collectors.toSet());
        assertTrue(columns.containsAll(Set.of("asset_id", "owner_id", "purpose", "status", "relative_path",
                "max_file_bytes", "max_spreadsheet_rows", "max_document_characters", "max_document_chunks",
                "unconfirmed_retention_days", "result_retention_days")));
        assertEquals(6, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM system_config WHERE param_key LIKE 'file_import.%'", Integer.class));
    }

    @Test
    void realServiceCommitAndOuterRollbackLeaveNoConsumableOrphan() throws Exception {
        ControlledDocumentStoreRequest request = new ControlledDocumentStoreRequest(
                7L, DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT, "rules.md", "text/markdown",
                new ByteArrayInputStream("事务边界".getBytes(StandardCharsets.UTF_8)));
        var committed = documentFiles.store(request);
        assertEquals("AVAILABLE", committed.status().name());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM file_document_asset WHERE status='AVAILABLE'", Integer.class));
        assertEquals(1, regularFileCount());

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ControlledDocumentStoreRequest rollbackRequest = new ControlledDocumentStoreRequest(
                    7L, DocumentFilePurpose.WAREHOUSE_ITEM_IMPORT, "rollback.md", "text/markdown",
                    new ByteArrayInputStream("回滚文件".getBytes(StandardCharsets.UTF_8)));
            documentFiles.store(rollbackRequest);
            assertEquals(2, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM file_document_asset WHERE status='AVAILABLE'", Integer.class));
            status.setRollbackOnly();
        });

        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM file_document_asset WHERE status='AVAILABLE'", Integer.class));
        assertEquals(1, regularFileCount(), "外层回滚不得留下只有文件的孤儿");
    }

    private int regularFileCount() throws IOException {
        if (!Files.exists(STORAGE_ROOT)) {
            return 0;
        }
        try (var paths = Files.walk(STORAGE_ROOT)) {
            return (int) paths.filter(Files::isRegularFile).count();
        }
    }

    private static Path createDatabasePath() {
        try {
            return Files.createTempFile("controlled-document-liquibase-", ".db");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Path createStorageRoot() {
        try {
            return Files.createTempDirectory("controlled-document-storage-");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
