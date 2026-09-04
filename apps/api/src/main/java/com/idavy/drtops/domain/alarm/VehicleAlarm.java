package com.idavy.drtops.domain.alarm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "vehicle_alarms")
public class VehicleAlarm {
    @Id private UUID id;
    @Column(name = "public_id", nullable = false, updatable = false, unique = true) private UUID publicId;
    @Column(nullable = false) private UUID vehicleId;
    @Column(nullable = false) private UUID terminalId;
    @Column(name = "onboard_system_id") private UUID onboardSystemId;
    @Column(name = "location_event_id") private UUID locationEventId;
    @Column(nullable = false) private String standard;
    @Column(nullable = false) private String module;
    @Column(nullable = false) private long terminalAlarmId;
    @Column(nullable = false) private int alarmTypeCode;
    @Column(nullable = false) private String alarmTypeNameSnapshot;
    @Column(nullable = false) private int alarmLevel;
    @Column(nullable = false) private String terminalAlarmIdentifier;
    @Column(nullable = false) private String terminalAlarmState;
    @Column(nullable = false) private Instant occurredAt;
    private Instant endedAt;
    @Column(nullable = false) private Instant gatewayReceivedAt;
    @Column(nullable = false) private BigDecimal longitude;
    @Column(nullable = false) private BigDecimal latitude;
    private BigDecimal speedKph;
    @Column(nullable = false) private String locationQualityStatus;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false) private String locationQualityReasons;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private ProcessingStatus processingStatus;
    @Column(nullable = false, length = 64, columnDefinition = "char(64)") private String payloadDigest;
    @Column(nullable = false, length = 64, columnDefinition = "char(64)") private String deduplicationKey;
    @Column(name = "handled_by") private UUID handledBy;
    @Column(name = "handled_at") private Instant handledAt;
    @Version @Column(nullable = false) private long version;
    @Column(nullable = false) private Instant createdAt;

    protected VehicleAlarm() { }
    private VehicleAlarm(VehicleAlarmIngressService.AlarmFact fact, String key, AlarmStore.LocationReference location) {
        if (!java.util.Objects.equals(fact.onboardSystemId(), location.onboardSystemId())) {
            throw new IllegalArgumentException("alarm and location onboard system must match");
        }
        id = UUID.randomUUID(); publicId = independentPublicId(id); vehicleId = fact.vehicleId(); terminalId = fact.terminalId();
        onboardSystemId = fact.onboardSystemId(); standard = fact.standard();
        locationEventId = location.eventId();
        module = fact.module(); terminalAlarmId = fact.terminalAlarmId();
        alarmTypeCode = fact.typeCode(); alarmTypeNameSnapshot = fact.alarmType();
        alarmLevel = fact.level(); terminalAlarmIdentifier = fact.terminalAlarmIdentifier(); terminalAlarmState = "START";
        occurredAt = fact.occurredAt(); gatewayReceivedAt = fact.gatewayReceivedAt(); longitude = fact.longitude();
        latitude = fact.latitude(); speedKph = fact.speedKph(); locationQualityStatus = location.qualityStatus();
        locationQualityReasons = location.qualityReasons();
        processingStatus = ProcessingStatus.NEW; payloadDigest = fact.payloadDigest(); deduplicationKey = key;
        version = 0; createdAt = Instant.now();
    }
    static VehicleAlarm start(VehicleAlarmIngressService.AlarmFact fact, String key, AlarmStore.LocationReference location) {
        return new VehicleAlarm(fact, key, location);
    }
    @PrePersist
    private void ensureDistinctPublicId() {
        if (id == null) id = UUID.randomUUID();
        if (publicId == null || publicId.equals(id)) publicId = independentPublicId(id);
    }
    private static UUID independentPublicId(UUID internalId) {
        UUID candidate;
        do { candidate = UUID.randomUUID(); } while (candidate.equals(internalId));
        return candidate;
    }
    void endAt(Instant at) { if (endedAt == null) endedAt = at; }
    void transitionTo(ProcessingStatus status, UUID actorId, Instant handledAt) {
        processingStatus = status;
        handledBy = actorId;
        this.handledAt = handledAt;
    }
    public UUID getId() { return id; }
    public UUID getPublicId() { return publicId; }
    public String getDeduplicationKey() { return deduplicationKey; }
    public Instant getEndedAt() { return endedAt; }
    public String getTerminalAlarmIdentifier() { return terminalAlarmIdentifier; }
    public UUID getTerminalId() { return terminalId; }
    public UUID getOnboardSystemId() { return onboardSystemId; }
    public UUID getVehicleId() { return vehicleId; }
    public UUID getLocationEventId() { return locationEventId; }
    public String getLocationQualityStatus() { return locationQualityStatus; }
    public String getLocationQualityReasons() { return locationQualityReasons; }
    public String getStandard() { return standard; }
    public String getModule() { return module; }
    public long getTerminalAlarmId() { return terminalAlarmId; }
    public int getAlarmTypeCode() { return alarmTypeCode; }
    public String getAlarmTypeNameSnapshot() { return alarmTypeNameSnapshot; }
    public int getAlarmLevel() { return alarmLevel; }
    public Instant getOccurredAt() { return occurredAt; }
    public BigDecimal getLongitude() { return longitude; }
    public BigDecimal getLatitude() { return latitude; }
    public BigDecimal getSpeedKph() { return speedKph; }
    public ProcessingStatus getProcessingStatus() { return processingStatus; }
    public UUID getHandledBy() { return handledBy; }
    public Instant getHandledAt() { return handledAt; }
    public long getVersion() { return version; }

    public enum ProcessingStatus {
        NEW,
        ACKNOWLEDGED,
        PROCESSING,
        RESOLVED,
        FALSE_POSITIVE
    }
}
