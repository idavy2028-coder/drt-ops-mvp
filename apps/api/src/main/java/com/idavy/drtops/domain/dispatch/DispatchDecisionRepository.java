package com.idavy.drtops.domain.dispatch;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DispatchDecisionRepository extends JpaRepository<DispatchDecision, UUID> {

    List<DispatchDecision> findByRideOrderId(UUID rideOrderId);

    List<DispatchDecision> findByDecisionResultInOrderByCreatedAtAsc(Collection<String> decisionResults);
}
