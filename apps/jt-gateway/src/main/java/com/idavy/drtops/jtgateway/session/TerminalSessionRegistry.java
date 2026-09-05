package com.idavy.drtops.jtgateway.session;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class TerminalSessionRegistry {
    private final ConcurrentMap<UUID, TerminalSession> authenticatedSessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Object> terminalLocks = new ConcurrentHashMap<>();

    public Optional<TerminalSession> claim(TerminalSession session) {
        return claim(session, ignored -> { });
    }

    public Optional<TerminalSession> claim(
            TerminalSession session,
            Consumer<TerminalSession> beforeReplacement) {
        UUID terminalId = java.util.Objects.requireNonNull(session.terminalId(), "terminalId");
        java.util.Objects.requireNonNull(beforeReplacement, "beforeReplacement");
        TerminalSession previous;
        synchronized (lockFor(terminalId)) {
            previous = authenticatedSessions.get(terminalId);
            if (previous != null && previous != session) {
                beforeReplacement.accept(previous);
                previous.markClosed();
            }
            authenticatedSessions.put(terminalId, session);
        }
        if (previous != null && previous != session) {
            previous.channel().close();
            return Optional.of(previous);
        }
        return Optional.empty();
    }

    public Optional<TerminalSession> current(UUID terminalId) {
        return Optional.ofNullable(authenticatedSessions.get(terminalId));
    }

    public <T> Optional<T> executeIfCurrent(TerminalSession session, Supplier<T> operation) {
        java.util.Objects.requireNonNull(session, "session");
        java.util.Objects.requireNonNull(operation, "operation");
        UUID terminalId = session.terminalId();
        if (terminalId == null) {
            return Optional.empty();
        }
        synchronized (lockFor(terminalId)) {
            if (authenticatedSessions.get(terminalId) != session
                    || session.state() != TerminalSessionState.AUTHENTICATED) {
                return Optional.empty();
            }
            return Optional.ofNullable(operation.get());
        }
    }

    public void remove(TerminalSession session) {
        if (session.terminalId() != null) {
            UUID terminalId = session.terminalId();
            synchronized (lockFor(terminalId)) {
                authenticatedSessions.remove(terminalId, session);
            }
        }
    }

    private Object lockFor(UUID terminalId) {
        return terminalLocks.computeIfAbsent(terminalId, ignored -> new Object());
    }
}
