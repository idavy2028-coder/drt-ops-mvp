package com.idavy.drtops.domain.task;

import com.idavy.drtops.domain.audit.AuditLog;
import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.domain.order.OrderStatus;
import com.idavy.drtops.domain.order.RideOrder;
import com.idavy.drtops.domain.order.RideOrderRepository;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TaskExecutionService {

    private final VehicleTaskRepository vehicleTaskRepository;
    private final RideOrderRepository rideOrderRepository;
    private final AuditLogRepository auditLogRepository;

    public TaskExecutionService(
            VehicleTaskRepository vehicleTaskRepository,
            RideOrderRepository rideOrderRepository,
            AuditLogRepository auditLogRepository) {
        this.vehicleTaskRepository = vehicleTaskRepository;
        this.rideOrderRepository = rideOrderRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public VehicleTask start(UUID actorId, UUID taskId) {
        VehicleTask task = task(taskId);
        task.startExecution();
        for (RideOrder order : affectedOrders(task)) {
            if (order.getStatus() == OrderStatus.CONFIRMED) {
                order.startExecution();
            }
        }
        audit(actorId, task.getId(), "TASK_STARTED", null);
        return task;
    }

    @Transactional
    public VehicleTask arrive(UUID actorId, UUID taskId, UUID taskStopId) {
        VehicleTask task = inProgressTask(taskId);
        TaskStop stop = stop(task, taskStopId);
        stop.arrive();
        task.markCurrentStop(stop.getVirtualStopId());
        audit(actorId, task.getId(), "TASK_STOP_ARRIVED", stop.getId().toString());
        return task;
    }

    @Transactional
    public VehicleTask board(UUID actorId, UUID taskId, UUID taskStopId) {
        VehicleTask task = inProgressTask(taskId);
        TaskStop stop = stop(task, taskStopId);
        stop.board();
        audit(actorId, task.getId(), "PASSENGER_BOARDED", stop.getId().toString());
        return task;
    }

    @Transactional
    public VehicleTask alight(UUID actorId, UUID taskId, UUID taskStopId) {
        VehicleTask task = inProgressTask(taskId);
        TaskStop stop = stop(task, taskStopId);
        stop.alight();
        audit(actorId, task.getId(), "PASSENGER_ALIGHTED", stop.getId().toString());
        return task;
    }

    @Transactional
    public VehicleTask complete(UUID actorId, UUID taskId) {
        VehicleTask task = inProgressTask(taskId);
        if (task.getStops().stream().anyMatch(stop -> !stop.isExecutionComplete())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "任务节点尚未全部完成");
        }
        task.complete();
        for (RideOrder order : affectedOrders(task)) {
            if (order.getStatus() == OrderStatus.IN_PROGRESS) {
                order.complete();
            }
        }
        audit(actorId, task.getId(), "TASK_COMPLETED", null);
        return task;
    }

    @Transactional
    public VehicleTask markException(UUID actorId, UUID taskId, String reason) {
        return closeTaskAsException(actorId, taskId, reason, "TASK_EXCEPTION");
    }

    @Transactional
    public VehicleTask markSevereDelay(UUID actorId, UUID taskId, String reason) {
        return closeTaskAsException(actorId, taskId, reason, "TASK_SEVERE_DELAY");
    }

    private VehicleTask closeTaskAsException(UUID actorId, UUID taskId, String reason, String auditAction) {
        VehicleTask task = task(taskId);
        task.markException(reason);
        for (RideOrder order : affectedOrders(task)) {
            if (order.getStatus() != OrderStatus.COMPLETED
                    && order.getStatus() != OrderStatus.CANCELLED
                    && order.getStatus() != OrderStatus.UNSERVICEABLE
                    && order.getStatus() != OrderStatus.EXCEPTION_CLOSED) {
                order.closeException(reason);
            }
        }
        audit(actorId, task.getId(), auditAction, reason);
        return task;
    }

    private VehicleTask inProgressTask(UUID taskId) {
        VehicleTask task = task(taskId);
        if (task.getStatus() != TaskStatus.IN_PROGRESS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "车辆任务尚未开始执行");
        }
        return task;
    }

    private VehicleTask task(UUID taskId) {
        return vehicleTaskRepository.findWithStopsById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "车辆任务不存在"));
    }

    private TaskStop stop(VehicleTask task, UUID taskStopId) {
        return task.getStops().stream()
                .filter(candidate -> candidate.getId().equals(taskStopId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务节点不存在"));
    }

    private Set<RideOrder> affectedOrders(VehicleTask task) {
        Set<UUID> orderIds = new LinkedHashSet<>();
        for (TaskStop stop : task.getStops()) {
            if (stop.getRideOrderId() != null) {
                orderIds.add(stop.getRideOrderId());
            }
        }

        Set<RideOrder> orders = new LinkedHashSet<>();
        for (UUID orderId : orderIds) {
            rideOrderRepository.findById(orderId).ifPresent(orders::add);
        }
        return orders;
    }

    private void audit(UUID actorId, UUID taskId, String action, String reason) {
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
