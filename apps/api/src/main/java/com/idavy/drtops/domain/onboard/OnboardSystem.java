package com.idavy.drtops.domain.onboard;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "onboard_systems")
public class OnboardSystem {

    public enum Status { ACTIVE, SUSPENDED, RETIRED }

    public enum OperatingMode { DISPATCH_SERVICE, SAFETY_MONITOR_ONLY }

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID vehicleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OperatingMode operatingMode;

    private UUID createdBy;
    private UUID updatedBy;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    private long version;

    protected OnboardSystem() {
    }

    private OnboardSystem(
            UUID id,
            UUID vehicleId,
            OperatingMode operatingMode,
            UUID actorId,
            OffsetDateTime createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.vehicleId = Objects.requireNonNull(vehicleId, "vehicleId");
        this.status = Status.ACTIVE;
        this.operatingMode = Objects.requireNonNull(operatingMode, "operatingMode");
        this.createdBy = Objects.requireNonNull(actorId, "actorId");
        this.updatedBy = actorId;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = createdAt;
    }

    public static OnboardSystem create(
            UUID vehicleId,
            OperatingMode operatingMode,
            UUID actorId,
            OffsetDateTime createdAt) {
        return new OnboardSystem(UUID.randomUUID(), vehicleId, operatingMode, actorId, createdAt);
    }

    public void changeOperatingMode(
            OperatingMode operatingMode, UUID actorId, OffsetDateTime changedAt) {
        requireMutable();
        OperatingMode nextMode = Objects.requireNonNull(operatingMode, "operatingMode");
        UUID nextActor = Objects.requireNonNull(actorId, "actorId");
        OffsetDateTime nextUpdatedAt = requireMonotonicTime(changedAt);
        this.operatingMode = nextMode;
        this.updatedBy = nextActor;
        this.updatedAt = nextUpdatedAt;
    }

    public void suspend(UUID actorId, OffsetDateTime changedAt) {
        requireStatus(Status.ACTIVE);
        UUID nextActor = Objects.requireNonNull(actorId, "actorId");
        OffsetDateTime nextUpdatedAt = requireMonotonicTime(changedAt);
        this.status = Status.SUSPENDED;
        this.updatedBy = nextActor;
        this.updatedAt = nextUpdatedAt;
    }

    public void activate(UUID actorId, OffsetDateTime changedAt) {
        requireStatus(Status.SUSPENDED);
        UUID nextActor = Objects.requireNonNull(actorId, "actorId");
        OffsetDateTime nextUpdatedAt = requireMonotonicTime(changedAt);
        this.status = Status.ACTIVE;
        this.updatedBy = nextActor;
        this.updatedAt = nextUpdatedAt;
    }

    public void retire(UUID actorId, OffsetDateTime changedAt) {
        requireMutable();
        UUID nextActor = Objects.requireNonNull(actorId, "actorId");
        OffsetDateTime nextUpdatedAt = requireMonotonicTime(changedAt);
        this.updatedBy = nextActor;
        this.updatedAt = nextUpdatedAt;
        this.status = Status.RETIRED;
    }

    public void touchConfiguration(UUID actorId, OffsetDateTime changedAt) {
        requireMutable();
        UUID nextActor = Objects.requireNonNull(actorId, "actorId");
        OffsetDateTime nextUpdatedAt = requireMonotonicTime(changedAt);
        this.updatedBy = nextActor;
        this.updatedAt = nextUpdatedAt;
    }

    public UUID getId() { return id; }
    public UUID getVehicleId() { return vehicleId; }
    public Status getStatus() { return status; }
    public OperatingMode getOperatingMode() { return operatingMode; }
    public UUID getCreatedBy() { return createdBy; }
    public UUID getUpdatedBy() { return updatedBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }

    private void requireMutable() {
        if (status == Status.RETIRED) {
            throw new IllegalStateException("retired onboard system is immutable");
        }
    }

    private void requireStatus(Status expected) {
        requireMutable();
        if (status != expected) {
            throw new IllegalStateException(
                    "onboard system status must be " + expected + " but was " + status);
        }
    }

    private OffsetDateTime requireMonotonicTime(OffsetDateTime changedAt) {
        OffsetDateTime value = Objects.requireNonNull(changedAt, "changedAt");
        if (value.isBefore(updatedAt)) {
            throw new IllegalArgumentException("changedAt must not be before updatedAt");
        }
        return value;
    }
}
