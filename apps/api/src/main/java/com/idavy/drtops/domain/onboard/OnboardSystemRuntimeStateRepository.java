package com.idavy.drtops.domain.onboard;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OnboardSystemRuntimeStateRepository
        extends JpaRepository<OnboardSystemRuntimeState, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select runtime
            from OnboardSystemRuntimeState runtime
            where runtime.onboardSystemId = :onboardSystemId
            """)
    Optional<OnboardSystemRuntimeState> findLockedByOnboardSystemId(
            @Param("onboardSystemId") UUID onboardSystemId);
}
