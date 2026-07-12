package com.idavy.drtops.domain.dispatch;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class DispatchDecisionReadService {

    private final DispatchDecisionRepository dispatchDecisionRepository;

    public DispatchDecisionReadService(DispatchDecisionRepository dispatchDecisionRepository) {
        this.dispatchDecisionRepository = dispatchDecisionRepository;
    }

    public List<DecisionSummary> list() {
        return dispatchDecisionRepository.findDecisionSummaries().stream()
                .map(summary -> new DecisionSummary(
                        summary.getId(),
                        summary.getRideOrderId(),
                        summary.getDecisionResult(),
                        summary.getCandidateCount(),
                        summary.getBestVehicleId(),
                        summary.getBestTaskId(),
                        summary.getEstimatedWaitMinutes(),
                        summary.getEstimatedDetourMinutes(),
                        summary.getCreatedAt()))
                .toList();
    }

    public record DecisionSummary(
            UUID id,
            UUID rideOrderId,
            String decisionResult,
            int candidateCount,
            UUID bestVehicleId,
            UUID bestTaskId,
            Integer estimatedWaitMinutes,
            Integer estimatedDetourMinutes,
            OffsetDateTime createdAt) {
    }
}
