package com.idavy.drtops.jtgateway.session;

import java.util.Objects;

public record AuthenticationDecision(boolean approved, AuthenticationRejection rejection) {
    public AuthenticationDecision {
        if (approved == (rejection != null)) {
            throw new IllegalArgumentException("authentication decision is inconsistent");
        }
    }

    public static AuthenticationDecision allow() {
        return new AuthenticationDecision(true, null);
    }

    public static AuthenticationDecision rejected(AuthenticationRejection rejection) {
        return new AuthenticationDecision(false, Objects.requireNonNull(rejection, "rejection"));
    }
}
