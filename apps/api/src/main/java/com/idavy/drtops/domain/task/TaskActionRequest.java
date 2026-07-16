package com.idavy.drtops.domain.task;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record TaskActionRequest(
        @NotNull @Valid TaskLocationReportRequest locationReport) {
}
