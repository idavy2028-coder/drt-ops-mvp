package com.idavy.drtops.jtgateway.session;

import io.netty.channel.Channel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class TerminalSession {
    private final UUID connectionId = UUID.randomUUID();
    private final Channel channel;
    private final Instant connectedAt;
    private TerminalSessionState state = TerminalSessionState.CONNECTED_UNAUTHENTICATED;
    private UUID terminalId;
    private int tokenVersion;
    private String terminalAlias = "unknown";
    private byte[] terminalIdentityDigest;
    private int authenticationFailures;
    private Instant lastValidMessageAt;

    public TerminalSession(Channel channel, Instant connectedAt) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.connectedAt = Objects.requireNonNull(connectedAt, "connectedAt");
        this.lastValidMessageAt = connectedAt;
    }

    public void registrationAccepted(UUID terminalId, int tokenVersion, String terminalIdentity) {
        requireState(TerminalSessionState.CONNECTED_UNAUTHENTICATED);
        this.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        this.tokenVersion = tokenVersion;
        String identity = Objects.requireNonNull(terminalIdentity, "terminalIdentity");
        this.terminalIdentityDigest = identityDigest(identity);
        int visible = Math.min(4, identity.length());
        this.terminalAlias = "****" + identity.substring(identity.length() - visible);
    }

    public void authenticated(Instant at) {
        requireState(TerminalSessionState.CONNECTED_UNAUTHENTICATED);
        if (terminalId == null) {
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

    public int tokenVersion() {
        return tokenVersion;
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

    private void requireState(TerminalSessionState expected) {
        if (state != expected) {
            throw new IllegalStateException("session transition is not allowed from " + state);
        }
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
