package com.idavy.drtops.domain.task;

import com.idavy.drtops.domain.audit.AuditLog;
import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.domain.location.LocationEventType;
import com.idavy.drtops.domain.location.LocationReportCommand;
import com.idavy.drtops.domain.location.LocationReportResult;
import com.idavy.drtops.domain.location.LocationReportScope;
import com.idavy.drtops.domain.location.VehicleLocationRecorder;
import com.idavy.drtops.domain.location.VehicleLocationSnapshotService;
import com.idavy.drtops.domain.order.OrderStatus;
import com.idavy.drtops.domain.order.RideOrder;
import com.idavy.drtops.domain.order.RideOrderRepository;
import java.util.LinkedHashSet;
import java.util.Optional;
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
    private final VehicleLocationRecorder locationRecorder;
    private final VehicleLocationSnapshotService snapshotService;

    public TaskExecutionService(
            VehicleTaskRepository vehicleTaskRepository,
            RideOrderRepository rideOrderRepository,
            AuditLogRepository auditLogRepository,
            VehicleLocationRecorder locationRecorder,
            VehicleLocationSnapshotService snapshotService) {
        this.vehicleTaskRepository = vehicleTaskRepository;
        this.rideOrderRepository = rideOrderRepository;
        this.auditLogRepository = auditLogRepository;
        this.locationRecorder = locationRecorder;
        this.snapshotService = snapshotService;
    }

    @Transactional
    public TaskActionResponse start(UUID actorId, UUID taskId, TaskLocationReportRequest request) {
        VehicleTask task = taskForExecution(taskId);
        LocationReportCommand command = command(
                LocationReportScope.TASK_ACTION_START,
                task, null, null, LocationEventType.TASK_STARTED, actorId, request);
        Optional<LocationReportResult> replay = locationRecorder.findReplay(command);
        if (replay.isPresent()) {
            return TaskActionResponse.from(task, replay.get());
        }

        requireTaskStatus(task, TaskStatus.PENDING_DEPARTURE, TaskStatus.DISPATCHED);
        LocationReportResult result = requireFresh(locationRecorder.append(command));
        task.startExecution();
        for (RideOrder order : affectedOrders(task)) {
            if (order.getStatus() == OrderStatus.CONFIRMED) {
                order.startExecution();
            }
        }
        snapshotService.apply(result.event());
        audit(actorId, task.getId(), "TASK_STARTED", null, result.event().getId());
        return TaskActionResponse.from(task, result);
    }

    @Transactional
    public TaskActionResponse arrive(
            UUID actorId, UUID taskId, UUID taskStopId, TaskLocationReportRequest request) {
        VehicleTask task = taskForExecution(taskId);
        TaskStop stop = stop(task, taskStopId);
        validateVirtualStop(request, stop);
        LocationEventType eventType = "BOARDING".equals(stop.getStopType())
                ? LocationEventType.PICKUP_ARRIVED : LocationEventType.DROPOFF_ARRIVED;
        LocationReportCommand command = command(
                LocationReportScope.TASK_ACTION_ARRIVE,
                task, stop.getId(), stop.getVirtualStopId(), eventType, actorId, request);
        Optional<LocationReportResult> replay = locationRecorder.findReplay(command);
        if (replay.isPresent()) {
            return TaskActionResponse.from(task, replay.get());
        }

        requireInProgress(task);
        requireStopStatus(stop, "PLANNED", "当前任务节点不能执行到站");
        LocationReportResult result = requireFresh(locationRecorder.append(command));
        stop.arrive();
        task.markCurrentStop(stop.getVirtualStopId());
        snapshotService.apply(result.event());
        audit(actorId, task.getId(), "TASK_STOP_ARRIVED", stop.getId().toString(), result.event().getId());
        return TaskActionResponse.from(task, result);
    }

    @Transactional
    public TaskActionResponse board(
            UUID actorId, UUID taskId, UUID taskStopId, TaskLocationReportRequest request) {
        VehicleTask task = taskForExecution(taskId);
        TaskStop stop = stop(task, taskStopId);
        validateVirtualStop(request, stop);
        LocationReportCommand command = command(
                LocationReportScope.TASK_ACTION_BOARD,
                task, stop.getId(), stop.getVirtualStopId(), LocationEventType.PASSENGER_BOARDED, actorId, request);
        Optional<LocationReportResult> replay = locationRecorder.findReplay(command);
        if (replay.isPresent()) {
            return TaskActionResponse.from(task, replay.get());
        }

        requireInProgress(task);
        requireStopType(stop, "BOARDING", "当前任务节点不是上车节点");
        requireStopStatus(stop, "ARRIVED", "当前任务节点不能执行上车");
        LocationReportResult result = requireFresh(locationRecorder.append(command));
        stop.board();
        snapshotService.apply(result.event());
        audit(actorId, task.getId(), "PASSENGER_BOARDED", stop.getId().toString(), result.event().getId());
        return TaskActionResponse.from(task, result);
    }

    @Transactional
    public TaskActionResponse alight(
            UUID actorId, UUID taskId, UUID taskStopId, TaskLocationReportRequest request) {
        VehicleTask task = taskForExecution(taskId);
        TaskStop stop = stop(task, taskStopId);
        validateVirtualStop(request, stop);
        LocationReportCommand command = command(
                LocationReportScope.TASK_ACTION_ALIGHT,
                task, stop.getId(), stop.getVirtualStopId(), LocationEventType.PASSENGER_ALIGHTED, actorId, request);
        Optional<LocationReportResult> replay = locationRecorder.findReplay(command);
        if (replay.isPresent()) {
            return TaskActionResponse.from(task, replay.get());
        }

        requireInProgress(task);
        requireStopType(stop, "ALIGHTING", "当前任务节点不是下车节点");
        requireStopStatus(stop, "ARRIVED", "当前任务节点不能执行下车");
        LocationReportResult result = requireFresh(locationRecorder.append(command));
        stop.alight();
        snapshotService.apply(result.event());
        audit(actorId, task.getId(), "PASSENGER_ALIGHTED", stop.getId().toString(), result.event().getId());
        return TaskActionResponse.from(task, result);
    }

    @Transactional
    public TaskActionResponse complete(UUID actorId, UUID taskId, TaskLocationReportRequest request) {
        VehicleTask task = taskForExecution(taskId);
        LocationReportCommand command = command(
                LocationReportScope.TASK_ACTION_COMPLETE,
                task, null, null, LocationEventType.TASK_COMPLETED, actorId, request);
        Optional<LocationReportResult> replay = locationRecorder.findReplay(command);
        if (replay.isPresent()) {
            return TaskActionResponse.from(task, replay.get());
        }

        requireInProgress(task);
        if (task.getStops().stream().anyMatch(stop -> !stop.isExecutionComplete())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "任务节点尚未全部完成");
        }
        LocationReportResult result = requireFresh(locationRecorder.append(command));
        task.complete();
        for (RideOrder order : affectedOrders(task)) {
            if (order.getStatus() == OrderStatus.IN_PROGRESS) {
                order.complete();
            }
        }
        snapshotService.apply(result.event());
        audit(actorId, task.getId(), "TASK_COMPLETED", null, result.event().getId());
        return TaskActionResponse.from(task, result);
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
        auditWithoutLocation(actorId, task.getId(), auditAction, reason);
        return task;
    }

    private LocationReportCommand command(
            LocationReportScope scope,
            VehicleTask task,
            UUID taskStopId,
            UUID virtualStopId,
            LocationEventType eventType,
            UUID actorId,
            TaskLocationReportRequest request) {
        if (taskStopId == null && request.virtualStopId() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前任务动作没有可关联的任务节点");
        }
        return new LocationReportCommand(
                scope,
                task.getVehicleId(),
                task.getId(),
                taskStopId,
                virtualStopId,
                eventType,
                request.longitude(),
                request.latitude(),
                request.standardizedAddress(),
                request.driverReportedAt(),
                actorId,
                request.note(),
                null,
                null,
                request.idempotencyKey());
    }

    private static LocationReportResult requireFresh(LocationReportResult result) {
        if (result.replayed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "任务动作幂等状态发生冲突，请重试");
        }
        return result;
    }

    private static void validateVirtualStop(TaskLocationReportRequest request, TaskStop stop) {
        if (request.virtualStopId() != null && !request.virtualStopId().equals(stop.getVirtualStopId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "虚拟站点与当前任务节点不一致");
        }
    }

    private static void requireTaskStatus(VehicleTask task, TaskStatus... expectedStatuses) {
        for (TaskStatus expectedStatus : expectedStatuses) {
            if (task.getStatus() == expectedStatus) {
                return;
            }
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "车辆任务当前状态不能执行该操作");
    }

    private static void requireInProgress(VehicleTask task) {
        if (task.getStatus() != TaskStatus.IN_PROGRESS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "车辆任务尚未开始执行");
        }
    }

    private static void requireStopType(TaskStop stop, String expectedType, String message) {
        if (!expectedType.equals(stop.getStopType())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, message);
        }
    }

    private static void requireStopStatus(TaskStop stop, String expectedStatus, String message) {
        if (!expectedStatus.equals(stop.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, message);
        }
    }

    private VehicleTask taskForExecution(UUID taskId) {
        return vehicleTaskRepository.findByIdForExecution(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "车辆任务不存在"));
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

    private void audit(UUID actorId, UUID taskId, String action, String reason, UUID locationEventId) {
        auditLogRepository.save(AuditLog.record(
                "VEHICLE_TASK",
                taskId,
                action,
                "USER",
                actorId.toString(),
                reason,
                "{\"locationEventId\":\"" + locationEventId + "\"}"));
    }

    private void auditWithoutLocation(UUID actorId, UUID taskId, String action, String reason) {
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
