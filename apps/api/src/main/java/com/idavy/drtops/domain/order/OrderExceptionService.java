package com.idavy.drtops.domain.order;

import com.idavy.drtops.domain.audit.AuditLog;
import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.domain.task.TaskResourceCoordinator;
import com.idavy.drtops.domain.task.VehicleTask;
import com.idavy.drtops.domain.task.VehicleTaskRepository;
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

    public OrderExceptionService(
            RideOrderRepository rideOrderRepository,
            AuditLogRepository auditLogRepository,
            VehicleTaskRepository vehicleTaskRepository,
            TaskResourceCoordinator taskResourceCoordinator) {
        this.rideOrderRepository = rideOrderRepository;
        this.auditLogRepository = auditLogRepository;
        this.vehicleTaskRepository = vehicleTaskRepository;
        this.taskResourceCoordinator = taskResourceCoordinator;
    }

    @Transactional
    public RideOrder cancel(UUID actorId, UUID orderId, String reason) {
        RideOrder order = order(orderId);
        order.cancel(reason);
        audit(actorId, order.getId(), "ORDER_CANCELLED", reason);
        return order;
    }

    @Transactional
    public RideOrder noShow(UUID actorId, UUID orderId, String reason) {
        RideOrder order = order(orderId);
        order.closeException(reason);
        for (VehicleTask task : vehicleTaskRepository.findActiveByRideOrderId(
                orderId, TaskResourceCoordinator.ACTIVE_STATUSES)) {
            if (task.activeOrderIds().size() <= 1) {
                task.cancelStopsForOrder(orderId);
                task.cancel(reason);
                taskResourceCoordinator.releaseIfUnused(task);
                auditTask(actorId, task.getId(), "TASK_CANCELLED_NO_SHOW", reason);
            } else {
                task.cancelStopsForOrder(orderId);
                auditTask(actorId, task.getId(), "TASK_STOPS_CANCELLED_NO_SHOW", reason);
            }
        }
        audit(actorId, order.getId(), "ORDER_NO_SHOW", reason);
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
