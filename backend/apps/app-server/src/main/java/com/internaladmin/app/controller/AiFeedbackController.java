package com.internaladmin.app.controller;

import com.internaladmin.module.ai.observability.api.AiFeedbackApi;
import com.internaladmin.module.ai.observability.api.AiObservabilityQueryApi;
import com.internaladmin.module.iam.api.PermissionCodes;
import com.internaladmin.platform.web.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** User feedback and administrator AI observation HTTP surface. */
@RestController
@ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
@RequestMapping("/api/ai")
public class AiFeedbackController {
    private final AiFeedbackApi feedback;
    private final AiObservabilityQueryApi observability;

    public AiFeedbackController(AiFeedbackApi feedback, AiObservabilityQueryApi observability) {
        this.feedback = feedback;
        this.observability = observability;
    }

    @GetMapping("/feedback/{assistantMessageId}")
    public ApiResponse<AiFeedbackApi.FeedbackSnapshot> getFeedback(@PathVariable String assistantMessageId,
                                                                     Authentication authentication) {
        return ApiResponse.ok(feedback.findForAssistantMessage(assistantMessageId, userId(authentication)).orElse(null));
    }

    @PutMapping("/feedback/{assistantMessageId}")
    public ApiResponse<AiFeedbackApi.FeedbackSnapshot> putFeedback(@PathVariable String assistantMessageId,
                                                                    @Valid @RequestBody FeedbackRequest request,
                                                                    Authentication authentication) {
        return ApiResponse.ok(feedback.upsert(userId(authentication), assistantMessageId,
                request.rating(), request.reason()));
    }

    @DeleteMapping("/feedback/{assistantMessageId}")
    public ApiResponse<Void> deleteFeedback(@PathVariable String assistantMessageId,
                                            Authentication authentication) {
        feedback.delete(userId(authentication), assistantMessageId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/observability/overview")
    @PreAuthorize("hasAuthority('" + PermissionCodes.AI_OBSERVABILITY_VIEW + "')")
    public ApiResponse<AiObservabilityQueryApi.Overview> overview(@RequestParam(required = false) Instant from,
                                                                    @RequestParam(required = false) Instant to,
                                                                    @RequestParam(required = false) List<String> status,
                                                                    @RequestParam(required = false) List<String> businessOutcome,
                                                                    @RequestParam(required = false) String errorSource,
                                                                    @RequestParam(required = false) String errorCode,
                                                                    @RequestParam(required = false) String provider,
                                                                    @RequestParam(required = false) String model,
                                                                    @RequestParam(required = false) String toolName,
                                                                    @RequestParam(required = false) String retrievalStage) {
        return ApiResponse.ok(observability.overview(filter(from, to, status, businessOutcome, errorSource,
                errorCode, provider, model, toolName, retrievalStage)));
    }

    @GetMapping("/observability/runs")
    @PreAuthorize("hasAuthority('" + PermissionCodes.AI_OBSERVABILITY_VIEW + "')")
    public ApiResponse<AiObservabilityQueryApi.RunPage> runs(@RequestParam(defaultValue = "1") long page,
                                                              @RequestParam(defaultValue = "20") long size,
                                                              @RequestParam(required = false) Instant from,
                                                              @RequestParam(required = false) Instant to,
                                                              @RequestParam(required = false) List<String> status,
                                                              @RequestParam(required = false) List<String> businessOutcome,
                                                              @RequestParam(required = false) String errorSource,
                                                              @RequestParam(required = false) String errorCode,
                                                              @RequestParam(required = false) String provider,
                                                              @RequestParam(required = false) String model,
                                                              @RequestParam(required = false) String toolName,
                                                              @RequestParam(required = false) String retrievalStage) {
        return ApiResponse.ok(observability.pageRuns(filter(from, to, status, businessOutcome, errorSource,
                errorCode, provider, model, toolName, retrievalStage), page, size));
    }

    @GetMapping("/observability/runs/{runId}")
    @PreAuthorize("hasAuthority('" + PermissionCodes.AI_OBSERVABILITY_VIEW + "')")
    public ApiResponse<AiObservabilityQueryApi.RunTimeline> run(@PathVariable String runId) {
        return ApiResponse.ok(observability.runTimeline(runId));
    }

    private static AiObservabilityQueryApi.RunFilter filter(Instant from, Instant to, List<String> statuses,
                                                              List<String> outcomes, String errorSource,
                                                              String errorCode, String provider, String model,
                                                              String toolName, String retrievalStage) {
        return new AiObservabilityQueryApi.RunFilter(from, to, statuses, outcomes, errorSource, errorCode,
                provider, model, toolName, retrievalStage);
    }

    private static Long userId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Long value)) {
            throw new AccessDeniedException("未登录");
        }
        return value;
    }

    public record FeedbackRequest(@NotBlank @Size(max = 24) String rating,
                                  @NotBlank @Size(max = 48) String reason) {
    }
}
