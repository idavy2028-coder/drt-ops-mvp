package com.idavy.drtops.jtgateway.session;

import java.util.Objects;
import java.util.Optional;

public record AuthenticationDecision(
        boolean approved,
        TerminalSessionContext context,
        TerminalRegistryPort.SessionLeaseGrant lease,
        AuthenticationRejection rejection,
        TerminalRegistryPort.SessionLeaseOwner cleanupLeaseOwner) {
    public AuthenticationDecision {
        if (approved && (context == null || lease == null || rejection != null
                || cleanupLeaseOwner != null)) {
            throw new IllegalArgumentException("approved authentication requires context and lease");
        }
        if (!approved && (context != null || lease != null || rejection == null)) {
            throw new IllegalArgumentException("authentication decision is inconsistent");
        }
    }

    /** @deprecated approval without a physical-terminal context is no longer valid. */
    @Deprecated(forRemoval = false)
    public static AuthenticationDecision allow() {
        throw new IllegalArgumentException(
                "approved authentication requires a terminal-session context");
    }

    public static AuthenticationDecision allow(
            TerminalSessionContext context,
            TerminalRegistryPort.SessionLeaseGrant lease) {
        return new AuthenticationDecision(
                true,
                Objects.requireNonNull(context, "context"),
                Objects.requireNonNull(lease, "lease"),
                null,
                null);
    }

    public static AuthenticationDecision rejected(AuthenticationRejection rejection) {
        return new AuthenticationDecision(
                false, null, null, Objects.requireNonNull(rejection, "rejection"), null);
    }

    public static AuthenticationDecision rejectedWithCleanup(
            AuthenticationRejection rejection,
            TerminalRegistryPort.SessionLeaseOwner cleanupOwner) {
        return new AuthenticationDecision(
                false,
                null,
                null,
                Objects.requireNonNull(rejection, "rejection"),
                Objects.requireNonNull(cleanupOwner, "cleanupOwner"));
    }

    public Optional<TerminalRegistryPort.SessionLeaseOwner> cleanupOwner() {
        return Optional.ofNullable(cleanupLeaseOwner);
    }

    @Override
    public String toString() {
        return "AuthenticationDecision[approved=" + approved
                + ", context=" + context
                + ", lease=" + lease
                + ", rejection=" + rejection
                + ", cleanupOwnerPresent=" + (cleanupLeaseOwner != null) + "]";
    }
}
