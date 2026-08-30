package com.idavy.drtops.jtgateway.session;

import java.util.Objects;

public record AuthenticationDecision(
        boolean approved,
        TerminalSessionContext context,
        AuthenticationRejection rejection) {
    public AuthenticationDecision {
        if (approved && (context == null || rejection != null)) {
            throw new IllegalArgumentException("approved authentication requires context");
        }
        if (!approved && (context != null || rejection == null)) {
            throw new IllegalArgumentException("authentication decision is inconsistent");
        }
    }

    /** @deprecated approval without a physical-terminal context is no longer valid. */
    @Deprecated(forRemoval = false)
    public static AuthenticationDecision allow() {
        throw new IllegalArgumentException(
                "approved authentication requires a terminal-session context");
    }

    public static AuthenticationDecision allow(TerminalSessionContext context) {
        return new AuthenticationDecision(true, Objects.requireNonNull(context, "context"), null);
    }

    public static AuthenticationDecision rejected(AuthenticationRejection rejection) {
        return new AuthenticationDecision(
                false, null, Objects.requireNonNull(rejection, "rejection"));
    }
}
