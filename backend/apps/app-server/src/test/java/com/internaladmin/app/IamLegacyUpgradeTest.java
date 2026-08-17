package com.internaladmin.app;

import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 以独立临时 SQLite 证明旧 IAM 结构经当前 master 升级后保留历史事实。 */
class IamLegacyUpgradeTest {

    @TempDir
    Path tempDir;

    @Test
    void legacyIamSchemaUpgradesAndPreservesRootAndUser() throws Exception {
        DataSource dataSource = dataSource(tempDir.resolve("legacy-upgrade.db"));
        runLiquibase(dataSource, "classpath:db/changelog/legacy-iam-fixture.xml");

        try (Connection connection = dataSource.getConnection();
             ResultSet columns = connection.createStatement().executeQuery("PRAGMA table_info('iam_department')")) {
            assertEquals(3, countColumns(columns));
        }

        runLiquibase(dataSource, "classpath:db/changelog-master.xml");

        try (Connection connection = dataSource.getConnection();
             ResultSet root = connection.createStatement().executeQuery(
                     "SELECT code, parent_id, sort_order, enabled, deleted, version FROM iam_department WHERE id=1")) {
            assertNotNull(root);
            root.next();
            assertEquals("ROOT", root.getString("code"));
            assertNull(root.getObject("parent_id"));
            assertEquals(0, root.getInt("sort_order"));
            assertEquals(1, root.getInt("enabled"));
            assertEquals(0, root.getInt("deleted"));
            assertEquals(0, root.getInt("version"));
        }
        try (Connection connection = dataSource.getConnection();
             ResultSet user = connection.createStatement().executeQuery(
                     "SELECT username, department_id, deleted FROM iam_user WHERE id=1001")) {
            assertNotNull(user);
            user.next();
            assertEquals("legacy-user", user.getString("username"));
            assertEquals(1L, user.getLong("department_id"));
            assertEquals(0, user.getInt("deleted"));
        }
    }

    private DataSource dataSource(Path path) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + path);
        return dataSource;
    }

    private void runLiquibase(DataSource dataSource, String changeLog) throws Exception {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(changeLog);
        liquibase.setShouldRun(true);
        liquibase.afterPropertiesSet();
    }

    private int countColumns(ResultSet columns) throws Exception {
        int count = 0;
        while (columns.next()) {
            count++;
        }
        return count;
    }
}
