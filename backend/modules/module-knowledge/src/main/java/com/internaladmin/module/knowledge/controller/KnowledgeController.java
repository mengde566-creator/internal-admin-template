package com.internaladmin.module.knowledge.controller;

import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.module.knowledge.service.KnowledgeService;
import com.internaladmin.platform.web.response.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Fixed-sample Gate A knowledge endpoints; no upload or arbitrary content input is accepted. */
@RestController
@RequestMapping("/api/ai/knowledge")
@ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
public class KnowledgeController {

    private final KnowledgeService service;

    public KnowledgeController(KnowledgeService service) {
        this.service = service;
    }

    /**
     * Import the repository-owned synthetic sample only.
     *
     * @return import counts
     */
    @PostMapping("/synthetic-import")
    @PreAuthorize("hasAuthority('ai:knowledge:manage')")
    public ApiResponse<KnowledgeService.ImportSummary> importSyntheticSamples() {
        return ApiResponse.ok(service.importSyntheticSamples());
    }

    /**
     * Query active synthetic knowledge with version references.
     *
     * @param query text query
     * @param topK bounded result count
     * @return active-version search results
     */
    @GetMapping("/search")
    @PreAuthorize("hasAuthority('" + PermissionCodes.WAREHOUSE_READ + "')")
    public ApiResponse<List<KnowledgeService.KnowledgeSearchResult>> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int topK) {
        return ApiResponse.ok(service.search(query, topK));
    }
}
