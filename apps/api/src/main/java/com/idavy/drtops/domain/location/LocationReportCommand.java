package com.idavy.drtops.domain.location;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record LocationReportCommand(
        UUID vehicleId,
        UUID vehicleTaskId,
        UUID taskStopId,
        UUID virtualStopId,
        LocationEventType eventType,
        BigDecimal longitude,
        BigDecimal latitude,
        String standardizedAddress,
        OffsetDateTime driverReportedAt,
        UUID recordedBy,
        String note,
        String correctionReason,
        UUID correctsEventId,
        UUID idempotencyKey) {
}
