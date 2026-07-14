package com.idavy.drtops.metrics;

import com.idavy.drtops.domain.dispatch.DispatchDecision;
import com.idavy.drtops.domain.dispatch.DispatchDecisionRepository;
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

    public OperationsMetricsService(
            RideOrderRepository rideOrderRepository,
            DispatchDecisionRepository dispatchDecisionRepository,
            VehicleTaskRepository vehicleTaskRepository) {
        this.rideOrderRepository = rideOrderRepository;
        this.dispatchDecisionRepository = dispatchDecisionRepository;
        this.vehicleTaskRepository = vehicleTaskRepository;
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
