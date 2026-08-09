package com.idavy.drtops.domain.dispatch;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record TaskInsertionPlan(
        boolean feasible,
        String rejectionReason,
        List<PlannedTaskStop> orderedStops,
        int boardingIndex,
        int alightingIndex,
        int estimatedWaitMinutes,
        int maxPassengerDetourMinutes,
        int peakOccupiedSeats,
        BigDecimal utilizationAfterInsert,
        int baselineRouteDurationSeconds,
        int plannedRouteDurationSeconds,
        int taskDisruptionScore,
        boolean degraded,
        String degradedReason) {

    public record PlannedTaskStop(
            UUID virtualStopId,
            UUID rideOrderId,
            String stopType,
            OffsetDateTime plannedArrivalAt,
            UUID existingStopId) {
    }
}
