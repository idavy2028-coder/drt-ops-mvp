package com.idavy.drtops.domain.terminal;

import com.idavy.drtops.domain.terminal.JtTerminalSessionLeaseService.SessionLeaseGrant;
import com.idavy.drtops.domain.terminal.JtTerminalSessionLeaseService.SessionLeaseOwner;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "jt_terminal_session_leases")
public class JtTerminalSessionLease {

    @Id
    @Column(name = "terminal_id", nullable = false)
    private UUID terminalId;

    @Column(name = "gateway_instance", nullable = false, length = 120)
    private String gatewayInstance;

    @Column(name = "connection_id", nullable = false)
    private UUID connectionId;

    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    @Column(name = "lease_generation", nullable = false)
    private long leaseGeneration;

    @Column(name = "authenticated_at", nullable = false)
    private OffsetDateTime authenticatedAt;

    @Column(name = "last_valid_message_at", nullable = false)
    private OffsetDateTime lastValidMessageAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "released_at")
    private OffsetDateTime releasedAt;

    @Column(name = "release_reason", length = 80)
    private String releaseReason;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected JtTerminalSessionLease() {
    }

    static JtTerminalSessionLease acquire(
            UUID terminalId,
            String gatewayInstance,
            UUID connectionId,
            int tokenVersion,
            long generation,
            OffsetDateTime now,
            Duration ttl) {
        JtTerminalSessionLease lease = new JtTerminalSessionLease();
        lease.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        lease.takeover(gatewayInstance, connectionId, tokenVersion, generation, now, ttl);
        return lease;
    }

    void takeover(
            String gatewayInstance,
            UUID connectionId,
            int tokenVersion,
            long generation,
            OffsetDateTime now,
            Duration ttl) {
        if (tokenVersion <= 0) {
            throw new IllegalArgumentException("tokenVersion must be positive");
        }
        if (generation <= 0) {
            throw new IllegalArgumentException("leaseGeneration must be positive");
        }
        this.gatewayInstance = requireGatewayInstance(gatewayInstance);
        this.connectionId = Objects.requireNonNull(connectionId, "connectionId");
        this.tokenVersion = tokenVersion;
        this.leaseGeneration = generation;
        this.authenticatedAt = Objects.requireNonNull(now, "now");
        this.lastValidMessageAt = now;
        this.expiresAt = expiresAt(now, ttl);
        this.releasedAt = null;
        this.releaseReason = null;
        this.updatedAt = now;
    }

    boolean ownedBy(SessionLeaseOwner owner) {
        return owner != null
                && terminalId.equals(owner.terminalId())
                && gatewayInstance.equals(owner.gatewayInstance())
                && connectionId.equals(owner.connectionId())
                && tokenVersion == owner.tokenVersion()
                && leaseGeneration == owner.leaseGeneration();
    }

    void renew(OffsetDateTime now, Duration ttl) {
        if (releasedAt != null) {
            throw new IllegalStateException("released lease cannot be renewed");
        }
        this.lastValidMessageAt = Objects.requireNonNull(now, "now");
        this.expiresAt = expiresAt(now, ttl);
        this.updatedAt = now;
    }

    void release(String reasonCode, OffsetDateTime now) {
        this.releaseReason = requireReasonCode(reasonCode);
        this.releasedAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    public boolean isLiveAt(int currentTokenVersion, OffsetDateTime now) {
        return releasedAt == null
                && tokenVersion == currentTokenVersion
                && Objects.requireNonNull(now, "now").isBefore(expiresAt);
    }

    SessionLeaseGrant toGrant() {
        return new SessionLeaseGrant(
                new SessionLeaseOwner(
                        terminalId,
                        gatewayInstance,
                        connectionId,
                        tokenVersion,
                        leaseGeneration),
                authenticatedAt.toInstant(),
                lastValidMessageAt.toInstant(),
                expiresAt.toInstant());
    }

    public UUID getTerminalId() {
        return terminalId;
    }

    public String getGatewayInstance() {
        return gatewayInstance;
    }

    public UUID getConnectionId() {
        return connectionId;
    }

    public int getTokenVersion() {
        return tokenVersion;
    }

    public long getLeaseGeneration() {
        return leaseGeneration;
    }

    public OffsetDateTime getAuthenticatedAt() {
        return authenticatedAt;
    }

    public OffsetDateTime getLastValidMessageAt() {
        return lastValidMessageAt;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public OffsetDateTime getReleasedAt() {
        return releasedAt;
    }

    public String getReleaseReason() {
        return releaseReason;
    }

    private static OffsetDateTime expiresAt(OffsetDateTime now, Duration ttl) {
        Duration requiredTtl = Objects.requireNonNull(ttl, "ttl");
        if (requiredTtl.isZero() || requiredTtl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        return now.plus(requiredTtl);
    }

    private static String requireGatewayInstance(String value) {
        if (value == null || value.isBlank() || value.length() > 120) {
            throw new IllegalArgumentException("gatewayInstance is invalid");
        }
        return value;
    }

    private static String requireReasonCode(String value) {
        if (value == null || !value.matches("[A-Z][A-Z0-9_]{2,79}")) {
            throw new IllegalArgumentException("reasonCode is invalid");
        }
        return value;
    }
}
