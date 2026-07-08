package com.idavy.drtops.domain.task;

import jakarta.persistence.Column;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "vehicle_tasks")
public class VehicleTask {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID vehicleId;

    @Column(nullable = false)
    private UUID driverId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private TaskStatus status;

    @Column(nullable = false)
    private OffsetDateTime plannedStartAt;

    private OffsetDateTime plannedEndAt;

    private UUID currentStopId;

    @Column(nullable = false, length = 40)
    private String sourceType;

    @OneToMany(mappedBy = "vehicleTask", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sequenceNumber ASC")
    private List<TaskStop> stops = new ArrayList<>();

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    protected VehicleTask() {
    }

    private VehicleTask(UUID vehicleId, UUID driverId, OffsetDateTime plannedStartAt, String sourceType) {
        OffsetDateTime now = OffsetDateTime.now();
        this.id = UUID.randomUUID();
        this.vehicleId = vehicleId;
        this.driverId = driverId;
        this.status = TaskStatus.PENDING_DEPARTURE;
        this.plannedStartAt = plannedStartAt;
        this.sourceType = sourceType;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static VehicleTask pendingDeparture(
            UUID vehicleId,
            UUID driverId,
            OffsetDateTime plannedStartAt,
            String sourceType) {
        return new VehicleTask(vehicleId, driverId, plannedStartAt, sourceType);
    }

    public void dispatch() {
        requireStatus(TaskStatus.PENDING_DEPARTURE);
        changeStatus(TaskStatus.DISPATCHED);
    }

    public void startExecution() {
        requireStatus(TaskStatus.DISPATCHED);
        changeStatus(TaskStatus.IN_PROGRESS);
    }

    public void pause(String reason) {
        requireStatus(TaskStatus.IN_PROGRESS);
        changeStatus(TaskStatus.PAUSED);
    }

    public void resume() {
        requireStatus(TaskStatus.PAUSED);
        changeStatus(TaskStatus.IN_PROGRESS);
    }

    public void complete() {
        requireStatus(TaskStatus.IN_PROGRESS);
        this.plannedEndAt = OffsetDateTime.now();
        changeStatus(TaskStatus.COMPLETED);
    }

    public void cancel(String reason) {
        if (isTerminal()) {
            throw new IllegalStateException("Cannot cancel task in status " + status);
        }
        changeStatus(TaskStatus.CANCELLED);
    }

    public void markException(String reason) {
        if (status == TaskStatus.COMPLETED) {
            throw new IllegalStateException("Cannot mark completed task as exception");
        }
        changeStatus(TaskStatus.EXCEPTION);
    }

    public void addStop(TaskStop stop) {
        stop.assignTo(this);
        stops.add(stop);
        stops.sort(Comparator.comparingInt(TaskStop::getSequenceNumber));
        this.updatedAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public List<TaskStop> getStops() {
        return List.copyOf(stops);
    }

    private void requireStatus(TaskStatus expectedStatus) {
        if (status != expectedStatus) {
            throw new IllegalStateException("Task status " + status + " cannot perform this transition");
        }
    }

    private boolean isTerminal() {
        return status == TaskStatus.COMPLETED
                || status == TaskStatus.CANCELLED
                || status == TaskStatus.EXCEPTION;
    }

    private void changeStatus(TaskStatus nextStatus) {
        this.status = nextStatus;
        this.updatedAt = OffsetDateTime.now();
    }
}
