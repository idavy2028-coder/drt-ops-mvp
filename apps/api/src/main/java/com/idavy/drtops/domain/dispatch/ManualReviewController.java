package com.idavy.drtops.domain.dispatch;

import com.idavy.drtops.common.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dispatch-decisions")
public class ManualReviewController {

    private final ManualReviewService manualReviewService;
    private final ManualReviewQueueService manualReviewQueueService;

    public ManualReviewController(
            ManualReviewService manualReviewService,
            ManualReviewQueueService manualReviewQueueService) {
        this.manualReviewService = manualReviewService;
        this.manualReviewQueueService = manualReviewQueueService;
    }

    @GetMapping("/manual-review")
    ApiResponse<List<ManualReviewQueueItem>> listManualReviewQueue() {
        return ApiResponse.ok(manualReviewQueueService.list());
    }

    @PostMapping("/{decisionId}/approve")
    ApiResponse<DispatchResult> approve(Authentication authentication, @PathVariable UUID decisionId) {
        return ApiResponse.ok(manualReviewService.approve(actorId(authentication), decisionId));
    }

    @PostMapping("/{decisionId}/reject")
    ApiResponse<DispatchResult> reject(
            Authentication authentication, @PathVariable UUID decisionId, @RequestBody ReasonRequest request) {
        return ApiResponse.ok(manualReviewService.reject(actorId(authentication), decisionId, request.reason()));
    }

    private UUID actorId(Authentication authentication) {
        return (UUID) authentication.getPrincipal();
    }

    public record ReasonRequest(String reason) {
    }
}
