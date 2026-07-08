package com.idavy.drtops.domain.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "task_stops")
public class TaskStop {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vehicle_task_id", nullable = false)
    private VehicleTask vehicleTask;

    @Column(nullable = false)
    private UUID virtualStopId;

    private UUID rideOrderId;

    @Column(nullable = false)
    private int sequenceNumber;

    @Column(nullable = false, length = 40)
    private String stopType;

    @Column(nullable = false)
    private OffsetDateTime plannedArrivalAt;

    private OffsetDateTime actualArrivalAt;

    @Column(nullable = false, length = 40)
    private String status;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    protected TaskStop() {
    }

    private TaskStop(
            UUID virtualStopId,
            UUID rideOrderId,
            int sequenceNumber,
            String stopType,
            OffsetDateTime plannedArrivalAt) {
        this.id = UUID.randomUUID();
        this.virtualStopId = virtualStopId;
        this.rideOrderId = rideOrderId;
        this.sequenceNumber = sequenceNumber;
        this.stopType = stopType;
        this.plannedArrivalAt = plannedArrivalAt;
        this.status = "PLANNED";
        this.createdAt = OffsetDateTime.now();
    }

    public static TaskStop planned(
            UUID virtualStopId,
            UUID rideOrderId,
            int sequenceNumber,
            String stopType,
            OffsetDateTime plannedArrivalAt) {
        if (sequenceNumber <= 0) {
            throw new IllegalArgumentException("sequenceNumber must be positive");
        }
        return new TaskStop(virtualStopId, rideOrderId, sequenceNumber, stopType, plannedArrivalAt);
    }

    void assignTo(VehicleTask vehicleTask) {
        this.vehicleTask = vehicleTask;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }
}
