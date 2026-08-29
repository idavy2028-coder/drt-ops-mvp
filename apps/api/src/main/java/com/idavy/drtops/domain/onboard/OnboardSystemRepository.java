package com.idavy.drtops.domain.onboard;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OnboardSystemRepository extends JpaRepository<OnboardSystem, UUID> {

    @Query(value = """
            select * from onboard_systems
            where vehicle_id = :vehicleId and status = 'ACTIVE'
            """, nativeQuery = true)
    Optional<OnboardSystem> findActiveByVehicleId(@Param("vehicleId") UUID vehicleId);

    List<OnboardSystem> findHistoryByVehicleIdOrderByCreatedAtAsc(UUID vehicleId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select system from OnboardSystem system where system.id = :id")
    Optional<OnboardSystem> findLockedById(@Param("id") UUID id);
}
