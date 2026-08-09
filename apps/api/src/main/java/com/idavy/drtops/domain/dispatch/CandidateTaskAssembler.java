package com.idavy.drtops.domain.dispatch;

import com.idavy.drtops.domain.fleet.Driver;
import com.idavy.drtops.domain.fleet.DriverRepository;
import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import com.idavy.drtops.domain.map.Coordinate;
import com.idavy.drtops.domain.order.RideOrder;
import com.idavy.drtops.domain.order.RideOrderRepository;
import com.idavy.drtops.domain.task.TaskStatus;
import com.idavy.drtops.domain.task.VehicleTask;
import com.idavy.drtops.domain.task.VehicleTaskRepository;
import com.idavy.drtops.integration.algorithm.DispatchEvaluateRequest;
import com.idavy.drtops.integration.algorithm.DispatchEvaluateResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
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
    private final TravelEstimateService travelEstimateService;
    private final TaskInsertionPlanner taskInsertionPlanner;

    public CandidateTaskAssembler(
            VehicleRepository vehicleRepository,
            DriverRepository driverRepository,
            VehicleTaskRepository vehicleTaskRepository,
            RideOrderRepository rideOrderRepository,
            TravelEstimateService travelEstimateService,
            TaskInsertionPlanner taskInsertionPlanner) {
        this.vehicleRepository = vehicleRepository;
        this.driverRepository = driverRepository;
        this.vehicleTaskRepository = vehicleTaskRepository;
        this.rideOrderRepository = rideOrderRepository;
        this.travelEstimateService = travelEstimateService;
        this.taskInsertionPlanner = taskInsertionPlanner;
    }

    public DispatchEvaluateRequest assemble(RideOrder order, DispatchRuleSet ruleSet) {
        return assembleWithTravelEstimates(order, ruleSet).request();
    }

    public TaskInsertionPlan replanExistingTask(
            RideOrder order,
            DispatchRuleSet ruleSet,
            Vehicle vehicle,
            VehicleTask task) {
        return taskInsertionPlanner.plan(
                order,
                vehicle,
                task,
                ruleSet,
                stopCoordinates(order, task),
                passengerCounts(order, task));
    }

    public Assembly assembleWithTravelEstimates(RideOrder order, DispatchRuleSet ruleSet) {
        Coordinate pickupCoordinate = new Coordinate(order.getOriginLng(), order.getOriginLat());
        Coordinate destinationCoordinate = new Coordinate(order.getDestinationLng(), order.getDestinationLat());
        TravelEstimate pickupToDestination = travelEstimateService.estimatePickupToDestination(
                pickupCoordinate, destinationCoordinate);
        List<String> manualReviewReasons = new ArrayList<>();
        if (pickupToDestination.degraded()) {
            manualReviewReasons.add("MAP_ROUTE_UNAVAILABLE");
        }
        Map<UUID, CandidateTravelEstimates> candidateEstimates = new HashMap<>();
        Map<UUID, TaskInsertionPlan> insertionPlans = new HashMap<>();
        List<DispatchEvaluateRequest.CandidateTask> candidates = toCandidateTasks(
                order, ruleSet, pickupCoordinate, pickupToDestination, candidateEstimates,
                insertionPlans, manualReviewReasons);
        DispatchEvaluateRequest request = new DispatchEvaluateRequest(
                new DispatchEvaluateRequest.Order(
                        order.getId(),
                        order.getPassengerCount(),
                        order.getRequestType(),
                        order.getRequestedDepartureAt(),
                        order.getBoardingStopId(),
                        order.getAlightingStopId()),
                toRuleSet(ruleSet),
                candidates);
        return new Assembly(
                request,
                Map.copyOf(candidateEstimates),
                Map.copyOf(insertionPlans),
                pickupToDestination,
                manualReviewReasons.isEmpty() ? null : manualReviewReasons.getFirst());
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
            DispatchRuleSet ruleSet,
            Coordinate pickupCoordinate,
            TravelEstimate pickupToDestination,
            Map<UUID, CandidateTravelEstimates> candidateEstimates,
            Map<UUID, TaskInsertionPlan> insertionPlans,
            List<String> manualReviewReasons) {
        List<Vehicle> dispatchableVehicles = vehicleRepository.findAll().stream()
                .filter(Vehicle::isDispatchable)
                .toList();
        Map<UUID, Vehicle> vehiclesById = new HashMap<>();
        for (Vehicle vehicle : dispatchableVehicles) {
            vehiclesById.put(vehicle.getId(), vehicle);
        }

        List<VehicleTask> tasks = vehicleTaskRepository.findAllByOrderByPlannedStartAtAsc();
        Set<UUID> vehiclesWithActiveTasks = tasks.stream()
                .filter(this::isInsertable)
                .map(VehicleTask::getVehicleId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<Vehicle> idleVehicles = dispatchableVehicles.stream()
                .filter(vehicle -> "IDLE".equals(vehicle.getCurrentStatus()))
                .filter(vehicle -> !vehiclesWithActiveTasks.contains(vehicle.getId()))
                .toList();
        List<Driver> drivers = driverRepository.findAll().stream()
                .filter(driver -> "QUALIFIED".equals(driver.getQualificationStatus()))
                .filter(driver -> "AVAILABLE".equals(driver.getCurrentStatus()))
                .toList();

        int count = Math.min(idleVehicles.size(), drivers.size());
        List<DispatchEvaluateRequest.CandidateTask> candidates = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            Vehicle vehicle = idleVehicles.get(index);
            addCandidateWithTravelEstimate(
                    candidates, candidateEstimates, manualReviewReasons, order, ruleSet, vehicle,
                    pickupCoordinate, pickupToDestination, insertionPlans, null);
        }

        for (VehicleTask task : tasks) {
            Vehicle vehicle = vehiclesById.get(task.getVehicleId());
            if (vehicle != null && isInsertable(task)) {
                addCandidateWithTravelEstimate(
                        candidates, candidateEstimates, manualReviewReasons, order, ruleSet, vehicle,
                        pickupCoordinate, pickupToDestination, insertionPlans, task);
            }
        }
        return candidates;
    }

    private DispatchEvaluateRequest.CandidateTask toNewTaskCandidate(
            RideOrder order,
            DispatchRuleSet ruleSet,
            Vehicle vehicle,
            TravelEstimate vehicleToPickup) {
        int estimatedWaitMinutes = estimateWaitMinutes(vehicleToPickup);
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
                utilization(order, vehicle),
                "NEW_TASK",
                1,
                null,
                new BigDecimal("100.00"));
    }

    private DispatchEvaluateRequest.CandidateTask toExistingTaskCandidate(
            RideOrder order,
            DispatchRuleSet ruleSet,
            VehicleTask task,
            Vehicle vehicle,
            TravelEstimate vehicleToPickup,
            TaskInsertionPlan plan) {
        List<DispatchEvaluateRequest.PlannedStop> plannedStops = new ArrayList<>();
        for (int index = 0; index < plan.orderedStops().size(); index++) {
            TaskInsertionPlan.PlannedTaskStop stop = plan.orderedStops().get(index);
            plannedStops.add(new DispatchEvaluateRequest.PlannedStop(
                    stop.virtualStopId(), index + 1, stop.plannedArrivalAt(), stop.stopType()));
        }

        return new DispatchEvaluateRequest.CandidateTask(
                task.getId(),
                task.getVehicleId(),
                Math.max(0, vehicle.getCapacity() - Math.max(0,
                        plan.peakOccupiedSeats() - order.getPassengerCount())),
                plannedStops.isEmpty() ? order.getBoardingStopId() : plannedStops.getFirst().stopId(),
                plannedStops,
                plan.estimatedWaitMinutes(),
                plan.maxPassengerDetourMinutes(),
                plan.feasible() ? "SAME_DIRECTION" : "UNKNOWN",
                plan.utilizationAfterInsert(),
                "EXISTING_TASK",
                0,
                plan.rejectionReason(),
                BigDecimal.valueOf(plan.taskDisruptionScore()));
    }

    private boolean isInsertable(VehicleTask task) {
        return task.getStatus() == TaskStatus.DISPATCHED || task.getStatus() == TaskStatus.IN_PROGRESS;
    }

    private void addCandidateWithTravelEstimate(
            List<DispatchEvaluateRequest.CandidateTask> candidates,
            Map<UUID, CandidateTravelEstimates> candidateEstimates,
            List<String> manualReviewReasons,
            RideOrder order,
            DispatchRuleSet ruleSet,
            Vehicle vehicle,
            Coordinate pickupCoordinate,
            TravelEstimate pickupToDestination,
            Map<UUID, TaskInsertionPlan> insertionPlans,
            VehicleTask existingTask) {
        try {
            TravelEstimate vehicleToPickup = travelEstimateService.estimateVehicleToPickup(vehicle.getId(), pickupCoordinate);
            if (vehicleToPickup.degraded() && !manualReviewReasons.contains("MAP_ROUTE_UNAVAILABLE")) {
                manualReviewReasons.add("MAP_ROUTE_UNAVAILABLE");
            }
            TaskInsertionPlan insertionPlan = existingTask == null
                    ? null
                    : replanExistingTask(order, ruleSet, vehicle, existingTask);
            DispatchEvaluateRequest.CandidateTask candidate = existingTask == null
                    ? toNewTaskCandidate(order, ruleSet, vehicle, vehicleToPickup)
                    : toExistingTaskCandidate(
                            order, ruleSet, existingTask, vehicle, vehicleToPickup, insertionPlan);
            candidates.add(candidate);
            candidateEstimates.put(candidate.taskId(), new CandidateTravelEstimates(vehicleToPickup, pickupToDestination));
            if (insertionPlan != null) {
                insertionPlans.put(existingTask.getId(), insertionPlan);
            }
        } catch (TravelEstimateService.MissingVehicleLocationSnapshotException exception) {
            if (!manualReviewReasons.contains("VEHICLE_LOCATION_SNAPSHOT_MISSING")) {
                manualReviewReasons.add("VEHICLE_LOCATION_SNAPSHOT_MISSING");
            }
        }
    }

    private Map<UUID, Coordinate> stopCoordinates(RideOrder newOrder, VehicleTask task) {
        Map<UUID, Coordinate> coordinates = new HashMap<>();
        addOrderCoordinates(coordinates, newOrder);
        for (UUID orderId : task.activeOrderIds()) {
            rideOrderRepository.findById(orderId).ifPresent(order -> addOrderCoordinates(coordinates, order));
        }
        return coordinates;
    }

    private void addOrderCoordinates(Map<UUID, Coordinate> coordinates, RideOrder order) {
        coordinates.putIfAbsent(
                order.getBoardingStopId(), new Coordinate(order.getOriginLng(), order.getOriginLat()));
        coordinates.putIfAbsent(
                order.getAlightingStopId(), new Coordinate(order.getDestinationLng(), order.getDestinationLat()));
    }

    private Map<UUID, Integer> passengerCounts(RideOrder newOrder, VehicleTask task) {
        Map<UUID, Integer> passengerCounts = new HashMap<>();
        passengerCounts.put(newOrder.getId(), newOrder.getPassengerCount());
        for (UUID orderId : task.activeOrderIds()) {
            rideOrderRepository.findById(orderId)
                    .ifPresent(order -> passengerCounts.put(orderId, order.getPassengerCount()));
        }
        return passengerCounts;
    }

    private int estimateWaitMinutes(TravelEstimate estimate) {
        return Math.max(1, (int) Math.ceil(estimate.durationSeconds() / 60D));
    }

    private static UUID syntheticTaskId(UUID vehicleId) {
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

    public record CandidateTravelEstimates(
            TravelEstimate vehicleToPickup,
            TravelEstimate pickupToDestination) {
    }

    public record Assembly(
            DispatchEvaluateRequest request,
            Map<UUID, CandidateTravelEstimates> candidateEstimates,
            Map<UUID, TaskInsertionPlan> insertionPlans,
            TravelEstimate pickupToDestination,
            String manualReviewReason) {

        public static Assembly requiresManualReview(DispatchEvaluateRequest request, String reason) {
            return new Assembly(request, Map.of(), Map.of(), null, reason);
        }

        public boolean requiresManualReview() {
            return manualReviewReason != null;
        }

        public CandidateTravelEstimates estimatesFor(DispatchEvaluateResponse.BestPlan bestPlan) {
            if (bestPlan == null) {
                return pickupToDestination == null ? null : new CandidateTravelEstimates(null, pickupToDestination);
            }
            CandidateTravelEstimates byTask = bestPlan.taskId() == null ? null : candidateEstimates.get(bestPlan.taskId());
            if (byTask != null) {
                return byTask;
            }
            return candidateEstimates.entrySet().stream()
                    .filter(entry -> entry.getKey().equals(syntheticTaskId(bestPlan.vehicleId())))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(pickupToDestination == null ? null : new CandidateTravelEstimates(null, pickupToDestination));
        }

        public boolean isNewTaskCandidate(DispatchEvaluateResponse.BestPlan bestPlan) {
            return bestPlan != null
                    && bestPlan.taskId() != null
                    && bestPlan.vehicleId() != null
                    && bestPlan.taskId().equals(syntheticTaskId(bestPlan.vehicleId()));
        }

        public TaskInsertionPlan insertionPlanFor(UUID taskId) {
            return insertionPlans.get(taskId);
        }
    }
}
