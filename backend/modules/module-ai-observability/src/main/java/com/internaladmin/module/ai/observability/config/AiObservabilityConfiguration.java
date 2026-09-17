package com.internaladmin.module.ai.observability.config;

import com.internaladmin.module.ai.observability.api.AiEvaluationDatasetProvider;
import com.internaladmin.module.ai.observability.service.AiEvaluationDatasetRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registry wiring for adapter-owned evaluation datasets. */
@Configuration
@ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
public class AiObservabilityConfiguration {
    @Bean
    public AiEvaluationDatasetRegistry aiEvaluationDatasetRegistry(ObjectProvider<AiEvaluationDatasetProvider> providers) {
        return new AiEvaluationDatasetRegistry(providers.orderedStream().toList());
    }
}
