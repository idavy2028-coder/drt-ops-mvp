package com.idavy.drtops.domain.location;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record LocationSourceDecision(
        boolean applySnapshot,
        boolean switchSource,
        UUID selectedTerminalId,
        boolean primaryEligible,
        int primaryRecoveryStreak,
        Instant lastPrimaryValidGatewayReceivedAt,
        Instant primaryTerminalCursorAt,
        Instant backupTerminalCursorAt,
        String reasonCode) {

    public LocationSourceDecision {
        if (switchSource && !applySnapshot) {
            throw new IllegalArgumentException("source switch must apply a snapshot");
        }
        if (applySnapshot && selectedTerminalId == null) {
            throw new IllegalArgumentException(
                    "snapshot application requires a selected terminal");
        }
        if (primaryRecoveryStreak < 0 || primaryRecoveryStreak > 2) {
            throw new IllegalArgumentException(
                    "primaryRecoveryStreak must be between 0 and 2");
        }
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (!reasonCode.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException(
                    "reasonCode must be a safe uppercase code");
        }
    }
}
