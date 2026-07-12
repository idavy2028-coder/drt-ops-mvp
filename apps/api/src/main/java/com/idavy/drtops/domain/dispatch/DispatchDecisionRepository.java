package com.idavy.drtops.domain.dispatch;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DispatchDecisionRepository extends JpaRepository<DispatchDecision, UUID> {

    List<DispatchDecision> findByRideOrderId(UUID rideOrderId);

    List<DispatchDecision> findByDecisionResultInOrderByCreatedAtAsc(Collection<String> decisionResults);

    @Query("""
            select decision.id as id,
                   decision.rideOrderId as rideOrderId,
                   decision.decisionResult as decisionResult,
                   decision.candidateCount as candidateCount,
                   decision.bestVehicleId as bestVehicleId,
                   decision.bestTaskId as bestTaskId,
                   decision.estimatedWaitMinutes as estimatedWaitMinutes,
                   decision.estimatedDetourMinutes as estimatedDetourMinutes,
                   decision.createdAt as createdAt
            from DispatchDecision decision
            order by decision.createdAt desc
            """)
    List<DecisionSummaryProjection> findDecisionSummaries();

    interface DecisionSummaryProjection {

        UUID getId();

        UUID getRideOrderId();

        String getDecisionResult();

        int getCandidateCount();

        UUID getBestVehicleId();

        UUID getBestTaskId();

        Integer getEstimatedWaitMinutes();

        Integer getEstimatedDetourMinutes();

        java.time.OffsetDateTime getCreatedAt();
    }
}
