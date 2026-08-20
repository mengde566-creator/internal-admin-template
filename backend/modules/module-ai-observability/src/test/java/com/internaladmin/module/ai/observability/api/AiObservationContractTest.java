package com.internaladmin.module.ai.observability.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AiObservationContractTest {
    @Test
    void recorderContractAcceptsOnlyRunStepMetadata() throws Exception {
        var method = AiObservationRecorder.class.getMethod("record", String.class, String.class,
                String.class, long.class, String.class, Integer.class, Integer.class);
        assertTrue(method.getParameterCount() == 7);
        assertTrue(method.getParameterTypes()[0] == String.class);
        assertTrue(AiObservationRecorder.class.getMethod("recordAttempt", String.class, String.class,
                String.class, int.class, long.class, String.class, Integer.class, Integer.class).getParameterCount() == 8);
        assertTrue(AiObservationRecorder.class.getMethod("finishRun", String.class, String.class,
                String.class).getParameterCount() == 3);
    }
}
