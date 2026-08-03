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

    boolean existsByVehicleIdAndStatusInAndIdNot(
            UUID vehicleId, List<TaskStatus> statuses, UUID excludedTaskId);

    boolean existsByDriverIdAndStatusInAndIdNot(
            UUID driverId, List<TaskStatus> statuses, UUID excludedTaskId);

    @EntityGraph(attributePaths = "stops")
    @Query("""
            select distinct task
            from VehicleTask task join task.stops stop
            where stop.rideOrderId = :orderId and task.status in :statuses
            """)
    List<VehicleTask> findActiveByRideOrderId(
            @Param("orderId") UUID orderId,
            @Param("statuses") List<TaskStatus> statuses);

    @EntityGraph(attributePaths = "stops")
    @Query("""
            select distinct task
            from VehicleTask task join task.stops stop
            where stop.rideOrderId = :orderId
            """)
    List<VehicleTask> findByRideOrderId(@Param("orderId") UUID orderId);
}
