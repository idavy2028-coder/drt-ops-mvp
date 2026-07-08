package com.idavy.drtops.domain.dispatch;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DispatchRuleSetRepository extends JpaRepository<DispatchRuleSet, UUID> {
}
