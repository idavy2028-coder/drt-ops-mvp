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
import com.idavy.drtops.domain.task.VehicleTask;
import com.idavy.drtops.domain.task.VehicleTaskRepository;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ManualReviewService {

    private static final String SYSTEM_ACTOR_TYPE = "SYSTEM";
    private static final String SYSTEM_ACTOR_ID = "manual-review";

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
    public DispatchResult approve(UUID decisionId) {
        DispatchDecision decision = decision(decisionId);
        RideOrder order = pendingManualReviewOrder(decision);
        VehicleTask task = createTask(order, decision);
        order.confirm(new RideOrder.OrderPromise(
                task.getStops().getFirst().getPlannedArrivalAt(),
                task.getStops().getLast().getPlannedArrivalAt()));
        audit(order.getId(), "MANUAL_REVIEW_APPROVED", null);
        return new DispatchResult(order.getId(), DispatchDecisionType.MANUAL_REVIEW, decision.getId(), task.getId());
    }

    @Transactional
    public DispatchResult reject(UUID decisionId, String reason) {
        DispatchDecision decision = decision(decisionId);
        RideOrder order = pendingManualReviewOrder(decision);
        order.markUnserviceable(reason);
        audit(order.getId(), "MANUAL_REVIEW_REJECTED", reason);
        return new DispatchResult(order.getId(), DispatchDecisionType.NO_FEASIBLE_PLAN, decision.getId(), null);
    }

    private VehicleTask createTask(RideOrder order, DispatchDecision decision) {
        if (decision.getBestVehicleId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "人工确认缺少候选车辆");
        }
        Vehicle vehicle = vehicleRepository.findById(decision.getBestVehicleId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "候选车辆不存在"));
        Driver driver = driverRepository.findAll().stream()
                .filter(candidate -> "QUALIFIED".equals(candidate.getQualificationStatus()))
                .filter(candidate -> "AVAILABLE".equals(candidate.getCurrentStatus()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "没有可用驾驶员"));

        int waitMinutes = decision.getEstimatedWaitMinutes() == null ? 0 : decision.getEstimatedWaitMinutes();
        int detourMinutes = decision.getEstimatedDetourMinutes() == null ? 0 : decision.getEstimatedDetourMinutes();
        OffsetDateTime boardingAt = order.getRequestedDepartureAt().plusMinutes(waitMinutes);
        OffsetDateTime alightingAt = boardingAt.plusMinutes(detourMinutes + 10L);

        VehicleTask task = VehicleTask.pendingDeparture(vehicle.getId(), driver.getId(), boardingAt, "MANUAL_REVIEW");
        task.addStop(TaskStop.planned(order.getBoardingStopId(), order.getId(), 1, "BOARDING", boardingAt));
        task.addStop(TaskStop.planned(order.getAlightingStopId(), order.getId(), 2, "ALIGHTING", alightingAt));
        task.dispatch();
        return vehicleTaskRepository.save(task);
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

    private void audit(UUID orderId, String action, String reason) {
        auditLogRepository.save(AuditLog.record(
                "RIDE_ORDER",
                orderId,
                action,
                SYSTEM_ACTOR_TYPE,
                SYSTEM_ACTOR_ID,
                reason,
                "{}"));
    }
}
