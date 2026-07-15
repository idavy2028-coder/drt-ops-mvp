package com.idavy.drtops.domain.task;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VehicleTaskRepository extends JpaRepository<VehicleTask, UUID> {

    @EntityGraph(attributePaths = "stops")
    List<VehicleTask> findAllByOrderByPlannedStartAtAsc();

    @EntityGraph(attributePaths = "stops")
    @Query("select task from VehicleTask task where task.id = :id")
    Optional<VehicleTask> findWithStopsById(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select task from VehicleTask task where task.id = :id")
    Optional<VehicleTask> findByIdForExecution(@Param("id") UUID id);
}
