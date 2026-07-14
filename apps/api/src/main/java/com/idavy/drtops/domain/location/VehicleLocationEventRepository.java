package com.idavy.drtops.domain.location;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleLocationEventRepository extends JpaRepository<VehicleLocationEvent, UUID> {

    Optional<VehicleLocationEvent> findByIdempotencyKey(UUID idempotencyKey);

    List<VehicleLocationEvent> findByVehicleIdOrderByDriverReportedAtDesc(UUID vehicleId);

    List<VehicleLocationEvent> findByVehicleIdAndDriverReportedAtBetweenOrderByDriverReportedAtAsc(
            UUID vehicleId, OffsetDateTime from, OffsetDateTime to);

    List<VehicleLocationEvent> findByVehicleTaskIdOrderByDriverReportedAtAsc(UUID vehicleTaskId);

    List<VehicleLocationEvent> findByVehicleTaskIdAndDriverReportedAtBetweenOrderByDriverReportedAtAsc(
            UUID vehicleTaskId, OffsetDateTime from, OffsetDateTime to);
}
