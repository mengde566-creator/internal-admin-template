package com.internaladmin.module.agent.warehouse;

import com.internaladmin.module.agent.warehouse.evaluation.WarehouseEvaluationDatasetProvider;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that the adapter's versioned corpus and manifest stay co-located. */
class WarehouseEvaluationCorpusTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void manifestAndCasesAreLoadedFromTheAdapterResourceFamily() throws Exception {
        WarehouseEvaluationDatasetProvider provider = new WarehouseEvaluationDatasetProvider();
        JsonNode manifest = JSON.readTree(read(provider.manifest()));
        JsonNode cases = JSON.readTree(read(provider.cases()));

        assertEquals(provider.datasetVersion(), manifest.path("datasetVersion").asText());
        assertEquals(provider.datasetVersion(), cases.path("datasetVersion").asText());
        assertEquals(24, manifest.path("caseCount").asInt());
        assertEquals(manifest.path("caseCount").asInt(), cases.path("cases").size());
        assertEquals(manifest.path("datasetSha256").asText(), sha256(read(provider.cases())));
        assertEquals(12, manifest.path("splits").path("calibration").asInt());
        assertEquals(12, manifest.path("splits").path("holdout").asInt());

        Set<String> paths = new HashSet<>();
        for (JsonNode resource : manifest.path("resources")) {
            String path = resource.path("path").asText();
            assertTrue(paths.add(path), "manifest resource paths must be unique");
            Resource loaded = provider.resource(path);
            assertNotNull(loaded);
            assertTrue(loaded.isReadable(), path);
            assertEquals(resource.path("sha256").asText(), sha256(read(loaded)), path);
        }
        assertFalse(paths.isEmpty());
    }

    private static String read(Resource resource) throws IOException {
        try (InputStream input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(64);
        for (byte current : digest) {
            result.append(String.format("%02x", current));
        }
        return result.toString();
    }
}
