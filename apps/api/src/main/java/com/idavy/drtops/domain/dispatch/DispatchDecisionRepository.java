package com.idavy.drtops.domain.dispatch;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DispatchDecisionRepository extends JpaRepository<DispatchDecision, UUID> {
}
