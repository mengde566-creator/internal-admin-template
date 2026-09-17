package com.internaladmin.module.ai.observability.service;

import com.internaladmin.module.ai.observability.api.AiEvaluationApi;
import com.internaladmin.module.ai.observability.api.AiEvaluationDatasetProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Deterministic dataset/config registry. Resources must come from the provider JAR. */
public final class AiEvaluationDatasetRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiEvaluationDatasetRegistry.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final List<RegisteredDataset> datasets;

    public AiEvaluationDatasetRegistry(List<AiEvaluationDatasetProvider> providers) {
        List<AiEvaluationDatasetProvider> ordered = providers == null ? List.of() : providers.stream()
                .filter(Objects::nonNull).sorted(Comparator.comparing(AiEvaluationDatasetProvider::providerId)).toList();
        Set<String> providerIds = new HashSet<>();
        Set<String> versions = new HashSet<>();
        Set<String> configVersions = new HashSet<>();
        List<RegisteredDataset> result = new ArrayList<>();
        for (AiEvaluationDatasetProvider provider : ordered) {
            if (blank(provider.providerId()) || !providerIds.add(provider.providerId())) invalid("PROVIDER");
            if (blank(provider.datasetVersion()) || !versions.add(provider.datasetVersion())) invalid("DATASET");
            JsonNode manifest = parse(provider.manifest());
            JsonNode config = parse(provider.config());
            String manifestVersion = text(manifest, "datasetVersion");
            if (!provider.datasetVersion().equals(manifestVersion)) invalid("DATASET_VERSION");
            String configVersion = text(config, "configVersion");
            if (!configVersions.add(configVersion)) invalid("CONFIG_DUPLICATE");
            String configDatasetVersion = config.path("datasetVersion").asText(provider.datasetVersion());
            if (!provider.datasetVersion().equals(configDatasetVersion)) invalid("CONFIG_DATASET_MISMATCH");
            String configHash = text(config, "configSha256");
            String configCanonical = String.join("|", configDatasetVersion, configVersion,
                    config.path("executionMode").asText(), config.path("ruleVersion").asText(),
                    config.path("knowledgeVersion").asText(), config.path("indexVersion").asText(),
                    config.path("modelVersion").asText());
            if (!configHash.equals(sha256(configCanonical))) invalid("CONFIG_HASH");
            int caseCount = manifest.path("caseCount").asInt(0);
            if (caseCount < 1) invalid("CASE_COUNT");
            List<String> categories = textList(manifest.path("categories"));
            if (categories.isEmpty()) invalid("CATEGORIES");
            JsonNode resources = manifest.get("resources");
            if (resources == null || !resources.isArray() || resources.isEmpty()) invalid("RESOURCES");
            Set<String> paths = new LinkedHashSet<>();
            for (JsonNode node : resources) {
                String path = text(node, "path");
                String expected = text(node, "sha256");
                if (!paths.add(path)) invalid("RESOURCE_DUPLICATE");
                Resource resource = provider.resource(path);
                if (resource == null || !resource.isReadable() || !expected.equals(sha256(read(resource)))) invalid("RESOURCE_HASH");
            }
            String manifestHash = text(manifest, "datasetSha256");
            Resource cases = provider.cases();
            if (!manifestHash.equals(sha256(read(cases)))) invalid("CASES_HASH");
            result.add(new RegisteredDataset(provider, provider.datasetVersion(), manifestHash, configVersion, configHash,
                    caseCount, categories, List.copyOf(paths),
                    config.path("executionMode").asText(), config.path("ruleVersion").asText(),
                    config.path("knowledgeVersion").asText(), config.path("modelVersion").asText(),
                    config.path("indexVersion").asText()));
            LOGGER.info("ai_evaluation_dataset stage=registration providerId={} datasetVersion={} configVersion={} caseCount={} status=REGISTERED",
                    provider.providerId(), provider.datasetVersion(), configVersion, manifest.path("caseCount").asInt());
            LOGGER.info("ai_evaluation_dataset stage=resource_validation providerId={} datasetVersion={} resourceCount={} status=VALIDATED",
                    provider.providerId(), provider.datasetVersion(), paths.size());
        }
        this.datasets = List.copyOf(result);
    }

    public RegisteredDataset find(String datasetVersion, String configVersion) {
        return datasets.stream().filter(item -> item.datasetVersion().equals(datasetVersion)
                && item.configVersion().equals(configVersion)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("评测数据集与运行配置不匹配"));
    }

    public List<RegisteredDataset> datasets() { return datasets; }

    private static JsonNode parse(Resource resource) {
        if (resource == null || !resource.isReadable()) invalid("RESOURCE");
        try (InputStream input = resource.getInputStream()) { return JSON.readTree(input.readAllBytes()); }
        catch (IOException | RuntimeException exception) { invalid("JSON"); return null; }
    }
    private static String read(Resource resource) {
        if (resource == null || !resource.isReadable()) invalid("RESOURCE");
        try (InputStream input = resource.getInputStream()) { return new String(input.readAllBytes(), StandardCharsets.UTF_8); }
        catch (IOException exception) { invalid("READ"); return ""; }
    }
    private static String sha256(String value) {
        try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder result = new StringBuilder(64); for (byte b : digest) result.append(String.format("%02x", b)); return result.toString(); }
        catch (Exception exception) { throw new IllegalStateException("AI_EVALUATION_HASH_FAILED", exception); }
    }
    private static String text(JsonNode node, String name) { String value = node.path(name).asText(); if (blank(value)) invalid("FIELD"); return value; }
    private static List<String> textList(JsonNode node) {
        if (!node.isArray()) invalid("CATEGORIES");
        Set<String> seen = new HashSet<>();
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText();
            if (blank(value) || !seen.add(value)) invalid("CATEGORIES");
            result.add(value);
        }
        return List.copyOf(result);
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void invalid(String reason) { throw new IllegalStateException("AI_EVALUATION_DATASET_" + reason + "_INVALID"); }

    public record RegisteredDataset(AiEvaluationDatasetProvider provider, String datasetVersion, String manifestSha256,
                                    String configVersion, String configSha256, int caseCount, List<String> categories,
                                    List<String> referencedResources, String executionMode, String ruleVersion,
                                    String knowledgeVersion, String modelVersion, String indexVersion) {
    }
}
