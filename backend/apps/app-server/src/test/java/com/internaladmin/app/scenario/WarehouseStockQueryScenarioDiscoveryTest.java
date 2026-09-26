package com.internaladmin.app.scenario;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WarehouseStockQueryScenarioDiscoveryTest {

    @Test
    @DisplayName("场景资源模式零匹配时必须失败")
    void noMatchingResourceFails() {
        AssertionError error = assertThrows(AssertionError.class,
                () -> WarehouseStockQueryScenarioIT.discoverResources(
                        "classpath*:/evaluation/**/nonexistent-scenario-v999999.json"));
        assertTrue(error.getMessage().contains("没有匹配的场景资源"));
    }
}
