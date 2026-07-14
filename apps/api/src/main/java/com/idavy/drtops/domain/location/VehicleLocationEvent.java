package com.idavy.drtops.domain.location;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "vehicle_location_events")
public class VehicleLocationEvent {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID vehicleId;

    private UUID vehicleTaskId;

    private UUID taskStopId;

    private UUID virtualStopId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private LocationEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private LocationSource source;

    @Column(nullable = false, length = 120)
    private String location;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(nullable = false, length = 20)
    private String coordinateSystem;

    @Column(nullable = false, length = 300)
    private String standardizedAddress;

    @Column(nullable = false)
    private OffsetDateTime driverReportedAt;

    @Column(nullable = false)
    private OffsetDateTime recordedAt;

    @Column(nullable = false)
    private UUID recordedBy;

    @Column(length = 500)
    private String note;

    @Column(length = 500)
    private String correctionReason;

    private UUID correctsEventId;

    @Column(nullable = false, unique = true)
    private UUID idempotencyKey;

    @Column(nullable = false, length = 64)
    private String requestFingerprint;

    @Column(nullable = false)
    private boolean snapshotApplied;

    @Column(nullable = false)
    private boolean outsideServiceArea;

    protected VehicleLocationEvent() {
    }

    private VehicleLocationEvent(
            UUID vehicleId,
            UUID vehicleTaskId,
            UUID taskStopId,
            UUID virtualStopId,
            LocationEventType eventType,
            LocationSource source,
            String location,
            BigDecimal longitude,
            BigDecimal latitude,
            String coordinateSystem,
            String standardizedAddress,
            OffsetDateTime driverReportedAt,
            OffsetDateTime recordedAt,
            UUID recordedBy,
            String note,
            String correctionReason,
            UUID correctsEventId,
            UUID idempotencyKey,
            String requestFingerprint,
            boolean snapshotApplied,
            boolean outsideServiceArea) {
        this.id = UUID.randomUUID();
        this.vehicleId = vehicleId;
        this.vehicleTaskId = vehicleTaskId;
        this.taskStopId = taskStopId;
        this.virtualStopId = virtualStopId;
        this.eventType = eventType;
        this.source = source;
        this.location = location;
        this.longitude = longitude;
        this.latitude = latitude;
        this.coordinateSystem = coordinateSystem;
        this.standardizedAddress = standardizedAddress;
        this.driverReportedAt = driverReportedAt;
        this.recordedAt = recordedAt;
        this.recordedBy = recordedBy;
        this.note = note;
        this.correctionReason = correctionReason;
        this.correctsEventId = correctsEventId;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.snapshotApplied = snapshotApplied;
        this.outsideServiceArea = outsideServiceArea;
    }

    public static VehicleLocationEvent record(
            UUID vehicleId,
            UUID vehicleTaskId,
            UUID taskStopId,
            UUID virtualStopId,
            LocationEventType eventType,
            LocationSource source,
            String location,
            BigDecimal longitude,
            BigDecimal latitude,
            String coordinateSystem,
            String standardizedAddress,
            OffsetDateTime driverReportedAt,
            OffsetDateTime recordedAt,
            UUID recordedBy,
            String note,
            String correctionReason,
            UUID correctsEventId,
            UUID idempotencyKey,
            String requestFingerprint,
            boolean snapshotApplied,
            boolean outsideServiceArea) {
        return new VehicleLocationEvent(
                vehicleId,
                vehicleTaskId,
                taskStopId,
                virtualStopId,
                eventType,
                source,
                location,
                longitude,
                latitude,
                coordinateSystem,
                standardizedAddress,
                driverReportedAt,
                recordedAt,
                recordedBy,
                note,
                correctionReason,
                correctsEventId,
                idempotencyKey,
                requestFingerprint,
                snapshotApplied,
                outsideServiceArea);
    }

    public UUID getId() { return id; }
    public UUID getVehicleId() { return vehicleId; }
    public UUID getVehicleTaskId() { return vehicleTaskId; }
    public UUID getTaskStopId() { return taskStopId; }
    public UUID getVirtualStopId() { return virtualStopId; }
    public LocationEventType getEventType() { return eventType; }
    public LocationSource getSource() { return source; }
    public String getLocation() { return location; }
    public BigDecimal getLongitude() { return longitude; }
    public BigDecimal getLatitude() { return latitude; }
    public String getCoordinateSystem() { return coordinateSystem; }
    public String getStandardizedAddress() { return standardizedAddress; }
    public OffsetDateTime getDriverReportedAt() { return driverReportedAt; }
    public OffsetDateTime getRecordedAt() { return recordedAt; }
    public UUID getRecordedBy() { return recordedBy; }
    public String getNote() { return note; }
    public String getCorrectionReason() { return correctionReason; }
    public UUID getCorrectsEventId() { return correctsEventId; }
    public UUID getIdempotencyKey() { return idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public boolean isSnapshotApplied() { return snapshotApplied; }
    public boolean isOutsideServiceArea() { return outsideServiceArea; }
}
