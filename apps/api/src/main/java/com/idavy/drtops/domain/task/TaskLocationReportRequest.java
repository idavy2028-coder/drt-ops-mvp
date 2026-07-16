package com.idavy.drtops.domain.task;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TaskLocationReportRequest(
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal longitude,
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal latitude,
        @NotBlank @Size(max = 300) String standardizedAddress,
        @NotNull OffsetDateTime driverReportedAt,
        UUID virtualStopId,
        @Size(max = 500) String note,
        @NotNull UUID idempotencyKey) {
}
