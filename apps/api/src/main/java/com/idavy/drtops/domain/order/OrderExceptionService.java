package com.idavy.drtops.domain.order;

import com.idavy.drtops.domain.audit.AuditLog;
import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.domain.location.LocationEventType;
import com.idavy.drtops.domain.location.VehicleLocationEventRepository;
import com.idavy.drtops.domain.task.TaskResourceCoordinator;
import com.idavy.drtops.domain.task.TaskStop;
import com.idavy.drtops.domain.task.VehicleTask;
import com.idavy.drtops.domain.task.VehicleTaskRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderExceptionService {

    private final RideOrderRepository rideOrderRepository;
    private final AuditLogRepository auditLogRepository;
    private final VehicleTaskRepository vehicleTaskRepository;
    private final TaskResourceCoordinator taskResourceCoordinator;
    private final VehicleLocationEventRepository locationEventRepository;
    private final NoShowRejectedAuditService rejectedAuditService;
    private final NoShowEligibilityPolicy noShowEligibilityPolicy = new NoShowEligibilityPolicy();
    private final Clock clock = Clock.systemUTC();

    public OrderExceptionService(
            RideOrderRepository rideOrderRepository,
            AuditLogRepository auditLogRepository,
            VehicleTaskRepository vehicleTaskRepository,
            TaskResourceCoordinator taskResourceCoordinator,
            VehicleLocationEventRepository locationEventRepository,
            NoShowRejectedAuditService rejectedAuditService) {
        this.rideOrderRepository = rideOrderRepository;
        this.auditLogRepository = auditLogRepository;
        this.vehicleTaskRepository = vehicleTaskRepository;
        this.taskResourceCoordinator = taskResourceCoordinator;
        this.locationEventRepository = locationEventRepository;
        this.rejectedAuditService = rejectedAuditService;
    }

    @Transactional
    public RideOrder cancel(UUID actorId, UUID orderId, String reason) {
        RideOrder order = order(orderId);
        order.cancel(reason);
        audit(actorId, order.getId(), "ORDER_CANCELLED", reason);
        return order;
    }

    @Transactional
    public RideOrder noShow(UUID actorId, UUID orderId, String reason, UUID idempotencyKey) {
        RideOrder order = order(orderId);
        if (auditLogRepository.findByEntityId(orderId).stream()
                .anyMatch(log -> "ORDER_NO_SHOW".equals(log.getAction())
                        && log.getMetadataJson().contains(idempotencyKey.toString()))) {
            return order;
        }
        List<VehicleTask> tasks = vehicleTaskRepository.findActiveByRideOrderId(
                orderId, TaskResourceCoordinator.ACTIVE_STATUSES);
        VehicleTask task = tasks.isEmpty() ? null : tasks.getFirst();
        TaskStop pickupStop = task == null ? null : task.getStops().stream()
                .filter(stop -> orderId.equals(stop.getRideOrderId()))
                .filter(stop -> "BOARDING".equals(stop.getStopType()))
                .findFirst()
                .orElse(null);
        NoShowEligibility eligibility = task == null || pickupStop == null
                ? NoShowEligibility.blocked(
                        null, 0, "NO_SHOW_TASK_NOT_IN_PROGRESS", "车辆任务尚未开始执行")
                : noShowEligibilityPolicy.evaluate(
                        order.getStatus(),
                        task.getStatus(),
                        pickupStop.getStatus(),
                        order.getEstimatedBoardingAt(),
                        pickupStop.getActualArrivalAt(),
                        locationEventRepository.existsByVehicleTaskIdAndTaskStopIdAndEventType(
                                task.getId(), pickupStop.getId(), LocationEventType.PICKUP_ARRIVED),
                        OffsetDateTime.now(clock));
        if (!eligibility.eligible()) {
            rejectedAuditService.record(actorId, orderId, eligibility);
            throw new NoShowConflictException(
                    eligibility.reasonCode(), eligibility.reasonMessage(), eligibility.eligibleAt());
        }
        order.closeException(reason);
        int cancelledStopCount = 0;
        boolean resourcesReleased = false;
        for (VehicleTask activeTask : tasks) {
            cancelledStopCount += (int) activeTask.getStops().stream()
                    .filter(stop -> orderId.equals(stop.getRideOrderId()))
                    .filter(stop -> !stop.isExecutionComplete())
                    .count();
            if (activeTask.activeOrderIds().size() <= 1) {
                activeTask.cancelStopsForOrder(orderId);
                activeTask.cancel(reason);
                taskResourceCoordinator.releaseIfUnused(activeTask);
                resourcesReleased = true;
                auditTask(actorId, activeTask.getId(), "TASK_CANCELLED_NO_SHOW", reason);
            } else {
                activeTask.cancelStopsForOrder(orderId);
                auditTask(actorId, activeTask.getId(), "TASK_STOPS_CANCELLED_NO_SHOW", reason);
            }
        }
        auditLogRepository.save(AuditLog.record(
                "RIDE_ORDER",
                order.getId(),
                "ORDER_NO_SHOW",
                "USER",
                actorId.toString(),
                reason,
                """
                {"estimatedBoardingAt":"%s","pickupArrivedAt":"%s","eligibleAt":"%s","waitedSeconds":%d,\
"idempotencyKey":"%s","resourcesReleased":%s,"cancelledStopCount":%d}
                """.formatted(
                        order.getEstimatedBoardingAt(),
                        pickupStop.getActualArrivalAt(),
                        eligibility.eligibleAt(),
                        eligibility.waitedSeconds(),
                        idempotencyKey,
                        resourcesReleased,
                        cancelledStopCount).strip()));
        return order;
    }

    private RideOrder order(UUID orderId) {
        return rideOrderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在"));
    }

    private void audit(UUID actorId, UUID orderId, String action, String reason) {
        auditLogRepository.save(AuditLog.record(
                "RIDE_ORDER",
                orderId,
                action,
                "USER",
                actorId.toString(),
                reason,
                "{}"));
    }

    private void auditTask(UUID actorId, UUID taskId, String action, String reason) {
        auditLogRepository.save(AuditLog.record(
                "VEHICLE_TASK",
                taskId,
                action,
                "USER",
                actorId.toString(),
                reason,
                "{}"));
    }
}
