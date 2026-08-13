package com.idavy.drtops.metrics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record OperationsDashboard(
        LocalDate operatingDate,
        LocalDate rangeStart,
        LocalDate rangeEnd,
        CoreMetrics coreMetrics,
        List<TrendPoint> trend,
        Distributions distributions,
        OffsetDateTime generatedAt) {

    public OperationsDashboard {
        trend = List.copyOf(trend);
    }

    public enum MetricStatus {
        NORMAL,
        HIGH,
        LOW,
        NO_BASELINE,
        NO_DATA
    }

    public record OrderVolume(
            long count,
            BigDecimal baseline,
            BigDecimal changeRate,
            MetricStatus status) {
    }

    public record TaskCompletion(
            long completed,
            long total,
            BigDecimal rate,
            BigDecimal baselineRate,
            MetricStatus status) {
    }

    public record AverageWait(
            BigDecimal minutes,
            long sampleCount,
            BigDecimal baselineMinutes,
            BigDecimal changeRate,
            MetricStatus status) {
    }

    public record VehicleUtilization(
            long utilized,
            long available,
            BigDecimal rate,
            BigDecimal baselineRate,
            MetricStatus status) {
    }

    public record CoreMetrics(
            OrderVolume orderVolume,
            TaskCompletion taskCompletion,
            AverageWait averageWait,
            VehicleUtilization vehicleUtilization) {
    }

    public record TrendPoint(
            LocalDate date,
            long orderCount,
            long completedTasks,
            long totalTasks,
            BigDecimal taskCompletionRate,
            BigDecimal averageWaitMinutes,
            long waitSampleCount,
            long utilizedVehicles,
            long availableVehicles,
            BigDecimal vehicleUtilizationRate) {
    }

    public record DistributionItem(
            String key,
            String label,
            long count,
            BigDecimal rate) {
    }

    public record Distributions(
            List<DistributionItem> orders,
            List<DistributionItem> tasks,
            List<DistributionItem> vehicles) {

        public Distributions {
            orders = List.copyOf(orders);
            tasks = List.copyOf(tasks);
            vehicles = List.copyOf(vehicles);
        }
    }
}
