package com.idavy.drtops.domain.order;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RideOrderRepository extends JpaRepository<RideOrder, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select rideOrder from RideOrder rideOrder where rideOrder.id = :id")
    Optional<RideOrder> findByIdForUpdate(@Param("id") UUID id);
}
