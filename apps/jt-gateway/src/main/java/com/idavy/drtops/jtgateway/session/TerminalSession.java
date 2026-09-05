package com.idavy.drtops.jtgateway.session;

import com.idavy.drtops.jt.protocol.codec.ProtocolVersion;
import com.idavy.drtops.jt.protocol.jsatl12.ActiveSafetyCapabilityProfile;
import io.netty.channel.Channel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TerminalSession {
    private final UUID connectionId = UUID.randomUUID();
    private final Channel channel;
    private final Instant connectedAt;
    private volatile TerminalSessionState state = TerminalSessionState.CONNECTED_UNAUTHENTICATED;
    private TerminalSessionContext context;
    private UUID terminalId;
    private UUID vehicleId;
    private String sourceCoordinateSystem;
    private int tokenVersion;
    private String activeSafetyStandard;
    private List<String> activeSafetyModules = List.of();
    private String terminalAlias = "unknown";
    private byte[] terminalIdentityDigest;
    private int authenticationFailures;
    private Instant lastValidMessageAt;
    private TerminalRegistryPort.SessionLeaseGrant leaseGrant;
    private final AtomicBoolean renewalInFlight = new AtomicBoolean();
    private Instant nextRenewalAttemptAt;
    private boolean leaseReleaseClaimed;

    public TerminalSession(Channel channel, Instant connectedAt) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.connectedAt = Objects.requireNonNull(connectedAt, "connectedAt");
        this.lastValidMessageAt = connectedAt;
    }

    public void registrationAccepted(
            TerminalSessionContext context,
            String terminalIdentity) {
        requireState(TerminalSessionState.CONNECTED_UNAUTHENTICATED);
        installContext(context, terminalIdentity);
    }

    public void restoreAuthenticatedIdentity(
            TerminalSessionContext context,
            String terminalIdentity) {
        requireState(TerminalSessionState.CONNECTED_UNAUTHENTICATED);
        installContext(context, terminalIdentity);
    }

    public void refreshAuthenticationContext(TerminalSessionContext context) {
        requireState(TerminalSessionState.CONNECTED_UNAUTHENTICATED);
        TerminalSessionContext current = Objects.requireNonNull(context, "context");
        if (terminalId == null
                || !terminalId.equals(current.terminalId())
                || tokenVersion != current.tokenVersion()) {
            throw new IllegalStateException("authentication context is inconsistent");
        }
        applyContext(current);
    }

    public void registrationAccepted(
            UUID terminalId,
            UUID vehicleId,
            String sourceCoordinateSystem,
            int tokenVersion,
            String terminalIdentity) {
        requireState(TerminalSessionState.CONNECTED_UNAUTHENTICATED);
        this.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        this.vehicleId = Objects.requireNonNull(vehicleId, "vehicleId");
        if (!"WGS84".equals(sourceCoordinateSystem) && !"GCJ02".equals(sourceCoordinateSystem)) {
            throw new IllegalArgumentException("sourceCoordinateSystem must be WGS84 or GCJ02");
        }
        this.sourceCoordinateSystem = sourceCoordinateSystem;
        this.tokenVersion = tokenVersion;
        String identity = Objects.requireNonNull(terminalIdentity, "terminalIdentity");
        this.terminalIdentityDigest = identityDigest(identity);
        int visible = Math.min(4, identity.length());
        this.terminalAlias = "****" + identity.substring(identity.length() - visible);
    }

    /** Compatibility overload: capability facts are frozen with the accepted vehicle binding. */
    public void registrationAccepted(
            UUID terminalId, UUID vehicleId, String sourceCoordinateSystem, int tokenVersion, String terminalIdentity,
            String activeSafetyStandard, List<String> activeSafetyModules) {
        registrationAccepted(terminalId, vehicleId, sourceCoordinateSystem, tokenVersion, terminalIdentity);
        this.activeSafetyStandard = activeSafetyStandard;
        this.activeSafetyModules = activeSafetyModules == null ? List.of() : List.copyOf(activeSafetyModules);
    }


    public void authenticated(Instant at) {
        requireState(TerminalSessionState.CONNECTED_UNAUTHENTICATED);
        if (terminalId == null || vehicleId == null || sourceCoordinateSystem == null) {
            throw new IllegalStateException("registration is required before authentication");
        }
        state = TerminalSessionState.AUTHENTICATED;
        touch(at);
    }

    public int recordAuthenticationFailure() {
        return ++authenticationFailures;
    }

    public void touch(Instant at) {
        if (state == TerminalSessionState.CLOSED) {
            return;
        }
        lastValidMessageAt = Objects.requireNonNull(at, "at");
    }

    public void close() {
        markClosed();
        channel.close();
    }

    void markClosed() {
        state = TerminalSessionState.CLOSED;
    }

    public UUID connectionId() {
        return connectionId;
    }

    public Channel channel() {
        return channel;
    }

    public Instant connectedAt() {
        return connectedAt;
    }

    public TerminalSessionState state() {
        return state;
    }

    public UUID terminalId() {
        return terminalId;
    }

    public UUID onboardSystemId() {
        return context == null ? null : context.onboardSystemId();
    }

    public UUID vehicleId() {
        return vehicleId;
    }

    public String sourceCoordinateSystem() {
        return sourceCoordinateSystem;
    }

    public int tokenVersion() {
        return tokenVersion;
    }

    public String activeSafetyStandard() { return activeSafetyStandard; }

    public List<String> activeSafetyModules() { return activeSafetyModules; }

    public Set<String> roles() {
        return context == null ? Set.of() : context.roles();
    }

    public TerminalSessionContext context() {
        return context;
    }

    public boolean acceptsTransport(ProtocolVersion version) {
        return context != null
                && version != null
                && context.protocolProfile().transportProfile().equals(version.name());
    }

    public ActiveSafetyCapabilityProfile activeSafetyCapabilityProfile() {
        if (context == null) {
            return new ActiveSafetyCapabilityProfile(null, List.of());
        }
        String standard = switch (context.protocolProfile().safetyProfile()) {
            case "JSATL12_2017" -> "T/JSATL12-2017";
            case "GBT28787_2023" -> "GB/T 28787-2023";
            default -> null;
        };
        return new ActiveSafetyCapabilityProfile(
                standard,
                context.protocolProfile().enabledActiveSafetyModules());
    }


    public String terminalAlias() {
        return terminalAlias;
    }

    public boolean matchesTerminalIdentity(String terminalIdentity) {
        return terminalIdentityDigest != null
                && terminalIdentity != null
                && MessageDigest.isEqual(terminalIdentityDigest, identityDigest(terminalIdentity));
    }

    public int authenticationFailures() {
        return authenticationFailures;
    }

    public Instant lastValidMessageAt() {
        return lastValidMessageAt;
    }

    public synchronized void installLease(
            TerminalRegistryPort.SessionLeaseGrant grant) {
        TerminalRegistryPort.SessionLeaseGrant installed =
                Objects.requireNonNull(grant, "grant");
        TerminalRegistryPort.SessionLeaseOwner owner =
                Objects.requireNonNull(installed.owner(), "grant.owner");
        if (terminalId == null
                || !terminalId.equals(owner.terminalId())
                || tokenVersion != owner.tokenVersion()
                || !connectionId.equals(owner.connectionId())
                || owner.leaseGeneration() <= 0
                || installed.authenticatedAt() == null
                || installed.lastValidMessageAt() == null
                || installed.expiresAt() == null
                || !installed.expiresAt().isAfter(installed.lastValidMessageAt())) {
            throw new IllegalArgumentException("session lease grant is inconsistent");
        }
        leaseGrant = installed;
        leaseReleaseClaimed = false;
        renewalInFlight.set(false);
        nextRenewalAttemptAt = null;
    }

    public synchronized boolean leaseOwnerMatches(
            TerminalRegistryPort.SessionLeaseOwner owner) {
        return leaseGrant != null && leaseGrant.owner().equals(owner);
    }

    public synchronized boolean renewalDue(Instant now, Duration interval) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(interval, "interval");
        return leaseGrant != null
                && now.isBefore(leaseGrant.expiresAt())
                && !now.isBefore(leaseGrant.lastValidMessageAt().plus(interval))
                && (nextRenewalAttemptAt == null
                        || !now.isBefore(nextRenewalAttemptAt));
    }

    public synchronized void acceptRenewal(
            TerminalRegistryPort.SessionLeaseGrant grant) {
        TerminalRegistryPort.SessionLeaseGrant renewed =
                Objects.requireNonNull(grant, "grant");
        if (leaseGrant == null
                || !leaseGrant.owner().equals(renewed.owner())
                || renewed.lastValidMessageAt() == null
                || renewed.expiresAt() == null
                || renewed.lastValidMessageAt().isBefore(leaseGrant.lastValidMessageAt())
                || !renewed.expiresAt().isAfter(renewed.lastValidMessageAt())) {
            return;
        }
        leaseGrant = renewed;
    }

    public synchronized boolean leaseExpired(Instant now) {
        return leaseGrant == null
                || !Objects.requireNonNull(now, "now").isBefore(leaseGrant.expiresAt());
    }

    public boolean beginRenewal(Instant now, Duration interval) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(interval, "interval");
        if (!renewalInFlight.compareAndSet(false, true)) {
            return false;
        }
        synchronized (this) {
            if (!renewalDue(now, interval)) {
                renewalInFlight.set(false);
                return false;
            }
            nextRenewalAttemptAt = now.plus(interval);
            return true;
        }
    }

    public void endRenewal() {
        renewalInFlight.set(false);
    }

    synchronized Optional<TerminalRegistryPort.SessionLeaseOwner> leaseOwner() {
        return leaseGrant == null ? Optional.empty() : Optional.of(leaseGrant.owner());
    }

    synchronized Optional<TerminalRegistryPort.SessionLeaseOwner> claimLeaseOwnerForRelease() {
        if (leaseGrant == null || leaseReleaseClaimed) {
            return Optional.empty();
        }
        leaseReleaseClaimed = true;
        return Optional.of(leaseGrant.owner());
    }

    private void requireState(TerminalSessionState expected) {
        if (state != expected) {
            throw new IllegalStateException("session transition is not allowed from " + state);
        }
    }

    private void installContext(
            TerminalSessionContext context,
            String terminalIdentity) {
        TerminalSessionContext accepted = Objects.requireNonNull(context, "context");
        String identity = Objects.requireNonNull(terminalIdentity, "terminalIdentity");
        byte[] identityDigest = identityDigest(identity);
        int visible = Math.min(4, identity.length());
        String terminalAlias = "****" + identity.substring(identity.length() - visible);
        applyContext(accepted);
        this.terminalIdentityDigest = identityDigest;
        this.terminalAlias = terminalAlias;
    }

    private void applyContext(TerminalSessionContext accepted) {
        this.context = accepted;
        this.terminalId = accepted.terminalId();
        this.vehicleId = accepted.vehicleId();
        this.sourceCoordinateSystem = accepted.sourceCoordinateSystem();
        this.tokenVersion = accepted.tokenVersion();
        this.activeSafetyStandard = accepted.activeSafetyStandard();
        this.activeSafetyModules = accepted.activeSafetyModules();
    }

    private void installTerminalIdentity(String terminalIdentity) {
        String identity = Objects.requireNonNull(terminalIdentity, "terminalIdentity");
        this.terminalIdentityDigest = identityDigest(identity);
        int visible = Math.min(4, identity.length());
        this.terminalAlias = "****" + identity.substring(identity.length() - visible);
    }

    private static byte[] identityDigest(String terminalIdentity) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(
                    terminalIdentity.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
