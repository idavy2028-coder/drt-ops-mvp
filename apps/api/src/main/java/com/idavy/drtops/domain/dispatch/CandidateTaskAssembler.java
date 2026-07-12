package com.idavy.drtops.domain.dispatch;

import com.idavy.drtops.domain.fleet.Driver;
import com.idavy.drtops.domain.fleet.DriverRepository;
import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import com.idavy.drtops.domain.order.RideOrder;
import com.idavy.drtops.domain.order.RideOrderRepository;
import com.idavy.drtops.domain.task.TaskStatus;
import com.idavy.drtops.domain.task.TaskStop;
import com.idavy.drtops.domain.task.VehicleTask;
import com.idavy.drtops.domain.task.VehicleTaskRepository;
import com.idavy.drtops.integration.algorithm.DispatchEvaluateRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class CandidateTaskAssembler {

    private final VehicleRepository vehicleRepository;
    private final DriverRepository driverRepository;
    private final VehicleTaskRepository vehicleTaskRepository;
    private final RideOrderRepository rideOrderRepository;

    public CandidateTaskAssembler(
            VehicleRepository vehicleRepository,
            DriverRepository driverRepository,
            VehicleTaskRepository vehicleTaskRepository,
            RideOrderRepository rideOrderRepository) {
        this.vehicleRepository = vehicleRepository;
        this.driverRepository = driverRepository;
        this.vehicleTaskRepository = vehicleTaskRepository;
        this.rideOrderRepository = rideOrderRepository;
    }

    public DispatchEvaluateRequest assemble(RideOrder order, DispatchRuleSet ruleSet) {
        return new DispatchEvaluateRequest(
                new DispatchEvaluateRequest.Order(
                        order.getId(),
                        order.getPassengerCount(),
                        order.getRequestType(),
                        order.getRequestedDepartureAt(),
                        order.getBoardingStopId(),
                        order.getAlightingStopId()),
                toRuleSet(ruleSet),
                toCandidateTasks(order, ruleSet));
    }

    private DispatchEvaluateRequest.RuleSet toRuleSet(DispatchRuleSet ruleSet) {
        return new DispatchEvaluateRequest.RuleSet(
                ruleSet.getMaxWaitMinutes(),
                ruleSet.getMaxDetourMinutes(),
                ruleSet.getAutoDispatchScoreThreshold(),
                ruleSet.getManualReviewScoreThreshold(),
                new DispatchEvaluateRequest.Weights(
                        ruleSet.getWaitWeight(),
                        ruleSet.getDetourWeight(),
                        ruleSet.getStabilityWeight(),
                        ruleSet.getUtilizationWeight()),
                mapInsertionPolicy(ruleSet.getInsertionPolicy()));
    }

    private List<DispatchEvaluateRequest.CandidateTask> toCandidateTasks(
            RideOrder order,
            DispatchRuleSet ruleSet) {
        List<Vehicle> dispatchableVehicles = vehicleRepository.findAll().stream()
                .filter(Vehicle::isDispatchable)
                .toList();
        Map<UUID, Vehicle> vehiclesById = new HashMap<>();
        for (Vehicle vehicle : dispatchableVehicles) {
            vehiclesById.put(vehicle.getId(), vehicle);
        }

        List<Vehicle> idleVehicles = dispatchableVehicles.stream()
                .filter(vehicle -> "IDLE".equals(vehicle.getCurrentStatus()))
                .toList();
        List<Driver> drivers = driverRepository.findAll().stream()
                .filter(driver -> "QUALIFIED".equals(driver.getQualificationStatus()))
                .filter(driver -> "AVAILABLE".equals(driver.getCurrentStatus()))
                .toList();

        int count = Math.min(idleVehicles.size(), drivers.size());
        List<DispatchEvaluateRequest.CandidateTask> candidates = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            candidates.add(toNewTaskCandidate(order, ruleSet, idleVehicles.get(index)));
        }

        for (VehicleTask task : vehicleTaskRepository.findAllByOrderByPlannedStartAtAsc()) {
            Vehicle vehicle = vehiclesById.get(task.getVehicleId());
            if (vehicle != null && isInsertable(task)) {
                candidates.add(toExistingTaskCandidate(order, ruleSet, task, vehicle));
            }
        }
        return candidates;
    }

    private DispatchEvaluateRequest.CandidateTask toNewTaskCandidate(
            RideOrder order,
            DispatchRuleSet ruleSet,
            Vehicle vehicle) {
        int estimatedWaitMinutes = Math.min(ruleSet.getMaxWaitMinutes(), 6);
        int estimatedDetourMinutes = Math.min(ruleSet.getMaxDetourMinutes(), 3);
        OffsetDateTime boardingAt = order.getRequestedDepartureAt().plusMinutes(estimatedWaitMinutes);
        OffsetDateTime alightingAt = boardingAt.plusMinutes(estimatedDetourMinutes + 10L);

        return new DispatchEvaluateRequest.CandidateTask(
                syntheticTaskId(vehicle.getId()),
                vehicle.getId(),
                vehicle.getCapacity(),
                order.getBoardingStopId(),
                List.of(
                        new DispatchEvaluateRequest.PlannedStop(
                                order.getBoardingStopId(),
                                1,
                                boardingAt,
                                "BOARDING"),
                        new DispatchEvaluateRequest.PlannedStop(
                                order.getAlightingStopId(),
                                2,
                                alightingAt,
                                "ALIGHTING")),
                estimatedWaitMinutes,
                estimatedDetourMinutes,
                "SAME_DIRECTION",
                utilization(order, vehicle));
    }

    private DispatchEvaluateRequest.CandidateTask toExistingTaskCandidate(
            RideOrder order,
            DispatchRuleSet ruleSet,
            VehicleTask task,
            Vehicle vehicle) {
        int estimatedWaitMinutes = Math.min(ruleSet.getMaxWaitMinutes(), 6);
        int estimatedDetourMinutes = Math.min(ruleSet.getMaxDetourMinutes(), 3);
        OffsetDateTime boardingAt = order.getRequestedDepartureAt().plusMinutes(estimatedWaitMinutes);
        OffsetDateTime alightingAt = boardingAt.plusMinutes(estimatedDetourMinutes + 10L);
        int nextSequence = nextSequence(task);
        List<DispatchEvaluateRequest.PlannedStop> plannedStops = new ArrayList<>();
        for (TaskStop stop : task.getStops()) {
            plannedStops.add(new DispatchEvaluateRequest.PlannedStop(
                    stop.getVirtualStopId(),
                    stop.getSequenceNumber(),
                    stop.getPlannedArrivalAt(),
                    stop.getStopType()));
        }
        plannedStops.add(new DispatchEvaluateRequest.PlannedStop(
                order.getBoardingStopId(),
                nextSequence,
                boardingAt,
                "BOARDING"));
        plannedStops.add(new DispatchEvaluateRequest.PlannedStop(
                order.getAlightingStopId(),
                nextSequence + 1,
                alightingAt,
                "ALIGHTING"));

        return new DispatchEvaluateRequest.CandidateTask(
                task.getId(),
                task.getVehicleId(),
                availableSeats(task, vehicle),
                currentStopId(task, order),
                plannedStops,
                estimatedWaitMinutes,
                estimatedDetourMinutes,
                directionCompatibility(task, order),
                utilizationAfterInsert(order, task, vehicle));
    }

    private boolean isInsertable(VehicleTask task) {
        return task.getStatus() == TaskStatus.DISPATCHED || task.getStatus() == TaskStatus.IN_PROGRESS;
    }

    private int nextSequence(VehicleTask task) {
        return task.getStops().stream()
                .mapToInt(TaskStop::getSequenceNumber)
                .max()
                .orElse(0) + 1;
    }

    private int availableSeats(VehicleTask task, Vehicle vehicle) {
        return Math.max(0, vehicle.getCapacity() - occupiedSeats(task));
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

    private UUID currentStopId(VehicleTask task, RideOrder order) {
        return task.getStops().stream()
                .filter(stop -> !stop.isExecutionComplete())
                .findFirst()
                .map(TaskStop::getVirtualStopId)
                .orElse(order.getBoardingStopId());
    }

    private String directionCompatibility(VehicleTask task, RideOrder order) {
        List<UUID> stopIds = task.getStops().stream()
                .map(TaskStop::getVirtualStopId)
                .toList();
        int boardingIndex = stopIds.indexOf(order.getBoardingStopId());
        int alightingIndex = stopIds.indexOf(order.getAlightingStopId());
        if (boardingIndex >= 0 && alightingIndex >= boardingIndex) {
            return "SAME_DIRECTION";
        }
        return "UNKNOWN";
    }

    private BigDecimal utilizationAfterInsert(RideOrder order, VehicleTask task, Vehicle vehicle) {
        BigDecimal utilization = BigDecimal.valueOf(occupiedSeats(task) + order.getPassengerCount())
                .divide(BigDecimal.valueOf(vehicle.getCapacity()), 2, RoundingMode.HALF_UP);
        return utilization.min(BigDecimal.ONE);
    }

    private UUID syntheticTaskId(UUID vehicleId) {
        return UUID.nameUUIDFromBytes(("candidate-task:" + vehicleId).getBytes(StandardCharsets.UTF_8));
    }

    private BigDecimal utilization(RideOrder order, Vehicle vehicle) {
        return BigDecimal.valueOf(order.getPassengerCount())
                .divide(BigDecimal.valueOf(vehicle.getCapacity()), 2, RoundingMode.HALF_UP);
    }

    private String mapInsertionPolicy(String insertionPolicy) {
        if ("SAME_DIRECTION_ONLY".equals(insertionPolicy)) {
            return insertionPolicy;
        }
        return "FLEXIBLE";
    }
}
