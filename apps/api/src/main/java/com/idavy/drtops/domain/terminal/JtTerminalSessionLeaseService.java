package com.idavy.drtops.domain.terminal;

import jakarta.persistence.EntityManager;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JtTerminalSessionLeaseService {

    private static final Duration LEASE_TTL = Duration.ofSeconds(180);

    private final JtTerminalSessionLeaseRepository leaseRepository;
    private final JtTerminalRepository terminalRepository;
    private final EntityManager entityManager;
    private final Clock clock;

    public JtTerminalSessionLeaseService(
            JtTerminalSessionLeaseRepository leaseRepository,
            JtTerminalRepository terminalRepository,
            EntityManager entityManager,
            ObjectProvider<Clock> clocks) {
        this.leaseRepository = leaseRepository;
        this.terminalRepository = terminalRepository;
        this.entityManager = entityManager;
        this.clock = clocks.getIfAvailable(Clock::systemUTC);
    }

    @Transactional
    public SessionLeaseGrant acquire(
            UUID terminalId,
            String gatewayInstance,
            UUID connectionId,
            int tokenVersion) {
        JtTerminal terminal = lockTerminal(Objects.requireNonNull(terminalId, "terminalId"));
        if (terminal.getAuthTokenVersion() != tokenVersion) {
            throw new IllegalStateException("terminal token version changed");
        }
        OffsetDateTime now = now();
        Optional<JtTerminalSessionLease> existing =
                leaseRepository.findLockedByTerminalId(terminalId);
        JtTerminalSessionLease lease;
        if (existing.isPresent()) {
            lease = existing.get();
            lease.takeover(
                    gatewayInstance,
                    connectionId,
                    tokenVersion,
                    lease.getLeaseGeneration() + 1,
                    now,
                    LEASE_TTL);
        } else {
            lease = JtTerminalSessionLease.acquire(
                    terminalId,
                    gatewayInstance,
                    connectionId,
                    tokenVersion,
                    1,
                    now,
                    LEASE_TTL);
        }
        return leaseRepository.saveAndFlush(lease).toGrant();
    }

    @Transactional
    public Optional<SessionLeaseGrant> renew(SessionLeaseOwner owner) {
        Objects.requireNonNull(owner, "owner");
        JtTerminal terminal = lockTerminal(owner.terminalId());
        if (terminal.getAuthTokenVersion() != owner.tokenVersion()) {
            return Optional.empty();
        }
        OffsetDateTime now = now();
        Optional<JtTerminalSessionLease> lease =
                leaseRepository.findLockedByTerminalId(owner.terminalId());
        if (lease.isEmpty()
                || !lease.get().ownedBy(owner)
                || !lease.get().isLiveAt(terminal.getAuthTokenVersion(), now)) {
            return Optional.empty();
        }
        lease.get().renew(now, LEASE_TTL);
        return Optional.of(leaseRepository.saveAndFlush(lease.get()).toGrant());
    }

    @Transactional
    public SessionLeaseReleaseResult release(
            SessionLeaseOwner owner, String safeReasonCode) {
        Objects.requireNonNull(owner, "owner");
        JtTerminal terminal = lockTerminal(owner.terminalId());
        Optional<JtTerminalSessionLease> lease =
                leaseRepository.findLockedByTerminalId(owner.terminalId());
        if (lease.isEmpty()) {
            return new SessionLeaseReleaseResult("ALREADY_RELEASED");
        }
        JtTerminalSessionLease current = lease.get();
        if (terminal.getAuthTokenVersion() != owner.tokenVersion()
                || !current.ownedBy(owner)) {
            return new SessionLeaseReleaseResult("STALE_OWNER_IGNORED");
        }
        if (current.getReleasedAt() != null) {
            return new SessionLeaseReleaseResult("ALREADY_RELEASED");
        }
        current.release(safeReasonCode, now());
        leaseRepository.saveAndFlush(current);
        return new SessionLeaseReleaseResult("RELEASED");
    }

    @Transactional(readOnly = true)
    public boolean isLiveAt(
            UUID terminalId, int currentTokenVersion, Instant now) {
        Objects.requireNonNull(now, "now");
        return leaseRepository.findById(Objects.requireNonNull(terminalId, "terminalId"))
                .map(lease -> lease.isLiveAt(
                        currentTokenVersion,
                        OffsetDateTime.ofInstant(now, ZoneOffset.UTC)))
                .orElse(false);
    }

    private JtTerminal lockTerminal(UUID terminalId) {
        entityManager.createNativeQuery("""
                select id from jt_terminals
                where id = :terminalId
                for update
                """)
                .setParameter("terminalId", terminalId)
                .getSingleResult();
        JtTerminal terminal = terminalRepository.findById(terminalId).orElseThrow();
        entityManager.refresh(terminal);
        return terminal;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    public record SessionLeaseOwner(
            @NotNull UUID terminalId,
            @NotBlank String gatewayInstance,
            @NotNull UUID connectionId,
            @Positive int tokenVersion,
            @Positive long leaseGeneration) {
        @Override
        public String toString() {
            return "SessionLeaseOwner[terminalId=" + terminalId
                    + ", tokenVersion=" + tokenVersion + "]";
        }
    }

    public record SessionLeaseGrant(
            SessionLeaseOwner owner,
            Instant authenticatedAt,
            Instant lastValidMessageAt,
            Instant expiresAt) {
    }

    public record SessionLeaseReleaseResult(String status) {
        public SessionLeaseReleaseResult {
            if (!Set.of(
                    "RELEASED",
                    "ALREADY_RELEASED",
                    "STALE_OWNER_IGNORED").contains(status)) {
                throw new IllegalArgumentException("unsupported release status");
            }
        }
    }
}
