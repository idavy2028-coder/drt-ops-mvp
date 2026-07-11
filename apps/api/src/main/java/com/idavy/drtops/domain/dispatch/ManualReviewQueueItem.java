package com.idavy.drtops.domain.dispatch;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ManualReviewQueueItem(
        UUID decisionId,
        UUID orderId,
        String passengerName,
        int passengerCount,
        OffsetDateTime requestedDepartureAt,
        UUID bestVehicleId,
        int candidateCount) {
}
