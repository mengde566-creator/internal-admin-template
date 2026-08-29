package com.internaladmin.module.agent.warehouse;

import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WarehouseSearchChangelogTest {
    @Test
    void changelogOwnsOnlyTheAdapterSchemaAndBothDerivedTables() throws Exception {
        try (InputStream stream = getClass().getClassLoader()
                .getResourceAsStream("db/changelog/warehouse-search-master.xml")) {
            assertTrue(stream != null);
            String xml = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(xml.contains("ai_warehouse_search"));
            assertTrue(xml.contains("item_search_index"));
            assertTrue(xml.contains("item_search_sync_state"));
            assertTrue(xml.contains("vector(1024)"));
            assertTrue(xml.contains("pg_trgm"));
            assertTrue(!xml.contains("ai_knowledge") && !xml.contains("wh_stock_balance"));
        }
    }
}
