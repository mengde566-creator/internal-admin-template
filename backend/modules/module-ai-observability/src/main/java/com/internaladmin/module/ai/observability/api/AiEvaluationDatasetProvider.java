package com.internaladmin.module.ai.observability.api;

import org.springframework.core.io.Resource;

/** Adapter-owned evaluation resources exposed through a narrow compile-time contract. */
public interface AiEvaluationDatasetProvider {
    String providerId();

    String datasetVersion();

    Resource manifest();

    Resource cases();

    Resource config();

    Resource resource(String path);
}
