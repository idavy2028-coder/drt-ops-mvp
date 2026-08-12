package com.idavy.drtops.jtgateway.session;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class TerminalSessionRegistry {
    private final ConcurrentMap<UUID, TerminalSession> authenticatedSessions = new ConcurrentHashMap<>();

    public Optional<TerminalSession> claim(TerminalSession session) {
        UUID terminalId = java.util.Objects.requireNonNull(session.terminalId(), "terminalId");
        TerminalSession previous = authenticatedSessions.put(terminalId, session);
        if (previous != null && previous != session) {
            previous.close();
            return Optional.of(previous);
        }
        return Optional.empty();
    }

    public Optional<TerminalSession> current(UUID terminalId) {
        return Optional.ofNullable(authenticatedSessions.get(terminalId));
    }

    public void remove(TerminalSession session) {
        if (session.terminalId() != null) {
            authenticatedSessions.remove(session.terminalId(), session);
        }
    }
}
