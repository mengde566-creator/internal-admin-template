package com.internaladmin.module.agent.warehouse.evaluation;

import com.internaladmin.module.ai.observability.api.AiEvaluationDatasetProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Warehouse adapter's versioned evaluation dataset and its co-located resources. */
@Component
@ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
public final class WarehouseEvaluationDatasetProvider implements AiEvaluationDatasetProvider {
    private static final String DATASET = "evaluation/warehouse/warehouse-agent-evaluation-manifest-v1.json";
    private static final String CASES = "evaluation/warehouse/agent-behavior-exception-evaluation-v1.json";
    private static final String CONFIG = "evaluation/warehouse/agent-evaluation-config-v1.json";
    private static final Map<String, String> RESOURCES = Map.of(
            "evaluation/warehouse/warehouse-recall-evaluation-v1.json", "evaluation/warehouse/warehouse-recall-evaluation-v1.json",
            "evaluation/warehouse/warehouse-embedding-recall-baseline-v1.json", "evaluation/warehouse/warehouse-embedding-recall-baseline-v1.json",
            "evaluation/warehouse/knowledge-query-evaluation-v1.json", "evaluation/warehouse/knowledge-query-evaluation-v1.json",
            "evaluation/warehouse/knowledge-dashscope-densesparse-baseline-v1.json", "evaluation/warehouse/knowledge-dashscope-densesparse-baseline-v1.json",
            "evaluation/warehouse/agent-behavior-exception-evaluation-v1.json", CASES);

    @Override public String providerId() { return "warehouse-agent-adapter"; }
    @Override public String datasetVersion() { return "warehouse-agent-evaluation-v1"; }
    @Override public Resource manifest() { return classpath(DATASET); }
    @Override public Resource cases() { return classpath(CASES); }
    @Override public Resource config() { return classpath(CONFIG); }
    @Override public Resource resource(String path) {
        String mapped = RESOURCES.get(path);
        if (mapped == null) throw new IllegalStateException("AI_EVALUATION_RESOURCE_MISSING");
        return classpath(mapped);
    }
    private static Resource classpath(String path) { return new ClassPathResource(path); }
}
