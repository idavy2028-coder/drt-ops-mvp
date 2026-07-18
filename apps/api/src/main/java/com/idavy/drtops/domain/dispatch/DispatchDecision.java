package com.idavy.drtops.domain.dispatch;

import com.idavy.drtops.integration.algorithm.DispatchEvaluateResponse;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "dispatch_decisions")
public class DispatchDecision {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID rideOrderId;

    @Column(nullable = false, length = 40)
    private String decisionResult;

    @Column(nullable = false)
    private int candidateCount;

    private UUID bestVehicleId;

    private UUID bestTaskId;

    @Column(precision = 6, scale = 2)
    private BigDecimal score;

    private Integer estimatedWaitMinutes;

    private Integer estimatedDetourMinutes;

    @Column(length = 40)
    private String mapProvider;

    @Column(nullable = false)
    private boolean mapDegraded;

    @Column(length = 100)
    private String mapDegradedReason;

    private Integer vehicleToPickupDistanceMeters;

    private Integer vehicleToPickupDurationSeconds;

    private Integer pickupToDestinationDistanceMeters;

    private Integer pickupToDestinationDurationSeconds;

    @Column(nullable = false, length = 1000)
    @JdbcTypeCode(SqlTypes.JSON)
    private String rejectedReasonsJson;

    @Column(nullable = false, length = 1000)
    @JdbcTypeCode(SqlTypes.JSON)
    private String explanationJson;

    @Column(nullable = false, length = 40)
    private String algorithmVersion;

    @Column(nullable = false, length = 40)
    private String actorType;

    @Column(nullable = false, length = 80)
    private String actorId;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    protected DispatchDecision() {
    }

    private DispatchDecision(
            UUID rideOrderId,
            String decisionResult,
            int candidateCount,
            UUID bestVehicleId,
            UUID bestTaskId,
            BigDecimal score,
            Integer estimatedWaitMinutes,
            Integer estimatedDetourMinutes,
            CandidateTaskAssembler.CandidateTravelEstimates travelEstimates,
            String rejectedReasonsJson,
            String explanationJson,
            String algorithmVersion,
            String actorType,
            String actorId) {
        this.id = UUID.randomUUID();
        this.rideOrderId = rideOrderId;
        this.decisionResult = decisionResult;
        this.candidateCount = candidateCount;
        this.bestVehicleId = bestVehicleId;
        this.bestTaskId = bestTaskId;
        this.score = score;
        this.estimatedWaitMinutes = estimatedWaitMinutes;
        this.estimatedDetourMinutes = estimatedDetourMinutes;
        this.mapProvider = provider(travelEstimates);
        this.mapDegraded = isDegraded(travelEstimates);
        this.mapDegradedReason = degradedReason(travelEstimates);
        this.vehicleToPickupDistanceMeters = distance(travelEstimates == null ? null : travelEstimates.vehicleToPickup());
        this.vehicleToPickupDurationSeconds = duration(travelEstimates == null ? null : travelEstimates.vehicleToPickup());
        this.pickupToDestinationDistanceMeters = distance(travelEstimates == null ? null : travelEstimates.pickupToDestination());
        this.pickupToDestinationDurationSeconds = duration(travelEstimates == null ? null : travelEstimates.pickupToDestination());
        this.rejectedReasonsJson = rejectedReasonsJson;
        this.explanationJson = explanationJson;
        this.algorithmVersion = algorithmVersion;
        this.actorType = actorType;
        this.actorId = actorId;
        this.createdAt = OffsetDateTime.now();
    }

    public static DispatchDecision manualReview(
            UUID rideOrderId,
            int candidateCount,
            UUID bestVehicleId,
            UUID bestTaskId,
            String algorithmVersion,
            String actorType,
            String actorId) {
        return new DispatchDecision(
                rideOrderId,
                "PENDING_MANUAL_REVIEW",
                candidateCount,
                bestVehicleId,
                bestTaskId,
                null,
                null,
                null,
                null,
                "[]",
                "{}",
                algorithmVersion,
                actorType,
                actorId);
    }

    public static DispatchDecision fromAlgorithm(
            UUID rideOrderId,
            DispatchEvaluateResponse response,
            UUID persistedTaskId,
            String rejectedReasonsJson,
            String explanationJson,
            String algorithmVersion,
            String actorType,
            String actorId) {
        return fromAlgorithm(
                rideOrderId, response, persistedTaskId, null, rejectedReasonsJson, explanationJson,
                algorithmVersion, actorType, actorId);
    }

    public static DispatchDecision fromAlgorithm(
            UUID rideOrderId,
            DispatchEvaluateResponse response,
            UUID persistedTaskId,
            CandidateTaskAssembler.CandidateTravelEstimates travelEstimates,
            String rejectedReasonsJson,
            String explanationJson,
            String algorithmVersion,
            String actorType,
            String actorId) {
        DispatchEvaluateResponse.BestPlan bestPlan = response.bestPlan();
        return new DispatchDecision(
                rideOrderId,
                response.decision().name(),
                response.candidateCount(),
                bestPlan == null ? null : bestPlan.vehicleId(),
                persistedTaskId,
                bestPlan == null ? null : bestPlan.score(),
                bestPlan == null ? null : bestPlan.estimatedWaitMinutes(),
                bestPlan == null ? null : bestPlan.estimatedDetourMinutes(),
                travelEstimates,
                rejectedReasonsJson,
                explanationJson,
                algorithmVersion,
                actorType,
                actorId);
    }

    public UUID getId() {
        return id;
    }

    public UUID getRideOrderId() {
        return rideOrderId;
    }

    public String getDecisionResult() {
        return decisionResult;
    }

    public UUID getBestVehicleId() {
        return bestVehicleId;
    }

    public UUID getBestTaskId() {
        return bestTaskId;
    }

    public int getCandidateCount() {
        return candidateCount;
    }

    public Integer getEstimatedWaitMinutes() {
        return estimatedWaitMinutes;
    }

    public Integer getEstimatedDetourMinutes() {
        return estimatedDetourMinutes;
    }

    public String getMapProvider() {
        return mapProvider;
    }

    public boolean isMapDegraded() {
        return mapDegraded;
    }

    public String getMapDegradedReason() {
        return mapDegradedReason;
    }

    public Integer getVehicleToPickupDistanceMeters() {
        return vehicleToPickupDistanceMeters;
    }

    public Integer getVehicleToPickupDurationSeconds() {
        return vehicleToPickupDurationSeconds;
    }

    public Integer getPickupToDestinationDistanceMeters() {
        return pickupToDestinationDistanceMeters;
    }

    public Integer getPickupToDestinationDurationSeconds() {
        return pickupToDestinationDurationSeconds;
    }

    private static boolean isDegraded(CandidateTaskAssembler.CandidateTravelEstimates estimates) {
        return estimates != null && ((estimates.vehicleToPickup() != null && estimates.vehicleToPickup().degraded())
                || (estimates.pickupToDestination() != null && estimates.pickupToDestination().degraded()));
    }

    private static String provider(CandidateTaskAssembler.CandidateTravelEstimates estimates) {
        if (estimates == null) {
            return null;
        }
        if (estimates.vehicleToPickup() != null) {
            return estimates.vehicleToPickup().provider();
        }
        return estimates.pickupToDestination() == null ? null : estimates.pickupToDestination().provider();
    }

    private static String degradedReason(CandidateTaskAssembler.CandidateTravelEstimates estimates) {
        if (estimates == null) {
            return null;
        }
        if (estimates.vehicleToPickup() != null && estimates.vehicleToPickup().degraded()) {
            return estimates.vehicleToPickup().degradedReason();
        }
        return estimates.pickupToDestination() != null && estimates.pickupToDestination().degraded()
                ? estimates.pickupToDestination().degradedReason() : null;
    }

    private static Integer distance(TravelEstimate estimate) {
        return estimate == null ? null : estimate.distanceMeters();
    }

    private static Integer duration(TravelEstimate estimate) {
        return estimate == null ? null : estimate.durationSeconds();
    }
}
