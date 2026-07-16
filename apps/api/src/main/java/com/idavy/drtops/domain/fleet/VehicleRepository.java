package com.idavy.drtops.domain.fleet;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VehicleRepository extends JpaRepository<Vehicle, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select vehicle from Vehicle vehicle where vehicle.id = :id")
    Optional<Vehicle> findByIdForLocationUpdate(@Param("id") UUID id);
}
