package com.idavy.drtops.domain.dispatch;

import com.idavy.drtops.integration.algorithm.DispatchEvaluateResponse;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

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

    @Column(nullable = false, length = 1000)
    private String rejectedReasonsJson;

    @Column(nullable = false, length = 1000)
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
        DispatchEvaluateResponse.BestPlan bestPlan = response.bestPlan();
        return new DispatchDecision(
                rideOrderId,
                response.decision().name(),
                response.candidateCount(),
                bestPlan == null ? null : bestPlan.vehicleId(),
                persistedTaskId != null ? persistedTaskId : bestPlan == null ? null : bestPlan.taskId(),
                bestPlan == null ? null : bestPlan.score(),
                bestPlan == null ? null : bestPlan.estimatedWaitMinutes(),
                bestPlan == null ? null : bestPlan.estimatedDetourMinutes(),
                rejectedReasonsJson,
                explanationJson,
                algorithmVersion,
                actorType,
                actorId);
    }

    public UUID getId() {
        return id;
    }

    public String getDecisionResult() {
        return decisionResult;
    }
}
