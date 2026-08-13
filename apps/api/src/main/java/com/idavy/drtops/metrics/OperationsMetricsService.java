package com.idavy.drtops.metrics;

import com.idavy.drtops.domain.dispatch.DispatchDecision;
import com.idavy.drtops.domain.dispatch.DispatchDecisionRepository;
import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import com.idavy.drtops.domain.order.OrderStatus;
import com.idavy.drtops.domain.order.RideOrder;
import com.idavy.drtops.domain.order.RideOrderRepository;
import com.idavy.drtops.domain.task.TaskStatus;
import com.idavy.drtops.domain.task.VehicleTask;
import com.idavy.drtops.domain.task.VehicleTaskRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationsMetricsService {

    private static final int RATE_SCALE = 4;
    private static final int MINUTES_SCALE = 2;
    static final ZoneId OPERATING_ZONE = ZoneId.of("Asia/Shanghai");

    private final RideOrderRepository rideOrderRepository;
    private final DispatchDecisionRepository dispatchDecisionRepository;
    private final VehicleTaskRepository vehicleTaskRepository;
    private final VehicleRepository vehicleRepository;

    public OperationsMetricsService(
            RideOrderRepository rideOrderRepository,
            DispatchDecisionRepository dispatchDecisionRepository,
            VehicleTaskRepository vehicleTaskRepository,
            VehicleRepository vehicleRepository) {
        this.rideOrderRepository = rideOrderRepository;
        this.dispatchDecisionRepository = dispatchDecisionRepository;
        this.vehicleTaskRepository = vehicleTaskRepository;
        this.vehicleRepository = vehicleRepository;
    }

    @Transactional(readOnly = true)
    public OperationsSummary calculateSummary(LocalDate operatingDate) {
        LocalDate metricsDate = operatingDate == null ? LocalDate.now(OPERATING_ZONE) : operatingDate;
        List<RideOrder> orders = rideOrderRepository.findAll().stream()
                .filter(order -> operatingDateOf(order.getRequestedDepartureAt()).equals(metricsDate))
                .toList();
        Set<UUID> orderIds = orders.stream()
                .map(RideOrder::getId)
                .collect(java.util.stream.Collectors.toSet());
        List<DispatchDecision> decisions = dispatchDecisionRepository.findAll().stream()
                .filter(decision -> orderIds.contains(decision.getRideOrderId()))
                .toList();
        List<VehicleTask> tasks = vehicleTaskRepository.findAll().stream()
                .filter(task -> operatingDateOf(task.getPlannedStartAt()).equals(metricsDate))
                .toList();
        long orderCount = orders.size();

        return new OperationsSummary(
                orderCount,
                ratio(countConfirmedOrders(orders), orderCount),
                ratio(countDecisions(decisions, "AUTO_DISPATCH"), orderCount),
                ratio(countManualReviewDecisions(decisions), orderCount),
                average(decisions.stream()
                        .map(DispatchDecision::getEstimatedWaitMinutes)
                        .filter(Objects::nonNull)
                        .toList()),
                average(decisions.stream()
                        .map(DispatchDecision::getEstimatedDetourMinutes)
                        .filter(Objects::nonNull)
                        .toList()),
                ratio(countCompletedTasks(tasks), tasks.size()),
                ratio(countExceptionClosedOrders(orders), orderCount),
                ratio(countUtilizedVehicles(tasks), countTaskVehicles(tasks)));
    }

    @Transactional(readOnly = true)
    public OperationsDashboard calculateDashboard(LocalDate endDate, int days) {
        if (days != 7) {
            throw new IllegalArgumentException("days must be 7");
        }
        LocalDate operatingDate = endDate == null ? LocalDate.now(OPERATING_ZONE) : endDate;
        LocalDate rangeStart = operatingDate.minusDays(days - 1L);
        LocalDate baselineStart = operatingDate.minusDays(days);
        LocalDate baselineEnd = operatingDate.minusDays(1);

        List<RideOrder> orders = rideOrderRepository.findAll();
        List<DispatchDecision> decisions = dispatchDecisionRepository.findAll();
        List<VehicleTask> tasks = vehicleTaskRepository.findAll();
        List<Vehicle> vehicles = vehicleRepository.findAll();
        long availableVehicles = vehicles.stream().filter(Vehicle::isDispatchable).count();

        List<OperationsDashboard.TrendPoint> trend = IntStream.range(0, days)
                .mapToObj(index -> dailyMetrics(
                        rangeStart.plusDays(index),
                        orders,
                        decisions,
                        tasks,
                        availableVehicles))
                .map(DailyMetrics::toTrendPoint)
                .toList();

        DailyMetrics current = dailyMetrics(operatingDate, orders, decisions, tasks, availableVehicles);
        List<RideOrder> baselineOrders = orders.stream()
                .filter(order -> isWithin(
                        operatingDateOf(order.getRequestedDepartureAt()),
                        baselineStart,
                        baselineEnd))
                .toList();
        Set<UUID> baselineOrderIds = baselineOrders.stream()
                .map(RideOrder::getId)
                .collect(java.util.stream.Collectors.toSet());
        List<DispatchDecision> baselineDecisions = decisions.stream()
                .filter(decision -> baselineOrderIds.contains(decision.getRideOrderId()))
                .toList();
        List<VehicleTask> baselineTasks = tasks.stream()
                .filter(task -> isWithin(operatingDateOf(task.getPlannedStartAt()), baselineStart, baselineEnd))
                .toList();

        BigDecimal orderBaseline = averageCount(baselineOrders.size(), days);
        BigDecimal completionBaseline = nullableRatio(countCompletedTasks(baselineTasks), baselineTasks.size());
        List<Integer> baselineWaitSamples = baselineDecisions.stream()
                .map(DispatchDecision::getEstimatedWaitMinutes)
                .filter(Objects::nonNull)
                .toList();
        BigDecimal waitBaseline = nullableAverage(baselineWaitSamples);
        long utilizedVehicleDays = IntStream.range(0, days)
                .mapToLong(index -> countUtilizedVehicles(tasksForDate(
                        tasks,
                        baselineStart.plusDays(index))))
                .sum();
        BigDecimal utilizationBaseline = nullableRatio(
                utilizedVehicleDays,
                availableVehicles * days);

        OperationsDashboard.CoreMetrics coreMetrics = new OperationsDashboard.CoreMetrics(
                new OperationsDashboard.OrderVolume(
                        current.orderCount(),
                        orderBaseline,
                        relativeChange(BigDecimal.valueOf(current.orderCount()), orderBaseline),
                        relativeStatus(
                                BigDecimal.valueOf(current.orderCount()),
                                orderBaseline,
                                new BigDecimal("0.10"))),
                new OperationsDashboard.TaskCompletion(
                        current.completedTasks(),
                        current.totalTasks(),
                        current.taskCompletionRate(),
                        completionBaseline,
                        pointStatus(
                                current.taskCompletionRate(),
                                completionBaseline,
                                new BigDecimal("0.03"))),
                new OperationsDashboard.AverageWait(
                        current.averageWaitMinutes(),
                        current.waitSampleCount(),
                        waitBaseline,
                        relativeChange(current.averageWaitMinutes(), waitBaseline),
                        relativeStatus(
                                current.averageWaitMinutes(),
                                waitBaseline,
                                new BigDecimal("0.10"))),
                new OperationsDashboard.VehicleUtilization(
                        current.utilizedVehicles(),
                        current.availableVehicles(),
                        current.vehicleUtilizationRate(),
                        utilizationBaseline,
                        pointStatus(
                                current.vehicleUtilizationRate(),
                                utilizationBaseline,
                                new BigDecimal("0.05"))));

        List<RideOrder> currentOrders = ordersForDate(orders, operatingDate);
        List<VehicleTask> currentTasks = tasksForDate(tasks, operatingDate);
        OperationsDashboard.Distributions distributions = new OperationsDashboard.Distributions(
                orderDistribution(currentOrders),
                taskDistribution(currentTasks),
                vehicleDistribution(vehicles));

        return new OperationsDashboard(
                operatingDate,
                rangeStart,
                operatingDate,
                coreMetrics,
                trend,
                distributions,
                java.time.OffsetDateTime.now(OPERATING_ZONE));
    }

    private DailyMetrics dailyMetrics(
            LocalDate date,
            List<RideOrder> allOrders,
            List<DispatchDecision> allDecisions,
            List<VehicleTask> allTasks,
            long availableVehicles) {
        List<RideOrder> orders = ordersForDate(allOrders, date);
        Set<UUID> orderIds = orders.stream()
                .map(RideOrder::getId)
                .collect(java.util.stream.Collectors.toSet());
        List<Integer> waitSamples = allDecisions.stream()
                .filter(decision -> orderIds.contains(decision.getRideOrderId()))
                .map(DispatchDecision::getEstimatedWaitMinutes)
                .filter(Objects::nonNull)
                .toList();
        List<VehicleTask> tasks = tasksForDate(allTasks, date);
        long completedTasks = countCompletedTasks(tasks);
        long utilizedVehicles = countUtilizedVehicles(tasks);
        return new DailyMetrics(
                date,
                orders.size(),
                completedTasks,
                tasks.size(),
                nullableRatio(completedTasks, tasks.size()),
                nullableAverage(waitSamples),
                waitSamples.size(),
                utilizedVehicles,
                availableVehicles,
                nullableRatio(utilizedVehicles, availableVehicles));
    }

    private List<RideOrder> ordersForDate(List<RideOrder> orders, LocalDate date) {
        return orders.stream()
                .filter(order -> operatingDateOf(order.getRequestedDepartureAt()).equals(date))
                .toList();
    }

    private List<VehicleTask> tasksForDate(List<VehicleTask> tasks, LocalDate date) {
        return tasks.stream()
                .filter(task -> operatingDateOf(task.getPlannedStartAt()).equals(date))
                .toList();
    }

    private boolean isWithin(LocalDate date, LocalDate start, LocalDate end) {
        return !date.isBefore(start) && !date.isAfter(end);
    }

    private List<OperationsDashboard.DistributionItem> orderDistribution(List<RideOrder> orders) {
        long total = orders.size();
        return List.of(
                distributionItem(
                        "PENDING",
                        "待处理",
                        countOrders(orders, status -> status == OrderStatus.PENDING_DISPATCH
                                || status == OrderStatus.PENDING_MANUAL_REVIEW),
                        total),
                distributionItem(
                        "IN_PROGRESS",
                        "执行中",
                        countOrders(orders, status -> status == OrderStatus.CONFIRMED
                                || status == OrderStatus.IN_PROGRESS),
                        total),
                distributionItem(
                        "COMPLETED",
                        "已完成",
                        countOrders(orders, status -> status == OrderStatus.COMPLETED),
                        total),
                distributionItem(
                        "EXCEPTION_CANCELLED",
                        "异常 / 取消",
                        countOrders(orders, status -> status == OrderStatus.UNSERVICEABLE
                                || status == OrderStatus.CANCELLED
                                || status == OrderStatus.EXCEPTION_CLOSED),
                        total));
    }

    private List<OperationsDashboard.DistributionItem> taskDistribution(List<VehicleTask> tasks) {
        long total = tasks.size();
        return List.of(
                distributionItem(
                        "PENDING_DEPARTURE",
                        "待发车",
                        countTasks(tasks, status -> status == TaskStatus.PENDING_DEPARTURE
                                || status == TaskStatus.DISPATCHED),
                        total),
                distributionItem(
                        "IN_PROGRESS",
                        "执行中",
                        countTasks(tasks, status -> status == TaskStatus.IN_PROGRESS
                                || status == TaskStatus.PAUSED),
                        total),
                distributionItem(
                        "COMPLETED",
                        "已完成",
                        countTasks(tasks, status -> status == TaskStatus.COMPLETED),
                        total),
                distributionItem(
                        "EXCEPTION_CANCELLED",
                        "异常 / 取消",
                        countTasks(tasks, status -> status == TaskStatus.EXCEPTION
                                || status == TaskStatus.CANCELLED),
                        total));
    }

    private List<OperationsDashboard.DistributionItem> vehicleDistribution(List<Vehicle> vehicles) {
        long total = vehicles.size();
        long inService = vehicles.stream()
                .filter(Vehicle::isDispatchable)
                .filter(vehicle -> "IN_SERVICE".equals(vehicle.getCurrentStatus())
                        || "DISPATCHED".equals(vehicle.getCurrentStatus()))
                .count();
        long idle = vehicles.stream()
                .filter(Vehicle::isDispatchable)
                .filter(vehicle -> "IDLE".equals(vehicle.getCurrentStatus()))
                .count();
        long unavailable = total - inService - idle;
        return List.of(
                distributionItem("IN_SERVICE", "执行中", inService, total),
                distributionItem("IDLE", "空闲", idle, total),
                distributionItem("UNAVAILABLE", "异常 / 不可用", unavailable, total));
    }

    private long countOrders(List<RideOrder> orders, Predicate<OrderStatus> predicate) {
        return orders.stream().map(RideOrder::getStatus).filter(predicate).count();
    }

    private long countTasks(List<VehicleTask> tasks, Predicate<TaskStatus> predicate) {
        return tasks.stream().map(VehicleTask::getStatus).filter(predicate).count();
    }

    private OperationsDashboard.DistributionItem distributionItem(
            String key,
            String label,
            long count,
            long total) {
        return new OperationsDashboard.DistributionItem(key, label, count, nullableRatio(count, total));
    }

    private BigDecimal averageCount(long count, int days) {
        return BigDecimal.valueOf(count)
                .divide(BigDecimal.valueOf(days), MINUTES_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal nullableRatio(long numerator, long denominator) {
        return denominator == 0 ? null : ratio(numerator, denominator);
    }

    private BigDecimal nullableAverage(List<Integer> values) {
        return values.isEmpty() ? null : average(values);
    }

    private BigDecimal relativeChange(BigDecimal current, BigDecimal baseline) {
        if (current == null || baseline == null || baseline.signum() == 0) {
            return null;
        }
        return current.subtract(baseline)
                .divide(baseline, RATE_SCALE, RoundingMode.HALF_UP);
    }

    private OperationsDashboard.MetricStatus relativeStatus(
            BigDecimal current,
            BigDecimal baseline,
            BigDecimal tolerance) {
        if (current == null || baseline == null) {
            return OperationsDashboard.MetricStatus.NO_BASELINE;
        }
        if (baseline.signum() == 0) {
            return current.signum() == 0
                    ? OperationsDashboard.MetricStatus.NORMAL
                    : OperationsDashboard.MetricStatus.HIGH;
        }
        return pointStatus(relativeChange(current, baseline), BigDecimal.ZERO, tolerance);
    }

    private OperationsDashboard.MetricStatus pointStatus(
            BigDecimal current,
            BigDecimal baseline,
            BigDecimal tolerance) {
        if (current == null || baseline == null) {
            return OperationsDashboard.MetricStatus.NO_BASELINE;
        }
        BigDecimal difference = current.subtract(baseline);
        if (difference.compareTo(tolerance) > 0) {
            return OperationsDashboard.MetricStatus.HIGH;
        }
        if (difference.compareTo(tolerance.negate()) < 0) {
            return OperationsDashboard.MetricStatus.LOW;
        }
        return OperationsDashboard.MetricStatus.NORMAL;
    }

    private record DailyMetrics(
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

        OperationsDashboard.TrendPoint toTrendPoint() {
            return new OperationsDashboard.TrendPoint(
                    date,
                    orderCount,
                    completedTasks,
                    totalTasks,
                    taskCompletionRate,
                    averageWaitMinutes,
                    waitSampleCount,
                    utilizedVehicles,
                    availableVehicles,
                    vehicleUtilizationRate);
        }
    }

    private LocalDate operatingDateOf(OffsetDateTime timestamp) {
        return timestamp.atZoneSameInstant(OPERATING_ZONE).toLocalDate();
    }

    private long countConfirmedOrders(List<RideOrder> orders) {
        return orders.stream()
                .filter(order -> order.getStatus() == OrderStatus.CONFIRMED
                        || order.getStatus() == OrderStatus.IN_PROGRESS
                        || order.getStatus() == OrderStatus.COMPLETED
                        || order.getStatus() == OrderStatus.PENDING_MANUAL_REVIEW)
                .count();
    }

    private long countDecisions(List<DispatchDecision> decisions, String decisionResult) {
        return decisions.stream()
                .filter(decision -> decisionResult.equals(decision.getDecisionResult()))
                .count();
    }

    private long countManualReviewDecisions(List<DispatchDecision> decisions) {
        return decisions.stream()
                .filter(decision -> "MANUAL_REVIEW".equals(decision.getDecisionResult())
                        || "PENDING_MANUAL_REVIEW".equals(decision.getDecisionResult()))
                .count();
    }

    private long countCompletedTasks(List<VehicleTask> tasks) {
        return tasks.stream()
                .filter(task -> task.getStatus() == TaskStatus.COMPLETED)
                .count();
    }

    private long countExceptionClosedOrders(List<RideOrder> orders) {
        return orders.stream()
                .filter(order -> order.getStatus() == OrderStatus.EXCEPTION_CLOSED)
                .count();
    }

    private long countUtilizedVehicles(List<VehicleTask> tasks) {
        return tasks.stream()
                .filter(task -> task.getStatus() != TaskStatus.CANCELLED && task.getStatus() != TaskStatus.EXCEPTION)
                .map(VehicleTask::getVehicleId)
                .distinct()
                .count();
    }

    private long countTaskVehicles(List<VehicleTask> tasks) {
        return tasks.stream()
                .map(VehicleTask::getVehicleId)
                .distinct()
                .count();
    }

    private BigDecimal ratio(long numerator, long denominator) {
        if (denominator == 0) {
            return BigDecimal.ZERO.setScale(RATE_SCALE, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), RATE_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal average(List<Integer> values) {
        if (values.isEmpty()) {
            return BigDecimal.ZERO.setScale(MINUTES_SCALE, RoundingMode.HALF_UP);
        }
        long sum = values.stream().mapToLong(Integer::longValue).sum();
        return BigDecimal.valueOf(sum)
                .divide(BigDecimal.valueOf(values.size()), MINUTES_SCALE, RoundingMode.HALF_UP);
    }
}
