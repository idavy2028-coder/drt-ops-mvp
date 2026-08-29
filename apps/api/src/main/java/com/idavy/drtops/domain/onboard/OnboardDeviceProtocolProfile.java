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
@Table(name = "onboard_device_protocol_profiles")
public class OnboardDeviceProtocolProfile {

    public enum TransportProfile { JT808_2019, JT808_2013 }

    public enum BusinessProfile { GBT28787_2023, VENDOR_DISPATCH, NONE }

    public enum SafetyProfile { GBT28787_2023, JSATL12_2017, NONE }

    public enum MediaProfile { JT1078_2016, NONE }

    public enum Status { ACTIVE, SUPERSEDED }

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID terminalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TransportProfile transportProfile;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private BusinessProfile businessProfile;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SafetyProfile safetyProfile;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MediaProfile mediaProfile;

    @Column(nullable = false)
    private int activePositionIntervalSeconds;

    @Column(nullable = false)
    private int idlePositionIntervalSeconds;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(nullable = false)
    private OffsetDateTime validFrom;
    private OffsetDateTime validTo;

    @Column(nullable = false, length = 500)
    private String reason;
    private UUID actorId;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    private long version;

    protected OnboardDeviceProtocolProfile() {
    }

    private OnboardDeviceProtocolProfile(
            UUID id,
            UUID terminalId,
            TransportProfile transportProfile,
            BusinessProfile businessProfile,
            SafetyProfile safetyProfile,
            MediaProfile mediaProfile,
            int activePositionIntervalSeconds,
            int idlePositionIntervalSeconds,
            String reason,
            UUID actorId,
            OffsetDateTime validFrom) {
        this.id = Objects.requireNonNull(id, "id");
        this.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        this.transportProfile = Objects.requireNonNull(transportProfile, "transportProfile");
        this.businessProfile = Objects.requireNonNull(businessProfile, "businessProfile");
        this.safetyProfile = Objects.requireNonNull(safetyProfile, "safetyProfile");
        this.mediaProfile = Objects.requireNonNull(mediaProfile, "mediaProfile");
        this.activePositionIntervalSeconds = requirePositive(
                activePositionIntervalSeconds, "activePositionIntervalSeconds");
        this.idlePositionIntervalSeconds = requirePositive(
                idlePositionIntervalSeconds, "idlePositionIntervalSeconds");
        this.status = Status.ACTIVE;
        this.validFrom = Objects.requireNonNull(validFrom, "validFrom");
        this.reason = requireReason(reason);
        this.actorId = Objects.requireNonNull(actorId, "actorId");
        this.createdAt = validFrom;
        this.updatedAt = validFrom;
    }

    public static OnboardDeviceProtocolProfile activate(
            UUID terminalId,
            TransportProfile transportProfile,
            BusinessProfile businessProfile,
            SafetyProfile safetyProfile,
            MediaProfile mediaProfile,
            int activePositionIntervalSeconds,
            int idlePositionIntervalSeconds,
            String reason,
            UUID actorId,
            OffsetDateTime validFrom) {
        return new OnboardDeviceProtocolProfile(
                UUID.randomUUID(), terminalId, transportProfile, businessProfile,
                safetyProfile, mediaProfile, activePositionIntervalSeconds,
                idlePositionIntervalSeconds, reason, actorId, validFrom);
    }

    public void supersede(String reason, UUID actorId, OffsetDateTime supersededAt) {
        if (status != Status.ACTIVE) {
            throw new IllegalStateException("protocol profile is already inactive");
        }
        OffsetDateTime changedAt = requireCloseTime(supersededAt);
        requireReason(reason);
        Objects.requireNonNull(actorId, "actorId");
        this.status = Status.SUPERSEDED;
        this.validTo = changedAt;
        this.updatedAt = changedAt;
    }

    public UUID getId() { return id; }
    public UUID getTerminalId() { return terminalId; }
    public TransportProfile getTransportProfile() { return transportProfile; }
    public BusinessProfile getBusinessProfile() { return businessProfile; }
    public SafetyProfile getSafetyProfile() { return safetyProfile; }
    public MediaProfile getMediaProfile() { return mediaProfile; }
    public int getActivePositionIntervalSeconds() { return activePositionIntervalSeconds; }
    public int getIdlePositionIntervalSeconds() { return idlePositionIntervalSeconds; }
    public Status getStatus() { return status; }
    public OffsetDateTime getValidFrom() { return validFrom; }
    public OffsetDateTime getValidTo() { return validTo; }
    public String getReason() { return reason; }
    public UUID getActorId() { return actorId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }

    private OffsetDateTime requireCloseTime(OffsetDateTime value) {
        OffsetDateTime candidate = Objects.requireNonNull(value, "supersededAt");
        if (candidate.isBefore(validFrom) || candidate.isBefore(updatedAt)) {
            throw new IllegalArgumentException("supersededAt must not precede profile history");
        }
        return candidate;
    }

    private static int requirePositive(int value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private static String requireReason(String reason) {
        return OnboardText.requireAuditText(reason, "reason");
    }
}
