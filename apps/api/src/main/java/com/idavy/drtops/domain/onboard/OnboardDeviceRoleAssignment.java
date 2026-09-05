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
@Table(name = "onboard_device_role_assignments")
public class OnboardDeviceRoleAssignment {

    public enum Role {
        DISPATCH,
        LOCATION_PRIMARY,
        LOCATION_BACKUP,
        ACTIVE_SAFETY,
        VIDEO,
        WAN_UPLINK
    }

    public enum Status { ACTIVE, REVOKED }

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID onboardSystemId;

    @Column(nullable = false)
    private UUID terminalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(nullable = false)
    private OffsetDateTime validFrom;
    private OffsetDateTime validTo;

    @Column(nullable = false, length = 500)
    private String assignedReason;

    @Column(length = 500)
    private String revokedReason;

    private UUID assignedBy;
    private UUID revokedBy;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    private long version;

    protected OnboardDeviceRoleAssignment() {
    }

    private OnboardDeviceRoleAssignment(
            UUID id,
            UUID onboardSystemId,
            UUID terminalId,
            Role role,
            String reason,
            UUID actorId,
            OffsetDateTime validFrom) {
        this.id = Objects.requireNonNull(id, "id");
        this.onboardSystemId = Objects.requireNonNull(onboardSystemId, "onboardSystemId");
        this.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        this.role = Objects.requireNonNull(role, "role");
        this.status = Status.ACTIVE;
        this.validFrom = Objects.requireNonNull(validFrom, "validFrom");
        this.assignedReason = requireReason(reason);
        this.assignedBy = Objects.requireNonNull(actorId, "actorId");
        this.createdAt = validFrom;
        this.updatedAt = validFrom;
    }

    public static OnboardDeviceRoleAssignment assign(
            UUID onboardSystemId,
            UUID terminalId,
            Role role,
            String reason,
            UUID actorId,
            OffsetDateTime validFrom) {
        return new OnboardDeviceRoleAssignment(
                UUID.randomUUID(), onboardSystemId, terminalId, role,
                reason, actorId, validFrom);
    }

    public void revoke(String reason, UUID actorId, OffsetDateTime revokedAt) {
        if (status != Status.ACTIVE) {
            throw new IllegalStateException("role assignment is already inactive");
        }
        OffsetDateTime changedAt = requireCloseTime(revokedAt);
        String nextRevokedReason = requireReason(reason);
        UUID nextRevokedBy = Objects.requireNonNull(actorId, "actorId");
        this.status = Status.REVOKED;
        this.validTo = changedAt;
        this.revokedReason = nextRevokedReason;
        this.revokedBy = nextRevokedBy;
        this.updatedAt = changedAt;
    }

    public UUID getId() { return id; }
    public UUID getOnboardSystemId() { return onboardSystemId; }
    public UUID getTerminalId() { return terminalId; }
    public Role getRole() { return role; }
    public Status getStatus() { return status; }
    public OffsetDateTime getValidFrom() { return validFrom; }
    public OffsetDateTime getValidTo() { return validTo; }
    public String getAssignedReason() { return assignedReason; }
    public String getRevokedReason() { return revokedReason; }
    public UUID getAssignedBy() { return assignedBy; }
    public UUID getRevokedBy() { return revokedBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }

    private OffsetDateTime requireCloseTime(OffsetDateTime value) {
        OffsetDateTime candidate = Objects.requireNonNull(value, "revokedAt");
        if (candidate.isBefore(validFrom) || candidate.isBefore(updatedAt)) {
            throw new IllegalArgumentException("revokedAt must not precede role history");
        }
        return candidate;
    }

    private static String requireReason(String reason) {
        return OnboardText.requireAuditText(reason, "reason");
    }
}
