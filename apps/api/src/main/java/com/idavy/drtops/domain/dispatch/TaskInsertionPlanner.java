package com.idavy.drtops.domain.dispatch;

import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.location.VehicleLocationSnapshotView;
import com.idavy.drtops.domain.map.Coordinate;
import com.idavy.drtops.domain.order.RideOrder;
import com.idavy.drtops.domain.task.TaskStop;
import com.idavy.drtops.domain.task.VehicleTask;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class TaskInsertionPlanner {

    private final TravelEstimateService travelEstimateService;

    public TaskInsertionPlanner(TravelEstimateService travelEstimateService) {
        this.travelEstimateService = travelEstimateService;
    }

    public TaskInsertionPlan plan(
            RideOrder order,
            Vehicle vehicle,
            VehicleTask task,
            DispatchRuleSet ruleSet,
            Map<UUID, Coordinate> stopCoordinates,
            Map<UUID, Integer> passengerCounts) {
        VehicleLocationSnapshotView snapshot = VehicleLocationSnapshotView.from(vehicle);
        if (snapshot == null) {
            return rejected("VEHICLE_LOCATION_SNAPSHOT_MISSING", vehicle.getCapacity());
        }
        Coordinate currentLocation = new Coordinate(snapshot.longitude(), snapshot.latitude());
        List<StopSeed> remaining = task.getStops().stream()
                .filter(stop -> !stop.isExecutionComplete())
                .map(StopSeed::existing)
                .toList();
        if (!stopCoordinates.containsKey(order.getBoardingStopId())
                || !stopCoordinates.containsKey(order.getAlightingStopId())
                || remaining.stream().anyMatch(stop -> !stopCoordinates.containsKey(stop.virtualStopId()))) {
            return rejected("ROUTE_INSERTION_UNAVAILABLE", vehicle.getCapacity());
        }

        RouteSchedule baseline = schedule(currentLocation, remaining, stopCoordinates, order.getRequestedDepartureAt());
        if (baseline.degraded()) {
            return rejected("MAP_ROUTE_UNAVAILABLE", vehicle.getCapacity(), baseline.degradedReason());
        }

        List<TaskInsertionPlan> alternatives = new ArrayList<>();
        List<Integer> boardingIndexes = boardingIndexes(remaining, order.getBoardingStopId());
        for (int boardingIndex : boardingIndexes) {
            for (int alightingIndex = boardingIndex + 1; alightingIndex <= remaining.size() + 1; alightingIndex++) {
                List<StopSeed> inserted = new ArrayList<>(remaining);
                inserted.add(boardingIndex, StopSeed.newStop(
                        order.getBoardingStopId(), order.getId(), "BOARDING"));
                inserted.add(alightingIndex, StopSeed.newStop(
                        order.getAlightingStopId(), order.getId(), "ALIGHTING"));
                alternatives.add(evaluate(
                        order, vehicle, task, ruleSet, stopCoordinates, passengerCounts,
                        currentLocation, baseline, inserted, boardingIndex, alightingIndex));
            }
        }

        return alternatives.stream()
                .filter(TaskInsertionPlan::feasible)
                .min(Comparator.comparingInt(TaskInsertionPlan::maxPassengerDetourMinutes)
                        .thenComparingInt(TaskInsertionPlan::plannedRouteDurationSeconds)
                        .thenComparingInt(TaskInsertionPlan::boardingIndex)
                        .thenComparingInt(TaskInsertionPlan::alightingIndex))
                .orElseGet(() -> primaryRejection(alternatives, vehicle.getCapacity()));
    }

    private List<Integer> boardingIndexes(List<StopSeed> remaining, UUID boardingStopId) {
        for (int index = 0; index < remaining.size(); index++) {
            StopSeed stop = remaining.get(index);
            if (!"BOARDING".equals(stop.stopType()) || !boardingStopId.equals(stop.virtualStopId())) {
                continue;
            }
            int groupEnd = index + 1;
            while (groupEnd < remaining.size()) {
                StopSeed grouped = remaining.get(groupEnd);
                if (!"BOARDING".equals(grouped.stopType())
                        || !boardingStopId.equals(grouped.virtualStopId())) {
                    break;
                }
                groupEnd++;
            }
            return List.of(groupEnd);
        }
        List<Integer> indexes = new ArrayList<>();
        for (int index = 0; index <= remaining.size(); index++) {
            indexes.add(index);
        }
        return indexes;
    }

    private TaskInsertionPlan evaluate(
            RideOrder newOrder,
            Vehicle vehicle,
            VehicleTask task,
            DispatchRuleSet ruleSet,
            Map<UUID, Coordinate> coordinates,
            Map<UUID, Integer> passengerCounts,
            Coordinate currentLocation,
            RouteSchedule baseline,
            List<StopSeed> inserted,
            int boardingIndex,
            int alightingIndex) {
        RouteSchedule planned = schedule(currentLocation, inserted, coordinates, newOrder.getRequestedDepartureAt());
        if (planned.degraded()) {
            return rejected("MAP_ROUTE_UNAVAILABLE", vehicle.getCapacity(), planned.degradedReason());
        }

        int waitMinutes = positiveMinutesBetween(
                newOrder.getRequestedDepartureAt(), planned.arrivals().get(boardingIndex));
        int maxDetourMinutes = maximumPassengerDetour(
                newOrder, inserted, planned, baseline, coordinates);
        int peakOccupiedSeats = peakOccupiedSeats(task, inserted, passengerCounts);
        String rejectionReason = null;
        if (waitMinutes > ruleSet.getMaxWaitMinutes()) {
            rejectionReason = "WAIT_TIME_EXCEEDED";
        } else if (maxDetourMinutes > ruleSet.getMaxDetourMinutes()) {
            rejectionReason = "DETOUR_TIME_EXCEEDED";
        } else if (peakOccupiedSeats > vehicle.getCapacity()) {
            rejectionReason = "INSUFFICIENT_CAPACITY";
        }

        List<TaskInsertionPlan.PlannedTaskStop> plannedStops = new ArrayList<>();
        for (int index = 0; index < inserted.size(); index++) {
            StopSeed stop = inserted.get(index);
            plannedStops.add(new TaskInsertionPlan.PlannedTaskStop(
                    stop.virtualStopId(), stop.rideOrderId(), stop.stopType(),
                    planned.arrivals().get(index), stop.existingStopId()));
        }
        int disruptionScore = Math.max(0, 100 - maxDetourMinutes * 5 - Math.max(0, inserted.size() - 2) * 2);
        return new TaskInsertionPlan(
                rejectionReason == null,
                rejectionReason,
                List.copyOf(plannedStops),
                boardingIndex,
                alightingIndex,
                waitMinutes,
                maxDetourMinutes,
                peakOccupiedSeats,
                BigDecimal.valueOf(peakOccupiedSeats)
                        .divide(BigDecimal.valueOf(vehicle.getCapacity()), 2, RoundingMode.HALF_UP),
                baseline.durationSeconds(),
                planned.durationSeconds(),
                disruptionScore,
                false,
                null);
    }

    private RouteSchedule schedule(
            Coordinate origin,
            List<StopSeed> stops,
            Map<UUID, Coordinate> coordinates,
            OffsetDateTime startAt) {
        List<OffsetDateTime> arrivals = new ArrayList<>();
        Coordinate previous = origin;
        OffsetDateTime cursor = startAt;
        int durationSeconds = 0;
        for (StopSeed stop : stops) {
            TravelEstimate estimate = travelEstimateService.estimateBetween(previous, coordinates.get(stop.virtualStopId()));
            if (estimate.degraded()) {
                return new RouteSchedule(List.of(), durationSeconds, true, estimate.degradedReason());
            }
            durationSeconds += estimate.durationSeconds();
            cursor = cursor.plusSeconds(estimate.durationSeconds());
            arrivals.add(cursor);
            previous = coordinates.get(stop.virtualStopId());
        }
        return new RouteSchedule(List.copyOf(arrivals), durationSeconds, false, null);
    }

    private int maximumPassengerDetour(
            RideOrder newOrder,
            List<StopSeed> stops,
            RouteSchedule planned,
            RouteSchedule baseline,
            Map<UUID, Coordinate> coordinates) {
        int maximum = 0;
        Map<UUID, OffsetDateTime> baselineAlightingTimes = new HashMap<>();
        int baselineIndex = 0;
        for (StopSeed stop : stops) {
            if (stop.existingStopId() == null) {
                continue;
            }
            if ("ALIGHTING".equals(stop.stopType())) {
                baselineAlightingTimes.put(stop.rideOrderId(), baseline.arrivals().get(baselineIndex));
            }
            baselineIndex++;
        }
        for (int index = 0; index < stops.size(); index++) {
            StopSeed stop = stops.get(index);
            if (!"ALIGHTING".equals(stop.stopType()) || stop.rideOrderId().equals(newOrder.getId())) {
                continue;
            }
            OffsetDateTime baselineArrival = baselineAlightingTimes.get(stop.rideOrderId());
            if (baselineArrival != null) {
                maximum = Math.max(maximum, positiveMinutesBetween(baselineArrival, planned.arrivals().get(index)));
            }
        }

        int boardingIndex = indexOf(stops, newOrder.getId(), "BOARDING");
        int alightingIndex = indexOf(stops, newOrder.getId(), "ALIGHTING");
        TravelEstimate direct = travelEstimateService.estimateBetween(
                coordinates.get(newOrder.getBoardingStopId()), coordinates.get(newOrder.getAlightingStopId()));
        long inVehicleSeconds = Duration.between(
                planned.arrivals().get(boardingIndex), planned.arrivals().get(alightingIndex)).getSeconds();
        maximum = Math.max(maximum, ceilMinutes(Math.max(0, inVehicleSeconds - direct.durationSeconds())));
        return maximum;
    }

    private int peakOccupiedSeats(
            VehicleTask task,
            List<StopSeed> stops,
            Map<UUID, Integer> passengerCounts) {
        Set<UUID> boarded = new HashSet<>();
        Set<UUID> alighted = new HashSet<>();
        for (TaskStop stop : task.getStops()) {
            if ("BOARDING".equals(stop.getStopType()) && "BOARDED".equals(stop.getStatus())) {
                boarded.add(stop.getRideOrderId());
            }
            if ("ALIGHTING".equals(stop.getStopType()) && "ALIGHTED".equals(stop.getStatus())) {
                alighted.add(stop.getRideOrderId());
            }
        }
        int occupied = boarded.stream()
                .filter(orderId -> !alighted.contains(orderId))
                .mapToInt(orderId -> passengerCounts.getOrDefault(orderId, 0))
                .sum();
        int peak = occupied;
        for (StopSeed stop : stops) {
            int passengers = passengerCounts.getOrDefault(stop.rideOrderId(), 0);
            occupied += "BOARDING".equals(stop.stopType()) ? passengers : -passengers;
            peak = Math.max(peak, occupied);
        }
        return peak;
    }

    private int indexOf(List<StopSeed> stops, UUID orderId, String type) {
        for (int index = 0; index < stops.size(); index++) {
            StopSeed stop = stops.get(index);
            if (orderId.equals(stop.rideOrderId()) && type.equals(stop.stopType())) {
                return index;
            }
        }
        throw new IllegalArgumentException("order stop is missing from insertion route");
    }

    private int positiveMinutesBetween(OffsetDateTime from, OffsetDateTime to) {
        return ceilMinutes(Math.max(0, Duration.between(from, to).getSeconds()));
    }

    private int ceilMinutes(long seconds) {
        return (int) Math.ceil(seconds / 60D);
    }

    private TaskInsertionPlan primaryRejection(List<TaskInsertionPlan> alternatives, int capacity) {
        return alternatives.stream()
                .filter(plan -> "MAP_ROUTE_UNAVAILABLE".equals(plan.rejectionReason()))
                .findFirst()
                .orElseGet(() -> alternatives.stream().findFirst()
                        .orElseGet(() -> rejected("ROUTE_INSERTION_UNAVAILABLE", capacity)));
    }

    private TaskInsertionPlan rejected(String reason, int capacity) {
        return rejected(reason, capacity, null);
    }

    private TaskInsertionPlan rejected(String reason, int capacity, String degradedReason) {
        return new TaskInsertionPlan(
                false, reason, List.of(), -1, -1, 0, 0, 0,
                BigDecimal.ZERO.setScale(2), 0, 0, 0,
                "MAP_ROUTE_UNAVAILABLE".equals(reason), degradedReason);
    }

    private record StopSeed(
            UUID virtualStopId,
            UUID rideOrderId,
            String stopType,
            UUID existingStopId) {

        static StopSeed existing(TaskStop stop) {
            return new StopSeed(
                    stop.getVirtualStopId(), stop.getRideOrderId(), stop.getStopType(), stop.getId());
        }

        static StopSeed newStop(UUID virtualStopId, UUID rideOrderId, String stopType) {
            return new StopSeed(virtualStopId, rideOrderId, stopType, null);
        }
    }

    private record RouteSchedule(
            List<OffsetDateTime> arrivals,
            int durationSeconds,
            boolean degraded,
            String degradedReason) {
    }
}
