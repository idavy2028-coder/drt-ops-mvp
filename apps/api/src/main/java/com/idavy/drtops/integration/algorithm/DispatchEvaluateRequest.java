package com.idavy.drtops.integration.algorithm;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record DispatchEvaluateRequest(
        Order order,
        RuleSet ruleSet,
        List<CandidateTask> candidateTasks) {

    public record Order(
            UUID orderId,
            int passengerCount,
            String requestType,
            OffsetDateTime requestedDepartureAt,
            UUID boardingStopId,
            UUID alightingStopId) {
    }

    public record RuleSet(
            int maxWaitMinutes,
            int maxDetourMinutes,
            BigDecimal autoDispatchScoreThreshold,
            BigDecimal manualReviewScoreThreshold,
            Weights weights,
            String insertionPolicy) {
    }

    public record Weights(
            @JsonProperty("wait") BigDecimal waitWeight,
            BigDecimal detour,
            BigDecimal stability,
            BigDecimal utilization) {
    }

    public record CandidateTask(
            UUID taskId,
            UUID vehicleId,
            int availableSeats,
            UUID currentStopId,
            List<PlannedStop> plannedStops,
            int estimatedWaitMinutes,
            int estimatedDetourMinutes,
            String directionCompatibility,
            BigDecimal utilizationAfterInsert) {
    }

    public record PlannedStop(
            UUID stopId,
            int sequence,
            OffsetDateTime plannedArrivalAt,
            String stopType) {
    }
}
