package com.internaladmin.module.agent.warehouse;

import com.internaladmin.module.agent.warehouse.config.WarehouseSearchConfiguration;
import com.internaladmin.module.knowledge.api.AiSearchInfrastructure;
import com.internaladmin.module.warehouse.api.WarehouseItemProjectionApi;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Explicit, opt-in PostgreSQL contract gate; excluded from the normal Surefire test pattern. */
class WarehouseSearchPostgresIT {
    private static final String IMAGE = "pgvector/pgvector:0.8.6-pg17-bookworm";
    private static final String USER = "sl03f_eval";
    private static final String PASSWORD = "sl03f_eval_password";
    private static final String DATABASE = "sl03f_eval";

    @Test
    void liquibaseAndIndexLifecycleRunOnIsolatedPgvectorWithoutBusinessTables() throws Exception {
        PgContainer container = PgContainer.start();
        try {
            DataSource dataSource = new DriverManagerDataSource(container.jdbcUrl(), USER, PASSWORD);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            EmbeddingModel embedding = mock(EmbeddingModel.class);
            when(embedding.embed(org.mockito.ArgumentMatchers.<String>anyList()))
                    .thenReturn(List.of(new float[WarehouseSearchIndexStore.DIMENSIONS]));
            AiSearchInfrastructure infrastructure = new AiSearchInfrastructure(dataSource, jdbc, embedding,
                    WarehouseSearchIndexStore.MODEL, WarehouseSearchIndexStore.DIMENSIONS);

            SpringLiquibase liquibase = new WarehouseSearchConfiguration().warehouseSearchLiquibase(infrastructure);
            liquibase.afterPropertiesSet();

            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema='ai_warehouse_search' AND table_name IN "
                    + "('item_search_index','item_search_sync_state')", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM pg_extension WHERE extname='vector'", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM pg_extension WHERE extname='pg_trgm'", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema='public' AND table_name='warehouse_search_databasechangelog'", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema='public' AND table_name='wh_item'", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.schemata "
                    + "WHERE schema_name='ai_knowledge'", Integer.class));

            WarehouseSearchIndexStore store = new WarehouseSearchIndexStore(infrastructure);
            AtomicInteger scan = new AtomicInteger();
            WarehouseItemProjectionApi projections = projection(scan,
                    item("r-1", "ITEM-01", "密封圈", true, 1));
            WarehouseSearchSynchronizer synchronizer = new WarehouseSearchSynchronizer(projections, store, infrastructure);
            try {
                synchronizer.reconcile();
                assertEquals("READY", store.state().status());
                assertEquals(1, store.trigram("ITEM-01", 3).size());

                scan.set(0);
                projections = projection(scan, item("r-1", "ITEM-01-NEW", "改名密封圈", true, 2));
                synchronizer = new WarehouseSearchSynchronizer(projections, store, infrastructure);
                synchronizer.reconcile();
                var renamed = store.trigram("ITEM-01-NEW", 3);
                assertEquals(1, renamed.size());
                assertEquals(2, renamed.getFirst().sourceVersion());
                assertEquals("ITEM-01-NEW", renamed.getFirst().code());

                scan.set(0);
                projections = projection(scan, item("r-1", "ITEM-01-NEW", "改名密封圈", false, 3));
                synchronizer = new WarehouseSearchSynchronizer(projections, store, infrastructure);
                synchronizer.reconcile();
                assertTrue(store.trigram("ITEM-01-NEW", 3).isEmpty());
            } finally {
                synchronizer.stop();
            }
        } finally {
            container.destroy();
        }
    }

    private static WarehouseItemProjectionApi projection(AtomicInteger calls,
                                                          WarehouseItemProjectionApi.WarehouseItemSearchProjection item) {
        return new WarehouseItemProjectionApi() {
            @Override
            public WarehouseItemProjectionPage scanItems(String cursor, int limit) {
                return calls.getAndIncrement() == 0
                        ? new WarehouseItemProjectionPage(List.of(item), null)
                        : new WarehouseItemProjectionPage(List.of(), null);
            }

            @Override
            public WarehouseItemSearchProjection readItem(String itemRef) { return item; }

            @Override
            public List<WarehouseItemSearchProjection> revalidateItems(List<String> itemRefs,
                                                                         com.internaladmin.module.warehouse.api.WarehouseAccessScopeDTO scope) {
                return List.of(item);
            }
        };
    }

    private static WarehouseItemProjectionApi.WarehouseItemSearchProjection item(String ref, String code,
                                                                                    String name, boolean enabled,
                                                                                    long sourceVersion) {
        return new WarehouseItemProjectionApi.WarehouseItemSearchProjection(ref, code, name, enabled,
                sourceVersion, Instant.now());
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
            String name = "sl03f-search-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            int port = -1;
            try {
                docker("run", "-d", "--rm", "--name", name, "--tmpfs", "/var/lib/postgresql/data",
                        "-e", "POSTGRES_USER=" + USER, "-e", "POSTGRES_PASSWORD=" + PASSWORD,
                        "-e", "POSTGRES_DB=" + DATABASE, "-p", "127.0.0.1:0:5432", IMAGE);
                String mapping = "";
                for (int attempt = 0; attempt < 30; attempt++) {
                    try {
                        mapping = docker("port", name, "5432/tcp").trim();
                        if (!mapping.isBlank()) break;
                    } catch (RuntimeException ignored) {
                        Thread.sleep(200L);
                    }
                }
                if (mapping.isBlank()) throw new IllegalStateException("临时 PostgreSQL 端口未分配");
                port = Integer.parseInt(mapping.substring(mapping.lastIndexOf(':') + 1));
                for (int attempt = 0; attempt < 60; attempt++) {
                    try {
                        if (docker("exec", name, "pg_isready", "-U", USER, "-d", DATABASE)
                                .contains("accepting connections")) {
                            String tmpfs = docker("inspect", "--format", "{{json .HostConfig.Tmpfs}}", name);
                            String mounts = docker("inspect", "--format", "{{json .Mounts}}", name);
                            assertTrue(tmpfs.contains("/var/lib/postgresql/data"));
                            assertFalse(mounts.contains("\"Type\":\"volume\""));
                            return new PgContainer(name, port);
                        }
                    } catch (RuntimeException ignored) {
                        Thread.sleep(500L);
                    }
                }
                throw new IllegalStateException("临时 PostgreSQL 未在限定时间内就绪");
            } catch (Exception ex) {
                try { docker("rm", "-f", name); } catch (Exception ignored) { }
                throw ex;
            }
        }

        String jdbcUrl() { return "jdbc:postgresql://127.0.0.1:" + port + "/" + DATABASE; }

        void destroy() throws Exception {
            if (!destroyed) {
                docker("rm", "-f", name);
                destroyed = true;
            }
        }

        private static String docker(String... args) throws Exception {
            List<String> command = new ArrayList<>();
            command.add("docker"); command.addAll(List.of(args));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("docker 命令超时");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) throw new IllegalStateException("docker 命令失败: " + args[0]);
            return output;
        }
    }
}
