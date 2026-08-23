package com.internaladmin.module.warehouse.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WarehouseServiceKeywordTest {
    @Test
    void keywordWildcardsAreEscapedAndRemainLiteral() {
        assertEquals("%A!%!_B!_C!!D%", WarehouseService.likePattern("A%_B_C!D"));
        assertEquals("%%", WarehouseService.likePattern(null));
    }
}
