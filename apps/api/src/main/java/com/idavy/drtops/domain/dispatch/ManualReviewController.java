package com.idavy.drtops.domain.dispatch;

import com.idavy.drtops.common.ApiResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dispatch-decisions")
public class ManualReviewController {

    private final ManualReviewService manualReviewService;

    public ManualReviewController(ManualReviewService manualReviewService) {
        this.manualReviewService = manualReviewService;
    }

    @PostMapping("/{decisionId}/approve")
    ApiResponse<DispatchResult> approve(@PathVariable UUID decisionId) {
        return ApiResponse.ok(manualReviewService.approve(decisionId));
    }

    @PostMapping("/{decisionId}/reject")
    ApiResponse<DispatchResult> reject(@PathVariable UUID decisionId, @RequestBody ReasonRequest request) {
        return ApiResponse.ok(manualReviewService.reject(decisionId, request.reason()));
    }

    public record ReasonRequest(String reason) {
    }
}
