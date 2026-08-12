package com.internaladmin.app;

import com.internaladmin.module.audit.mapper.AuditOperationMapper;
import com.internaladmin.module.audit.model.entity.AuditOperationDO;
import com.internaladmin.module.audit.service.AuditRecordService;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuditRecordServiceTest {

    @Test
    void recordMapsGenericFieldsAndTimeToMapper() {
        AtomicReference<AuditOperationDO> captured = new AtomicReference<>();
        AuditOperationMapper mapper = mapperProxy((proxy, method, args) -> {
            if ("insert".equals(method.getName())) {
                captured.set((AuditOperationDO) args[0]);
                return 1;
            }
            return null;
        });
        AuditRecordService service = new AuditRecordService(mapper);

        service.record(7L, "CUSTOM_ACTION", 11L, "CUSTOM_RESULT");

        AuditOperationDO record = captured.get();
        assertEquals(7L, record.getOperatorId());
        assertEquals("CUSTOM_ACTION", record.getAction());
        assertEquals(11L, record.getTargetId());
        assertEquals("CUSTOM_RESULT", record.getResult());
        assertNotNull(record.getOccurredAt());
    }

    @Test
    void mapperFailureIsVisible() {
        AuditOperationMapper mapper = mapperProxy((proxy, method, args) -> {
            if ("insert".equals(method.getName())) {
                throw new IllegalStateException("mapper failure");
            }
            return null;
        });
        AuditRecordService service = new AuditRecordService(mapper);

        assertThrows(IllegalStateException.class,
                () -> service.record(7L, "CUSTOM_ACTION", 11L, "CUSTOM_RESULT"));
    }

    private AuditOperationMapper mapperProxy(java.lang.reflect.InvocationHandler handler) {
        return (AuditOperationMapper) Proxy.newProxyInstance(
                AuditOperationMapper.class.getClassLoader(),
                new Class<?>[]{AuditOperationMapper.class},
                handler);
    }
}
