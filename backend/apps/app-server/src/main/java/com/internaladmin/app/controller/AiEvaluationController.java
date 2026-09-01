package com.internaladmin.app.controller;

import com.internaladmin.module.ai.observability.api.AiEvaluationApi;
import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.platform.web.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** Bounded administrator HTTP surface for the fixed offline evaluation registry. */
@RestController
@ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
@PreAuthorize("hasAuthority('" + PermissionCodes.AI_OBSERVABILITY_VIEW + "')")
@RequestMapping("/api/ai/observability/evaluations")
public class AiEvaluationController {
    private final AiEvaluationApi evaluations;

    public AiEvaluationController(AiEvaluationApi evaluations) {
        this.evaluations = evaluations;
    }

    @GetMapping("/datasets")
    public ApiResponse<List<AiEvaluationApi.DatasetRegistration>> datasets() {
        return ApiResponse.ok(evaluations.datasets());
    }

    @GetMapping("/configs")
    public ApiResponse<List<AiEvaluationApi.RunConfiguration>> configurations() {
        return ApiResponse.ok(evaluations.configurations());
    }

    @PostMapping("/runs")
    public ApiResponse<AiEvaluationApi.EvaluationRun> start(@Valid @RequestBody StartRequest request) {
        return ApiResponse.ok(evaluations.start(request.datasetVersion(), request.configVersion(), request.clientRequestId()));
    }

    @GetMapping("/runs")
    public ApiResponse<AiEvaluationApi.EvaluationPage> runs(@RequestParam(defaultValue = "1") long page,
                                                             @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.ok(evaluations.pageRuns(page, size));
    }

    @GetMapping("/runs/{evaluationRunId}")
    public ApiResponse<AiEvaluationApi.EvaluationDetail> run(@PathVariable String evaluationRunId) {
        return ApiResponse.ok(evaluations.getRun(evaluationRunId));
    }

    public record StartRequest(@NotBlank @Size(max = 128) String datasetVersion,
                               @NotBlank @Size(max = 128) String configVersion,
                               @NotBlank @Size(max = 128) String clientRequestId) {
    }
}
