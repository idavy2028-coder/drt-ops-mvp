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
@Table(name = "onboard_device_capabilities")
public class OnboardDeviceCapability {

    public enum Capability {
        JT808_LOCATION,
        GBT28787_DISPATCH,
        VENDOR_DISPATCH,
        ADAS,
        DMS,
        VIDEO,
        JT1078_MEDIA
    }

    public enum CapabilityStatus { DECLARED, VERIFIED, DISABLED }

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID terminalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Capability capability;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CapabilityStatus status;

    @Column(length = 500)
    private String evidenceRef;
    private OffsetDateTime verifiedAt;
    private UUID verifiedBy;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    private long version;

    protected OnboardDeviceCapability() {
    }

    private OnboardDeviceCapability(
            UUID id,
            UUID terminalId,
            Capability capability,
            String reason,
            OffsetDateTime declaredAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        this.capability = Objects.requireNonNull(capability, "capability");
        this.status = CapabilityStatus.DECLARED;
        this.reason = requireReason(reason);
        this.createdAt = Objects.requireNonNull(declaredAt, "declaredAt");
        this.updatedAt = declaredAt;
    }

    public static OnboardDeviceCapability declare(
            UUID terminalId,
            Capability capability,
            String reason,
            OffsetDateTime declaredAt) {
        return new OnboardDeviceCapability(
                UUID.randomUUID(), terminalId, capability, reason, declaredAt);
    }

    public void verify(
            String evidenceRef, UUID actorId, String reason, OffsetDateTime verifiedAt) {
        requireDeclared();
        String nextEvidenceRef = OnboardText.requireAuditText(evidenceRef, "evidenceRef");
        UUID nextVerifiedBy = Objects.requireNonNull(actorId, "actorId");
        OffsetDateTime nextVerifiedAt = requireChangeTime(verifiedAt);
        String nextReason = requireReason(reason);
        this.status = CapabilityStatus.VERIFIED;
        this.evidenceRef = nextEvidenceRef;
        this.verifiedBy = nextVerifiedBy;
        this.verifiedAt = nextVerifiedAt;
        this.reason = nextReason;
        this.updatedAt = nextVerifiedAt;
    }

    public void disable(String reason, OffsetDateTime disabledAt) {
        requireEnabled();
        String nextReason = requireReason(reason);
        OffsetDateTime nextUpdatedAt = requireChangeTime(disabledAt);
        this.status = CapabilityStatus.DISABLED;
        this.reason = nextReason;
        this.updatedAt = nextUpdatedAt;
    }

    public UUID getId() { return id; }
    public UUID getTerminalId() { return terminalId; }
    public Capability getCapability() { return capability; }
    public CapabilityStatus getStatus() { return status; }
    public String getEvidenceRef() { return evidenceRef; }
    public OffsetDateTime getVerifiedAt() { return verifiedAt; }
    public UUID getVerifiedBy() { return verifiedBy; }
    public String getReason() { return reason; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }

    private void requireEnabled() {
        if (status == CapabilityStatus.DISABLED) {
            throw new IllegalStateException("disabled capability is immutable");
        }
    }

    private void requireDeclared() {
        if (status != CapabilityStatus.DECLARED) {
            throw new IllegalStateException("capability must be DECLARED before verification");
        }
    }

    private OffsetDateTime requireChangeTime(OffsetDateTime value) {
        OffsetDateTime candidate = Objects.requireNonNull(value, "changedAt");
        if (candidate.isBefore(updatedAt)) {
            throw new IllegalArgumentException("changedAt must not be before updatedAt");
        }
        return candidate;
    }

    private static String requireReason(String reason) {
        return OnboardText.requireAuditText(reason, "reason");
    }
}
