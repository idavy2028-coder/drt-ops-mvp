package com.idavy.drtops.integration.algorithm;

import com.idavy.drtops.domain.dispatch.DispatchDecisionType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record DispatchEvaluateResponse(
        DispatchDecisionType decision,
        BestPlan bestPlan,
        int candidateCount,
        int rejectedCount,
        List<RejectedCandidate> rejectedCandidates,
        Map<String, Object> explanation) {

    public record BestPlan(
            UUID taskId,
            UUID vehicleId,
            BigDecimal score,
            int estimatedWaitMinutes,
            int estimatedDetourMinutes,
            String directionCompatibility,
            BigDecimal utilizationAfterInsert) {
    }

    public record RejectedCandidate(
            UUID taskId,
            String reason) {
    }
}
