package com.idavy.drtops.domain.dispatch;

import com.idavy.drtops.domain.audit.AuditLog;
import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.domain.fleet.Driver;
import com.idavy.drtops.domain.fleet.DriverRepository;
import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import com.idavy.drtops.domain.order.OrderStatus;
import com.idavy.drtops.domain.order.RideOrder;
import com.idavy.drtops.domain.order.RideOrderRepository;
import com.idavy.drtops.domain.task.TaskStop;
import com.idavy.drtops.domain.task.TaskStatus;
import com.idavy.drtops.domain.task.VehicleTask;
import com.idavy.drtops.domain.task.VehicleTaskRepository;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ManualReviewService {

    private final DispatchDecisionRepository dispatchDecisionRepository;
    private final RideOrderRepository rideOrderRepository;
    private final VehicleRepository vehicleRepository;
    private final DriverRepository driverRepository;
    private final VehicleTaskRepository vehicleTaskRepository;
    private final AuditLogRepository auditLogRepository;

    public ManualReviewService(
            DispatchDecisionRepository dispatchDecisionRepository,
            RideOrderRepository rideOrderRepository,
            VehicleRepository vehicleRepository,
            DriverRepository driverRepository,
            VehicleTaskRepository vehicleTaskRepository,
            AuditLogRepository auditLogRepository) {
        this.dispatchDecisionRepository = dispatchDecisionRepository;
        this.rideOrderRepository = rideOrderRepository;
        this.vehicleRepository = vehicleRepository;
        this.driverRepository = driverRepository;
        this.vehicleTaskRepository = vehicleTaskRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public DispatchResult approve(UUID actorId, UUID decisionId) {
        DispatchDecision decision = decision(decisionId);
        RideOrder order = pendingManualReviewOrder(decision);
        VehicleTask task = createTask(order, decision);
        order.confirm(new RideOrder.OrderPromise(
                task.getStops().getFirst().getPlannedArrivalAt(),
                task.getStops().getLast().getPlannedArrivalAt()));
        audit(actorId, order.getId(), "MANUAL_REVIEW_APPROVED", null);
        return new DispatchResult(order.getId(), DispatchDecisionType.MANUAL_REVIEW, decision.getId(), task.getId());
    }

    @Transactional
    public DispatchResult reject(UUID actorId, UUID decisionId, String reason) {
        DispatchDecision decision = decision(decisionId);
        RideOrder order = pendingManualReviewOrder(decision);
        order.markUnserviceable(reason);
        audit(actorId, order.getId(), "MANUAL_REVIEW_REJECTED", reason);
        return new DispatchResult(order.getId(), DispatchDecisionType.NO_FEASIBLE_PLAN, decision.getId(), null);
    }

    private VehicleTask createTask(RideOrder order, DispatchDecision decision) {
        if (decision.getBestVehicleId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "人工确认缺少候选车辆");
        }
        Vehicle vehicle = vehicleRepository.findById(decision.getBestVehicleId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "候选车辆不存在"));

        int waitMinutes = decision.getEstimatedWaitMinutes() == null ? 0 : decision.getEstimatedWaitMinutes();
        int detourMinutes = decision.getEstimatedDetourMinutes() == null ? 0 : decision.getEstimatedDetourMinutes();
        OffsetDateTime boardingAt = order.getRequestedDepartureAt().plusMinutes(waitMinutes);
        OffsetDateTime alightingAt = boardingAt.plusMinutes(detourMinutes + 10L);

        if (decision.getBestTaskId() != null) {
            VehicleTask existingTask = vehicleTaskRepository.findWithStopsById(decision.getBestTaskId()).orElse(null);
            if (existingTask != null) {
                return insertIntoExistingTask(order, vehicle, existingTask, boardingAt, alightingAt);
            }
        }

        Driver driver = driverRepository.findAll().stream()
                .filter(candidate -> "QUALIFIED".equals(candidate.getQualificationStatus()))
                .filter(candidate -> "AVAILABLE".equals(candidate.getCurrentStatus()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "没有可用驾驶员"));

        VehicleTask task = VehicleTask.pendingDeparture(vehicle.getId(), driver.getId(), boardingAt, "MANUAL_REVIEW");
        task.addStop(TaskStop.planned(order.getBoardingStopId(), order.getId(), 1, "BOARDING", boardingAt));
        task.addStop(TaskStop.planned(order.getAlightingStopId(), order.getId(), 2, "ALIGHTING", alightingAt));
        task.dispatch();
        return vehicleTaskRepository.save(task);
    }

    private VehicleTask insertIntoExistingTask(
            RideOrder order,
            Vehicle vehicle,
            VehicleTask task,
            OffsetDateTime boardingAt,
            OffsetDateTime alightingAt) {
        if (!task.getVehicleId().equals(vehicle.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "候选任务车辆不一致");
        }
        if (task.getStatus() != TaskStatus.DISPATCHED && task.getStatus() != TaskStatus.IN_PROGRESS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "候选任务不可插单");
        }
        assertCapacityAvailable(order, vehicle, task);

        int nextSequence = task.getStops().stream()
                .mapToInt(TaskStop::getSequenceNumber)
                .max()
                .orElse(0) + 1;
        task.addStop(TaskStop.planned(order.getBoardingStopId(), order.getId(), nextSequence, "BOARDING", boardingAt));
        task.addStop(TaskStop.planned(order.getAlightingStopId(), order.getId(), nextSequence + 1, "ALIGHTING", alightingAt));
        return vehicleTaskRepository.save(task);
    }

    private void assertCapacityAvailable(RideOrder order, Vehicle vehicle, VehicleTask task) {
        if (occupiedSeats(task) + order.getPassengerCount() > vehicle.getCapacity()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "候选任务剩余座位不足");
        }
    }

    private int occupiedSeats(VehicleTask task) {
        Set<UUID> orderIds = new LinkedHashSet<>();
        for (TaskStop stop : task.getStops()) {
            if (stop.getRideOrderId() != null) {
                orderIds.add(stop.getRideOrderId());
            }
        }

        int occupiedSeats = 0;
        for (UUID orderId : orderIds) {
            occupiedSeats += rideOrderRepository.findById(orderId)
                    .map(RideOrder::getPassengerCount)
                    .orElse(0);
        }
        return occupiedSeats;
    }

    private RideOrder pendingManualReviewOrder(DispatchDecision decision) {
        RideOrder order = rideOrderRepository.findById(decision.getRideOrderId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在"));
        if (order.getStatus() != OrderStatus.PENDING_MANUAL_REVIEW) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "订单不在人工确认状态");
        }
        return order;
    }

    private DispatchDecision decision(UUID decisionId) {
        DispatchDecision decision = dispatchDecisionRepository.findById(decisionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "调度决策不存在"));
        if (!"MANUAL_REVIEW".equals(decision.getDecisionResult())
                && !"PENDING_MANUAL_REVIEW".equals(decision.getDecisionResult())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "调度决策不需要人工确认");
        }
        return decision;
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
}
