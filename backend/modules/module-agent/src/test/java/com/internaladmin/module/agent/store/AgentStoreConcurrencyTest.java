package com.internaladmin.module.agent.store;

import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves active-run reservation is decided by one database compare-and-set. */
class AgentStoreConcurrencyTest {

    @TempDir
    Path tempDir;

    @Test
    void onlyOneRunCanReserveTheSameConversation() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:file:" + tempDir.resolve("agent-concurrency.db")
                + "?cache=shared&busy_timeout=5000");
        runLiquibase(dataSource);
        AgentStore first = new AgentStore(new JdbcTemplate(dataSource));
        AgentStore second = new AgentStore(new JdbcTemplate(dataSource));
        first.ensureConversation("gate-b-concurrency", 7L);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> futures = List.of(
                    submit(executor, first, "client-a", ready, go),
                    submit(executor, second, "client-b", ready, go));
            ready.await();
            go.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get());
            }
            assertEquals(1, outcomes.stream().filter(AgentStore.StartRun.class::isInstance).count());
            assertEquals(1, outcomes.stream().filter(BusinessExceptionMarker.class::isInstance).count());
            BusinessExceptionMarker conflict = outcomes.stream()
                    .filter(BusinessExceptionMarker.class::isInstance)
                    .map(BusinessExceptionMarker.class::cast)
                    .findFirst().orElseThrow();
            assertEquals("CONFLICT", conflict.code());
        }
        finally {
            executor.shutdownNow();
        }
    }

    @Test
    void onlyOneTerminalTransitionWinsAndReleasesActiveRun() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:file:" + tempDir.resolve("agent-terminal-race.db")
                + "?cache=shared&busy_timeout=5000");
        runLiquibase(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        AgentStore first = new AgentStore(jdbc);
        AgentStore second = new AgentStore(jdbc);
        first.ensureConversation("gate-b-terminal-race", 7L);
        AgentStore.StartRun run = first.startRun("gate-b-terminal-race", "client-terminal", "库存", 7L);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> one = terminal(executor, first, run.runId(), ready, go);
            Future<Boolean> two = terminal(executor, second, run.runId(), ready, go);
            ready.await();
            go.countDown();
            assertEquals(1, List.of(one.get(), two.get()).stream().filter(Boolean.TRUE::equals).count());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_conversation "
                    + "WHERE active_run_id = ?", Integer.class, run.runId()));
            assertTrue(!first.fail(run.runId(), "late") && !second.cancel(run.runId()));
        }
        finally {
            executor.shutdownNow();
        }
    }

    private Future<Boolean> terminal(ExecutorService executor, AgentStore store, String runId,
                                     CountDownLatch ready, CountDownLatch go) {
        return executor.submit(() -> {
            ready.countDown();
            go.await();
            return store.complete(runId);
        });
    }

    private Future<Object> submit(ExecutorService executor, AgentStore store, String client,
                                  CountDownLatch ready, CountDownLatch go) {
        return executor.submit(() -> {
            ready.countDown();
            go.await();
            try {
                return store.startRun("gate-b-concurrency", client, "库存", 7L);
            }
            catch (com.internaladmin.platform.kernel.error.BusinessException exception) {
                return new BusinessExceptionMarker(exception.getErrorCode().name());
            }
        });
    }

    private void runLiquibase(SQLiteDataSource dataSource) throws Exception {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:/db/changelog/agent-concurrency-master.xml");
        liquibase.setShouldRun(true);
        liquibase.afterPropertiesSet();
    }

    private record BusinessExceptionMarker(String code) {
    }
}
