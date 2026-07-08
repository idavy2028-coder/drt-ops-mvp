package com.idavy.drtops.domain.dispatch;

import com.idavy.drtops.domain.fleet.Driver;
import com.idavy.drtops.domain.fleet.DriverRepository;
import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import com.idavy.drtops.domain.order.RideOrder;
import com.idavy.drtops.integration.algorithm.DispatchEvaluateRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class CandidateTaskAssembler {

    private final VehicleRepository vehicleRepository;
    private final DriverRepository driverRepository;

    public CandidateTaskAssembler(VehicleRepository vehicleRepository, DriverRepository driverRepository) {
        this.vehicleRepository = vehicleRepository;
        this.driverRepository = driverRepository;
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
        List<Vehicle> vehicles = vehicleRepository.findAll().stream()
                .filter(Vehicle::isDispatchable)
                .filter(vehicle -> "IDLE".equals(vehicle.getCurrentStatus()))
                .toList();
        List<Driver> drivers = driverRepository.findAll().stream()
                .filter(driver -> "QUALIFIED".equals(driver.getQualificationStatus()))
                .filter(driver -> "AVAILABLE".equals(driver.getCurrentStatus()))
                .toList();

        int count = Math.min(vehicles.size(), drivers.size());
        List<DispatchEvaluateRequest.CandidateTask> candidates = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            Vehicle vehicle = vehicles.get(index);
            candidates.add(toCandidateTask(order, ruleSet, vehicle));
        }
        return candidates;
    }

    private DispatchEvaluateRequest.CandidateTask toCandidateTask(
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
