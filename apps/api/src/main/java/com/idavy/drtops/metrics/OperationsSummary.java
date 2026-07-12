package com.idavy.drtops.metrics;

import java.math.BigDecimal;

public record OperationsSummary(
        long orderCount,
        BigDecimal confirmationRate,
        BigDecimal autoDispatchRate,
        BigDecimal manualReviewRate,
        BigDecimal averageWaitMinutes,
        BigDecimal averageDetourMinutes,
        BigDecimal taskCompletionRate,
        BigDecimal exceptionCloseRate,
        BigDecimal vehicleUtilizationRate) {
}
