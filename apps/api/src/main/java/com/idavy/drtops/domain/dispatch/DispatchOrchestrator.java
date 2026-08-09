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
import com.idavy.drtops.domain.task.TaskStopInsertionPolicy;
import com.idavy.drtops.domain.task.TaskStatus;
import com.idavy.drtops.domain.task.VehicleTask;
import com.idavy.drtops.domain.task.VehicleTaskRepository;
import com.idavy.drtops.domain.task.TaskResourceCoordinator;
import com.idavy.drtops.integration.algorithm.AlgorithmClient;
import com.idavy.drtops.integration.algorithm.DispatchEvaluateRequest;
import com.idavy.drtops.integration.algorithm.DispatchEvaluateResponse;
import java.time.OffsetDateTime;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DispatchOrchestrator {

    private static final String ALGORITHM_VERSION = "0.2.0";
    private static final String SYSTEM_ACTOR_TYPE = "SYSTEM";
    private static final String SYSTEM_ACTOR_ID = "dispatch-orchestrator";

    private final RideOrderRepository rideOrderRepository;
    private final DispatchRuleSetRepository ruleSetRepository;
    private final DispatchDecisionRepository dispatchDecisionRepository;
    private final VehicleRepository vehicleRepository;
    private final DriverRepository driverRepository;
    private final VehicleTaskRepository vehicleTaskRepository;
    private final TaskResourceCoordinator taskResourceCoordinator;
    private final TaskStopInsertionPolicy taskStopInsertionPolicy;
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
            TaskResourceCoordinator taskResourceCoordinator,
            TaskStopInsertionPolicy taskStopInsertionPolicy,
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
        this.taskResourceCoordinator = taskResourceCoordinator;
        this.taskStopInsertionPolicy = taskStopInsertionPolicy;
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
        CandidateTaskAssembler.Assembly assembly = candidateTaskAssembler.assembleWithTravelEstimates(order, ruleSet);
        DispatchEvaluateResponse response = algorithmClient.evaluate(assembly.request());
        response = forceManualReviewWhenRequired(response, assembly);
        CandidateTaskAssembler.CandidateTravelEstimates travelEstimates = assembly.estimatesFor(response.bestPlan());

        VehicleTask vehicleTask = applyDecision(order, ruleSet, response, travelEstimates, assembly);
        DispatchDecision decision = dispatchDecisionRepository.save(DispatchDecision.fromAlgorithm(
                order.getId(),
                response,
                persistedTaskId(response, vehicleTask),
                travelEstimates,
                toJson(response.rejectedCandidates()),
                toJson(explanationWithTravelEstimates(response, travelEstimates, assembly)),
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

    private UUID persistedTaskId(DispatchEvaluateResponse response, VehicleTask vehicleTask) {
        if (vehicleTask != null) {
            return vehicleTask.getId();
        }
        DispatchEvaluateResponse.BestPlan bestPlan = response.bestPlan();
        if (response.decision() == DispatchDecisionType.MANUAL_REVIEW
                && bestPlan != null
                && bestPlan.taskId() != null
                && vehicleTaskRepository.existsById(bestPlan.taskId())) {
            return bestPlan.taskId();
        }
        return null;
    }

    private DispatchRuleSet enabledRuleSet() {
        return ruleSetRepository.findAll().stream()
                .filter(DispatchRuleSet::isEnabled)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "未配置启用的调度规则组"));
    }

    private DispatchEvaluateResponse forceManualReviewWhenRequired(
            DispatchEvaluateResponse response,
            CandidateTaskAssembler.Assembly assembly) {
        if (!assembly.requiresManualReview() || response.decision() == DispatchDecisionType.MANUAL_REVIEW) {
            return response;
        }
        Map<String, Object> explanation = new LinkedHashMap<>();
        if (response.explanation() != null) {
            explanation.putAll(response.explanation());
        }
        explanation.put("reason", assembly.manualReviewReason());
        explanation.put("manualReviewRequired", true);
        return new DispatchEvaluateResponse(
                DispatchDecisionType.MANUAL_REVIEW,
                response.bestPlan(),
                response.candidateCount(),
                response.rejectedCount(),
                response.rejectedCandidates(),
                explanation);
    }

    private VehicleTask applyDecision(
            RideOrder order,
            DispatchRuleSet ruleSet,
            DispatchEvaluateResponse response,
            CandidateTaskAssembler.CandidateTravelEstimates travelEstimates,
            CandidateTaskAssembler.Assembly assembly) {
        return switch (response.decision()) {
            case AUTO_DISPATCH -> autoDispatch(
                    order,
                    ruleSet,
                    response.bestPlan(),
                    travelEstimates,
                    assembly.isNewTaskCandidate(response.bestPlan()));
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

    private VehicleTask autoDispatch(
            RideOrder order,
            DispatchRuleSet ruleSet,
            DispatchEvaluateResponse.BestPlan bestPlan,
            CandidateTaskAssembler.CandidateTravelEstimates travelEstimates,
            boolean newTaskCandidate) {
        if (bestPlan == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "算法自动派发结果缺少最优方案");
        }

        VehicleTask existingTask = bestPlan.taskId() == null || newTaskCandidate
                ? null : taskForInsertion(bestPlan.taskId());
        Vehicle vehicle = vehicleRepository.findById(bestPlan.vehicleId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "算法返回的车辆不存在"));
        int waitMinutes = travelEstimates == null || travelEstimates.vehicleToPickup() == null
                ? bestPlan.estimatedWaitMinutes()
                : (int) Math.max(1, Math.ceil(travelEstimates.vehicleToPickup().durationSeconds() / 60D));
        OffsetDateTime estimatedBoardingAt = order.getRequestedDepartureAt().plusMinutes(waitMinutes);
        OffsetDateTime estimatedArrivalAt = travelEstimates == null || travelEstimates.pickupToDestination() == null
                ? estimatedBoardingAt.plusMinutes(bestPlan.estimatedDetourMinutes() + 10L)
                : estimatedBoardingAt.plus(Duration.ofSeconds(travelEstimates.pickupToDestination().durationSeconds()));

        if (existingTask != null) {
            return insertIntoExistingTask(order, ruleSet, vehicle, existingTask);
        }

        Driver driver = availableDriver();
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

        taskResourceCoordinator.reserve(vehicle.getId(), driver.getId());
        VehicleTask savedTask = vehicleTaskRepository.save(task);
        order.confirm(new RideOrder.OrderPromise(estimatedBoardingAt, estimatedArrivalAt));
        return savedTask;
    }

    private VehicleTask taskForInsertion(UUID taskId) {
        VehicleTask task = vehicleTaskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "算法返回的任务不存在"));
        return task;
    }

    private VehicleTask insertIntoExistingTask(
            RideOrder order,
            DispatchRuleSet ruleSet,
            Vehicle vehicle,
            VehicleTask task) {
        if (!task.getVehicleId().equals(vehicle.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "算法返回的任务车辆不一致");
        }
        if (task.getStatus() != TaskStatus.DISPATCHED && task.getStatus() != TaskStatus.IN_PROGRESS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "DISPATCH_CANDIDATE_STALE");
        }
        TaskInsertionPlan plan = candidateTaskAssembler.replanExistingTask(order, ruleSet, vehicle, task);
        if (!plan.feasible()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "DISPATCH_CANDIDATE_STALE");
        }
        taskStopInsertionPolicy.applyPlan(task, order, plan);
        OffsetDateTime estimatedBoardingAt = plannedTime(plan, order.getId(), "BOARDING");
        OffsetDateTime estimatedArrivalAt = plannedTime(plan, order.getId(), "ALIGHTING");
        VehicleTask savedTask = vehicleTaskRepository.save(task);
        order.confirm(new RideOrder.OrderPromise(estimatedBoardingAt, estimatedArrivalAt));
        return savedTask;
    }

    private OffsetDateTime plannedTime(TaskInsertionPlan plan, UUID orderId, String stopType) {
        return plan.orderedStops().stream()
                .filter(stop -> orderId.equals(stop.rideOrderId()) && stopType.equals(stop.stopType()))
                .map(TaskInsertionPlan.PlannedTaskStop::plannedArrivalAt)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "DISPATCH_CANDIDATE_STALE"));
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

    private Map<String, Object> explanationWithTravelEstimates(
            DispatchEvaluateResponse response,
            CandidateTaskAssembler.CandidateTravelEstimates travelEstimates,
            CandidateTaskAssembler.Assembly assembly) {
        Map<String, Object> explanation = new LinkedHashMap<>();
        if (response.explanation() != null) {
            explanation.putAll(response.explanation());
        }
        DispatchEvaluateResponse.BestPlan bestPlan = response.bestPlan();
        if (bestPlan != null) {
            explanation.put("candidateType", bestPlan.candidateType());
            explanation.put("activationCost", bestPlan.activationCost());
            explanation.put("selectionReason", bestPlan.selectionReason());
            explanation.put("estimatedWaitMinutes", bestPlan.estimatedWaitMinutes());
            explanation.put("maxPassengerDetourMinutes", bestPlan.estimatedDetourMinutes());
            TaskInsertionPlan insertionPlan = bestPlan.taskId() == null
                    ? null : assembly.insertionPlanFor(bestPlan.taskId());
            if (insertionPlan != null) {
                explanation.put("baselineRouteDurationSeconds", insertionPlan.baselineRouteDurationSeconds());
                explanation.put("plannedRouteDurationSeconds", insertionPlan.plannedRouteDurationSeconds());
                explanation.put("peakOccupiedSeats", insertionPlan.peakOccupiedSeats());
                explanation.put("insertionBoardingPosition", insertionPlan.boardingIndex());
                explanation.put("insertionAlightingPosition", insertionPlan.alightingIndex());
                explanation.put("routeDegraded", insertionPlan.degraded());
                if (insertionPlan.degradedReason() != null) {
                    explanation.put("routeDegradedReason", insertionPlan.degradedReason());
                }
            }
        }
        if (travelEstimates != null) {
            explanation.put("vehicleToPickup", estimateDetails(travelEstimates.vehicleToPickup()));
            explanation.put("pickupToDestination", estimateDetails(travelEstimates.pickupToDestination()));
        }
        if (assembly.manualReviewReason() != null) {
            explanation.put("manualReviewReason", assembly.manualReviewReason());
        }
        return explanation;
    }

    private Map<String, Object> estimateDetails(TravelEstimate estimate) {
        if (estimate == null) {
            return Map.of("available", false);
        }
        return Map.of(
                "available", true,
                "distanceMeters", estimate.distanceMeters(),
                "durationSeconds", estimate.durationSeconds(),
                "provider", estimate.provider(),
                "degraded", estimate.degraded(),
                "degradedReason", estimate.degradedReason() == null ? "" : estimate.degradedReason());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize dispatch decision payload", exception);
        }
    }
}
