package com.internaladmin.module.warehouse.service;

import com.internaladmin.platform.kernel.error.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QuantityCodecTest {
    @Test
    void scalesAndRoundTripsExactDecimal() {
        long scaled = QuantityCodec.parse("922337203685477.5807", false, false);
        assertEquals(Long.MAX_VALUE, scaled);
        assertEquals("922337203685477.5807", QuantityCodec.format(scaled));
        assertEquals("12.34", QuantityCodec.format(QuantityCodec.parse("12.3400", false, false)));
    }

    @Test
    void rejectsPrecisionSignAndOverflow() {
        assertThrows(BusinessException.class, () -> QuantityCodec.parse("1.00001", true, false));
        assertThrows(BusinessException.class, () -> QuantityCodec.parse("0", true, false));
        assertThrows(BusinessException.class, () -> QuantityCodec.parse("-1", false, true));
        assertThrows(BusinessException.class, () -> QuantityCodec.parse("922337203685477.5808", false, false));
        assertThrows(BusinessException.class, () -> QuantityCodec.parse("-922337203685477.5808", false, false));
        assertThrows(BusinessException.class, () -> QuantityCodec.add(Long.MAX_VALUE, 1));
        assertThrows(BusinessException.class, () -> QuantityCodec.add(0, -1));
    }
}
