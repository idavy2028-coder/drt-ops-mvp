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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;

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

    @JdbcTypeCode(SqlTypes.GEOGRAPHY)
    @Column(nullable = false, columnDefinition = "geometry")
    private Point location;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(nullable = false, length = 20)
    private String coordinateSystem;

    @Column(length = 300)
    private String standardizedAddress;

    @Column(nullable = false)
    private OffsetDateTime driverReportedAt;

    @Column(nullable = false)
    private OffsetDateTime recordedAt;

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

    private UUID terminalId;
    @Column(length = 40) private String protocolVersion;
    private Integer messageSerialNo;
    @Column(precision = 10, scale = 7) private BigDecimal rawLongitude;
    @Column(precision = 10, scale = 7) private BigDecimal rawLatitude;
    @Column(length = 20) private String rawCoordinateSystem;
    private OffsetDateTime gatewayReceivedAt;
    @Column(length = 64) private String payloadDigest;
    @Column(precision = 6, scale = 2) private BigDecimal speedKph;
    private Integer directionDegrees;
    private BigDecimal altitudeMeters;
    private Integer satelliteCount;
    private Long alarmBits;
    private Long statusBits;
    @Column(length = 80) private String coordinateTransformVersion;
    @Enumerated(EnumType.STRING) @Column(length = 20) private LocationQualityStatus qualityStatus;
    @JdbcTypeCode(SqlTypes.JSON) private String qualityReasons;

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
        this.location = GeographyPoint.fromWkt(location);
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
    public String getLocation() { return GeographyPoint.toWkt(location); }
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
    public UUID getTerminalId() { return terminalId; }
    public String getProtocolVersion() { return protocolVersion; }
    public Integer getMessageSerialNo() { return messageSerialNo; }
    public BigDecimal getRawLongitude() { return rawLongitude; }
    public BigDecimal getRawLatitude() { return rawLatitude; }
    public String getRawCoordinateSystem() { return rawCoordinateSystem; }
    public OffsetDateTime getGatewayReceivedAt() { return gatewayReceivedAt; }
    public String getPayloadDigest() { return payloadDigest; }
    public BigDecimal getSpeedKph() { return speedKph; }
    public Integer getDirectionDegrees() { return directionDegrees; }
    public Integer getSatelliteCount() { return satelliteCount; }
    public String getCoordinateTransformVersion() { return coordinateTransformVersion; }
    public LocationQualityStatus getQualityStatus() { return qualityStatus; }
    public String getQualityReasons() { return qualityReasons; }

    public static VehicleLocationEvent recordGps(UUID vehicleId, UUID terminalId, CanonicalPositionIngress ingress,
            CoordinateTransformer.StandardizedCoordinate coordinate, LocationQualityDecision decision,
            UUID idempotencyKey, String fingerprint, OffsetDateTime recordedAt, java.time.Instant gatewayReceivedAt,
            boolean outsideServiceArea) {
        VehicleLocationEvent event = record(vehicleId, null, null, null, LocationEventType.GPS_REPORT, LocationSource.GPS_DEVICE,
                "POINT(" + coordinate.longitude().toPlainString() + " " + coordinate.latitude().toPlainString() + ")",
                coordinate.longitude(), coordinate.latitude(), "GCJ02", null,
                ingress.terminalLocatedAt().atOffset(java.time.ZoneOffset.UTC), recordedAt, null, null, null, null,
                idempotencyKey, fingerprint, decision.applySnapshot(), outsideServiceArea);
        event.terminalId = terminalId; event.protocolVersion = ingress.protocolVersion(); event.messageSerialNo = ingress.messageSerialNo();
        event.rawLongitude = ingress.rawLongitude(); event.rawLatitude = ingress.rawLatitude(); event.rawCoordinateSystem = ingress.rawCoordinateSystem();
        event.gatewayReceivedAt = gatewayReceivedAt.atOffset(java.time.ZoneOffset.UTC); event.payloadDigest = ingress.payloadDigest();
        event.speedKph = ingress.speedKph(); event.directionDegrees = ingress.directionDegrees();
        event.altitudeMeters = ingress.altitudeMeters() == null ? null : BigDecimal.valueOf(ingress.altitudeMeters());
        event.satelliteCount = ingress.satelliteCount(); event.alarmBits = ingress.alarmBits(); event.statusBits = ingress.statusBits();
        event.coordinateTransformVersion = coordinate.transformVersion(); event.qualityStatus = decision.status();
        event.qualityReasons = decision.reasons().stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining("\",\"", "[\"", "\"]"));
        return event;
    }
}
