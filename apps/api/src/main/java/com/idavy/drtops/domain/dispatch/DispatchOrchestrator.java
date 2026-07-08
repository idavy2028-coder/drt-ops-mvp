package com.idavy.drtops.domain.dispatch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.idavy.drtops.integration.algorithm.AlgorithmClient;
import com.idavy.drtops.integration.algorithm.DispatchEvaluateRequest;
import com.idavy.drtops.integration.algorithm.DispatchEvaluateResponse;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DispatchOrchestrator {

    private static final String ALGORITHM_VERSION = "0.1.0";
    private static final String SYSTEM_ACTOR_TYPE = "SYSTEM";
    private static final String SYSTEM_ACTOR_ID = "dispatch-orchestrator";

    private final RideOrderRepository rideOrderRepository;
    private final DispatchRuleSetRepository ruleSetRepository;
    private final DispatchDecisionRepository dispatchDecisionRepository;
    private final VehicleRepository vehicleRepository;
    private final DriverRepository driverRepository;
    private final VehicleTaskRepository vehicleTaskRepository;
    private final AuditLogRepository auditLogRepository;
    private final CandidateTaskAssembler candidateTaskAssembler;
    private final AlgorithmClient algorithmClient;
    private final ObjectMapper objectMapper;

    public DispatchOrchestrator(
            RideOrderRepository rideOrderRepository,
            DispatchRuleSetRepository ruleSetRepository,
            DispatchDecisionRepository dispatchDecisionRepository,
            VehicleRepository vehicleRepository,
            DriverRepository driverRepository,
            VehicleTaskRepository vehicleTaskRepository,
            AuditLogRepository auditLogRepository,
            CandidateTaskAssembler candidateTaskAssembler,
            AlgorithmClient algorithmClient,
            ObjectMapper objectMapper) {
        this.rideOrderRepository = rideOrderRepository;
        this.ruleSetRepository = ruleSetRepository;
        this.dispatchDecisionRepository = dispatchDecisionRepository;
        this.vehicleRepository = vehicleRepository;
        this.driverRepository = driverRepository;
        this.vehicleTaskRepository = vehicleTaskRepository;
        this.auditLogRepository = auditLogRepository;
        this.candidateTaskAssembler = candidateTaskAssembler;
        this.algorithmClient = algorithmClient;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public DispatchResult dispatchOrder(UUID orderId) {
        RideOrder order = rideOrderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在"));
        if (order.getStatus() != OrderStatus.PENDING_DISPATCH) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "订单当前状态不可调度");
        }

        DispatchRuleSet ruleSet = enabledRuleSet();
        DispatchEvaluateRequest request = candidateTaskAssembler.assemble(order, ruleSet);
        DispatchEvaluateResponse response = algorithmClient.evaluate(request);

        VehicleTask vehicleTask = applyDecision(order, response);
        DispatchDecision decision = dispatchDecisionRepository.save(DispatchDecision.fromAlgorithm(
                order.getId(),
                response,
                vehicleTask == null ? null : vehicleTask.getId(),
                toJson(response.rejectedCandidates()),
                toJson(response.explanation()),
                ALGORITHM_VERSION,
                SYSTEM_ACTOR_TYPE,
                SYSTEM_ACTOR_ID));
        auditLogRepository.save(AuditLog.record(
                "RIDE_ORDER",
                order.getId(),
                auditAction(response.decision()),
                SYSTEM_ACTOR_TYPE,
                SYSTEM_ACTOR_ID,
                explanationReason(response),
                toJson(Map.of(
                        "dispatchDecisionId", decision.getId(),
                        "decision", response.decision().name()))));

        return new DispatchResult(
                order.getId(),
                response.decision(),
                decision.getId(),
                vehicleTask == null ? null : vehicleTask.getId());
    }

    private DispatchRuleSet enabledRuleSet() {
        return ruleSetRepository.findAll().stream()
                .filter(DispatchRuleSet::isEnabled)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "未配置启用的调度规则组"));
    }

    private VehicleTask applyDecision(RideOrder order, DispatchEvaluateResponse response) {
        return switch (response.decision()) {
            case AUTO_DISPATCH -> autoDispatch(order, response.bestPlan());
            case MANUAL_REVIEW -> {
                order.markPendingManualReview(explanationReason(response));
                yield null;
            }
            case NO_FEASIBLE_PLAN -> {
                order.markUnserviceable(explanationReason(response));
                yield null;
            }
        };
    }

    private VehicleTask autoDispatch(RideOrder order, DispatchEvaluateResponse.BestPlan bestPlan) {
        if (bestPlan == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "算法自动派发结果缺少最优方案");
        }

        Vehicle vehicle = vehicleRepository.findById(bestPlan.vehicleId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "算法返回的车辆不存在"));
        Driver driver = availableDriver();
        OffsetDateTime estimatedBoardingAt = order.getRequestedDepartureAt().plusMinutes(bestPlan.estimatedWaitMinutes());
        OffsetDateTime estimatedArrivalAt = estimatedBoardingAt.plusMinutes(bestPlan.estimatedDetourMinutes() + 10L);

        VehicleTask task = VehicleTask.pendingDeparture(
                vehicle.getId(),
                driver.getId(),
                estimatedBoardingAt,
                "ALGORITHM");
        task.addStop(TaskStop.planned(
                order.getBoardingStopId(),
                order.getId(),
                1,
                "BOARDING",
                estimatedBoardingAt));
        task.addStop(TaskStop.planned(
                order.getAlightingStopId(),
                order.getId(),
                2,
                "ALIGHTING",
                estimatedArrivalAt));
        task.dispatch();

        VehicleTask savedTask = vehicleTaskRepository.save(task);
        order.confirm(new RideOrder.OrderPromise(estimatedBoardingAt, estimatedArrivalAt));
        return savedTask;
    }

    private Driver availableDriver() {
        return driverRepository.findAll().stream()
                .filter(driver -> "QUALIFIED".equals(driver.getQualificationStatus()))
                .filter(driver -> "AVAILABLE".equals(driver.getCurrentStatus()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "没有可用驾驶员"));
    }

    private String auditAction(DispatchDecisionType decision) {
        return switch (decision) {
            case AUTO_DISPATCH -> "ORDER_AUTO_DISPATCHED";
            case MANUAL_REVIEW -> "ORDER_PENDING_MANUAL_REVIEW";
            case NO_FEASIBLE_PLAN -> "ORDER_UNSERVICEABLE";
        };
    }

    private String explanationReason(DispatchEvaluateResponse response) {
        Object reason = response.explanation() == null ? null : response.explanation().get("reason");
        return reason == null ? response.decision().name() : reason.toString();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize dispatch decision payload", exception);
        }
    }
}
