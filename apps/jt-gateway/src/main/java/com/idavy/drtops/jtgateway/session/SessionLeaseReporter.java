package com.idavy.drtops.jtgateway.session;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

public final class SessionLeaseReporter {

    private static final Duration RENEW_INTERVAL = Duration.ofSeconds(30);

    private final TerminalRegistryPort registryPort;
    private final Executor executor;
    private final Clock clock;

    public SessionLeaseReporter(
            TerminalRegistryPort registryPort,
            Executor executor,
            Clock clock) {
        this.registryPort = Objects.requireNonNull(registryPort, "registryPort");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void renewIfDue(TerminalSession session, Instant now) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(now, "now");
        if (session.leaseExpired(now)) {
            closeOnEventLoop(session);
            return;
        }
        if (!session.renewalDue(now, RENEW_INTERVAL)
                || !session.beginRenewal(now, RENEW_INTERVAL)) {
            return;
        }
        Optional<TerminalRegistryPort.SessionLeaseOwner> owner = session.leaseOwner();
        if (owner.isEmpty()) {
            session.endRenewal();
            return;
        }
        try {
            executor.execute(() -> renew(session, owner.orElseThrow()));
        } catch (RejectedExecutionException rejected) {
            session.endRenewal();
        }
    }

    public void release(TerminalSession session, String reasonCode) {
        Objects.requireNonNull(session, "session");
        Optional<TerminalRegistryPort.SessionLeaseOwner> owner =
                session.claimLeaseOwnerForRelease();
        if (owner.isEmpty()) {
            return;
        }
        release(owner.orElseThrow(), reasonCode);
    }

    void release(
            TerminalRegistryPort.SessionLeaseOwner owner,
            String reasonCode) {
        try {
            executor.execute(() -> {
                try {
                    registryPort.releaseSessionLease(
                            Objects.requireNonNull(owner, "owner"), reasonCode);
                } catch (RuntimeException ignored) {
                    // API TTL is the fail-closed fallback for a failed release report.
                }
            });
        } catch (RejectedExecutionException ignored) {
            // API TTL is the fail-closed fallback for a saturated reporter queue.
        }
    }

    private void renew(
            TerminalSession session,
            TerminalRegistryPort.SessionLeaseOwner owner) {
        Optional<TerminalRegistryPort.SessionLeaseGrant> renewed;
        try {
            renewed = registryPort.renewSessionLease(owner);
        } catch (RuntimeException failure) {
            renewed = Optional.empty();
        }
        Optional<TerminalRegistryPort.SessionLeaseGrant> result = renewed;
        try {
            session.channel().eventLoop().execute(() -> {
                try {
                    result.filter(grant -> session.leaseOwnerMatches(grant.owner()))
                            .ifPresent(session::acceptRenewal);
                } finally {
                    session.endRenewal();
                }
                if (session.leaseExpired(clock.instant())) {
                    session.close();
                }
            });
        } catch (RejectedExecutionException closed) {
            session.endRenewal();
        }
    }

    private void closeOnEventLoop(TerminalSession session) {
        try {
            session.channel().eventLoop().execute(() -> {
                if (session.leaseExpired(clock.instant())) {
                    session.close();
                }
            });
        } catch (RejectedExecutionException ignored) {
            session.markClosed();
        }
    }
}
