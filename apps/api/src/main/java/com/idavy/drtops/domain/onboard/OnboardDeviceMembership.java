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
@Table(name = "onboard_device_memberships")
public class OnboardDeviceMembership {

    public enum Status { ACTIVE, REMOVED }

    public enum NetworkMode { DIRECT_CELLULAR, SHARED_LAN_CLIENT }

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID onboardSystemId;

    @Column(nullable = false)
    private UUID terminalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NetworkMode networkMode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(nullable = false)
    private OffsetDateTime validFrom;
    private OffsetDateTime validTo;

    @Column(nullable = false, length = 500)
    private String addedReason;

    @Column(length = 500)
    private String removedReason;

    private UUID addedBy;
    private UUID removedBy;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    private long version;

    protected OnboardDeviceMembership() {
    }

    private OnboardDeviceMembership(
            UUID id,
            UUID onboardSystemId,
            UUID terminalId,
            NetworkMode networkMode,
            String reason,
            UUID actorId,
            OffsetDateTime validFrom) {
        this.id = Objects.requireNonNull(id, "id");
        this.onboardSystemId = Objects.requireNonNull(onboardSystemId, "onboardSystemId");
        this.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        this.networkMode = Objects.requireNonNull(networkMode, "networkMode");
        this.status = Status.ACTIVE;
        this.validFrom = Objects.requireNonNull(validFrom, "validFrom");
        this.addedReason = requireReason(reason);
        this.addedBy = Objects.requireNonNull(actorId, "actorId");
        this.createdAt = validFrom;
        this.updatedAt = validFrom;
    }

    public static OnboardDeviceMembership join(
            UUID onboardSystemId,
            UUID terminalId,
            NetworkMode networkMode,
            String reason,
            UUID actorId,
            OffsetDateTime validFrom) {
        return new OnboardDeviceMembership(
                UUID.randomUUID(), onboardSystemId, terminalId, networkMode,
                reason, actorId, validFrom);
    }

    public void remove(String reason, UUID actorId, OffsetDateTime removedAt) {
        requireActive();
        OffsetDateTime nextValidTo = requireCloseTime(removedAt);
        String nextRemovedReason = requireReason(reason);
        UUID nextRemovedBy = Objects.requireNonNull(actorId, "actorId");
        this.status = Status.REMOVED;
        this.validTo = nextValidTo;
        this.removedReason = nextRemovedReason;
        this.removedBy = nextRemovedBy;
        this.updatedAt = nextValidTo;
    }

    public UUID getId() { return id; }
    public UUID getOnboardSystemId() { return onboardSystemId; }
    public UUID getTerminalId() { return terminalId; }
    public NetworkMode getNetworkMode() { return networkMode; }
    public Status getStatus() { return status; }
    public OffsetDateTime getValidFrom() { return validFrom; }
    public OffsetDateTime getValidTo() { return validTo; }
    public String getAddedReason() { return addedReason; }
    public String getRemovedReason() { return removedReason; }
    public UUID getAddedBy() { return addedBy; }
    public UUID getRemovedBy() { return removedBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }

    private void requireActive() {
        if (status != Status.ACTIVE) {
            throw new IllegalStateException("membership is already inactive");
        }
    }

    private OffsetDateTime requireCloseTime(OffsetDateTime value) {
        OffsetDateTime candidate = Objects.requireNonNull(value, "changedAt");
        if (candidate.isBefore(validFrom) || candidate.isBefore(updatedAt)) {
            throw new IllegalArgumentException("changedAt must not precede membership history");
        }
        return candidate;
    }

    private static String requireReason(String reason) {
        return OnboardText.requireAuditText(reason, "reason");
    }
}
