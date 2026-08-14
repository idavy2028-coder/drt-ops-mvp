package com.idavy.drtops.domain.alarm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
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
        id = UUID.randomUUID(); publicId = UUID.randomUUID(); vehicleId = fact.vehicleId(); terminalId = fact.terminalId(); standard = fact.standard();
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
