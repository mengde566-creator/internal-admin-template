package com.internaladmin.module.ai.observability.api;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Test-only generic provider fixture for the core contract tests. */
final class TestEvaluationDatasetProvider implements AiEvaluationDatasetProvider {
    private static final String DATASET_VERSION = "test-dataset-v1";
    private static final String CONFIG_VERSION = "test-config-v1";
    private static final String RESOURCE_PATH = "evaluation/test/fixture.json";
    private static final String CASES = "{\"datasetVersion\":\"" + DATASET_VERSION + "\",\"cases\":["
            + caseJson("case-a-calibration", "CAT_A", "calibration") + ","
            + caseJson("case-a-holdout", "CAT_A", "holdout") + ","
            + caseJson("case-b-calibration", "CAT_B", "calibration") + ","
            + caseJson("case-b-holdout", "CAT_B", "holdout") + "]}";
    private static final String CONFIG_CANONICAL = String.join("|", DATASET_VERSION, CONFIG_VERSION,
            "DETERMINISTIC_FIXTURE", "test-rules-v1", "test-knowledge-v1", "test-index-v1", "test-model-v1");
    private static final String CONFIG = "{\"datasetVersion\":\"" + DATASET_VERSION + "\",\"configVersion\":\""
            + CONFIG_VERSION + "\",\"executionMode\":\"DETERMINISTIC_FIXTURE\",\"ruleVersion\":\"test-rules-v1\","
            + "\"knowledgeVersion\":\"test-knowledge-v1\",\"indexVersion\":\"test-index-v1\",\"modelVersion\":\"test-model-v1\","
            + "\"configSha256\":\"" + sha256(CONFIG_CANONICAL) + "\"}";
    private static final String MANIFEST = "{\"datasetVersion\":\"" + DATASET_VERSION + "\",\"datasetSha256\":\""
            + sha256(CASES) + "\",\"manifestVersion\":\"1\",\"resources\":[{\"path\":\"" + RESOURCE_PATH
            + "\",\"sha256\":\"" + sha256(CASES) + "\",\"responsibility\":\"generic fixture\"}],"
            + "\"categories\":[\"CAT_A\",\"CAT_B\"],\"caseCount\":4,\"splits\":{\"calibration\":2,\"holdout\":2}}";

    @Override public String providerId() { return "test-provider"; }
    @Override public String datasetVersion() { return DATASET_VERSION; }
    @Override public Resource manifest() { return bytes(MANIFEST); }
    @Override public Resource cases() { return bytes(CASES); }
    @Override public Resource config() { return bytes(CONFIG); }
    @Override public Resource resource(String path) {
        if (!RESOURCE_PATH.equals(path)) throw new IllegalStateException("AI_EVALUATION_RESOURCE_MISSING");
        return bytes(CASES);
    }

    private static String caseJson(String id, String category, String split) {
        return "{\"caseId\":\"" + id + "\",\"category\":\"" + category + "\",\"split\":\"" + split
                + "\",\"executionMode\":\"PUBLIC_SERVICE_DETERMINISTIC\",\"preconditionsRef\":\"fixture\","
                + "\"steps\":[{\"text\":\"execute\"}],\"requiresProviderRouting\":false,\"postRoutingSteps\":[],"
                + "\"expectedBusinessOutcome\":\"ANSWERED\",\"expectedRunStatus\":\"COMPLETE\","
                + "\"expectedStableCode\":\"SUCCESS\",\"expectedToolSequence\":[],\"forbiddenTools\":[],"
                + "\"expectedCitation\":{\"documentCode\":\"\",\"versionCode\":\"\"},"
                + "\"privacyAssertions\":[\"safe\"],\"maxProviderCalls\":0}";
    }

    private static Resource bytes(String value) {
        return new ByteArrayResource(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte current : digest) result.append(String.format("%02x", current));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
