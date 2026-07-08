package com.idavy.drtops.domain.dispatch;

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
            String algorithmVersion,
            String actorType,
            String actorId) {
        this.id = UUID.randomUUID();
        this.rideOrderId = rideOrderId;
        this.decisionResult = decisionResult;
        this.candidateCount = candidateCount;
        this.bestVehicleId = bestVehicleId;
        this.bestTaskId = bestTaskId;
        this.rejectedReasonsJson = "[]";
        this.explanationJson = "{}";
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
