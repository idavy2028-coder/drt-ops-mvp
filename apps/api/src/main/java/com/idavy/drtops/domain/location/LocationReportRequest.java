package com.idavy.drtops.domain.location;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record LocationReportRequest(
        UUID vehicleTaskId,
        UUID taskStopId,
        UUID virtualStopId,
        @NotNull LocationEventType eventType,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal longitude,
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal latitude,
        @NotBlank String standardizedAddress,
        @NotNull OffsetDateTime driverReportedAt,
        String note,
        String correctionReason,
        UUID correctsEventId,
        @NotNull UUID idempotencyKey) {
}
