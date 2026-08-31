package com.idavy.drtops.domain.location;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class LocationSourceArbitrator {

    private static final Duration MINIMUM_PRIMARY_STALE_AFTER = Duration.ofSeconds(30);

    public LocationSourceDecision decide(
            ArbitrationState state,
            PositionCandidate candidate) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(candidate, "candidate");

        boolean primary = candidate.terminalId().equals(state.primaryTerminalId())
                && "LOCATION_PRIMARY".equals(candidate.sourceRole());
        boolean backup = state.backupTerminalId() != null
                && candidate.terminalId().equals(state.backupTerminalId())
                && "LOCATION_BACKUP".equals(candidate.sourceRole());
        if (!primary && !backup) {
            return unchanged(state, "POSITION_NOT_ELIGIBLE");
        }

        boolean eligibleQuality = candidate.qualityStatus() == LocationQualityStatus.GOOD
                || candidate.qualityStatus() == LocationQualityStatus.WARNING;
        if (!eligibleQuality) {
            return primary
                    ? new LocationSourceDecision(
                            false, false, state.activeTerminalId(), false, 0,
                            "POSITION_NOT_ELIGIBLE")
                    : unchanged(state, "POSITION_NOT_ELIGIBLE");
        }

        boolean newerThanSnapshot = state.lastSnapshotAt() == null
                || candidate.terminalLocatedAt().isAfter(state.lastSnapshotAt());
        if (!newerThanSnapshot) {
            return unchanged(state, "POSITION_NOT_ELIGIBLE");
        }

        if (primary) {
            return decidePrimary(state, candidate);
        }
        return decideBackup(state, candidate);
    }

    private LocationSourceDecision decidePrimary(
            ArbitrationState state,
            PositionCandidate candidate) {
        boolean recoveryRequired = !state.primaryEligible()
                || state.primaryRecoveryStreak() > 0
                || (state.activeTerminalId() != null
                        && !state.activeTerminalId().equals(state.primaryTerminalId()));
        if (recoveryRequired) {
            boolean monotonicRecovery = state.lastPrimaryValidAt() == null
                    || candidate.terminalLocatedAt().isAfter(state.lastPrimaryValidAt());
            if (!monotonicRecovery) {
                return unchanged(state, "POSITION_NOT_ELIGIBLE");
            }
            int nextStreak = state.primaryRecoveryStreak() + 1;
            if (nextStreak < 3) {
                return new LocationSourceDecision(
                        false, false, state.activeTerminalId(), state.primaryEligible(), nextStreak,
                        "PRIMARY_RECOVERING");
            }
            boolean sourceChanged = !state.primaryTerminalId().equals(state.activeTerminalId());
            return new LocationSourceDecision(
                    true, sourceChanged, state.primaryTerminalId(), true, 0,
                    "PRIMARY_RECOVERED");
        }

        boolean initialSelection = state.activeTerminalId() == null;
        return new LocationSourceDecision(
                true,
                initialSelection,
                state.primaryTerminalId(),
                true,
                0,
                initialSelection ? "PRIMARY_SELECTED" : "ACTIVE_SOURCE_ACCEPTED");
    }

    private LocationSourceDecision decideBackup(
            ArbitrationState state,
            PositionCandidate candidate) {
        if (state.backupTerminalId().equals(state.activeTerminalId())) {
            return new LocationSourceDecision(
                    true, false, state.backupTerminalId(), state.primaryEligible(),
                    state.primaryRecoveryStreak(), "ACTIVE_SOURCE_ACCEPTED");
        }

        if (state.activeTerminalId() == null && state.primaryEligible()
                && state.lastPrimaryValidAt() != null) {
            return unchanged(state, "NON_ACTIVE_SOURCE_IGNORED");
        }

        boolean primaryUnavailable = !state.primaryEligible()
                || state.lastPrimaryValidAt() == null
                || !candidate.gatewayReceivedAt().isBefore(
                        state.lastPrimaryValidAt().plus(staleAfter(state.expectedPrimaryInterval())));
        if (!primaryUnavailable) {
            return unchanged(state, "NON_ACTIVE_SOURCE_IGNORED");
        }

        return new LocationSourceDecision(
                true,
                !state.backupTerminalId().equals(state.activeTerminalId()),
                state.backupTerminalId(),
                state.primaryEligible(),
                0,
                state.primaryEligible() ? "PRIMARY_STALE" : "PRIMARY_QUALITY_REJECTED");
    }

    private static LocationSourceDecision unchanged(
            ArbitrationState state,
            String reasonCode) {
        return new LocationSourceDecision(
                false,
                false,
                state.activeTerminalId(),
                state.primaryEligible(),
                state.primaryRecoveryStreak(),
                reasonCode);
    }

    private static Duration staleAfter(Duration expectedPrimaryInterval) {
        Duration doubled = expectedPrimaryInterval.multipliedBy(2);
        return doubled.compareTo(MINIMUM_PRIMARY_STALE_AFTER) < 0
                ? MINIMUM_PRIMARY_STALE_AFTER
                : doubled;
    }

    public record ArbitrationState(
            UUID primaryTerminalId,
            UUID backupTerminalId,
            UUID activeTerminalId,
            Instant lastPrimaryValidAt,
            Instant lastSnapshotAt,
            Duration expectedPrimaryInterval,
            boolean primaryEligible,
            int primaryRecoveryStreak) {

        public ArbitrationState {
            Objects.requireNonNull(primaryTerminalId, "primaryTerminalId");
            if (primaryTerminalId.equals(backupTerminalId)) {
                throw new IllegalArgumentException("backup terminal must differ from primary terminal");
            }
            if (activeTerminalId != null
                    && !activeTerminalId.equals(primaryTerminalId)
                    && !activeTerminalId.equals(backupTerminalId)) {
                throw new IllegalArgumentException("active terminal must be the configured primary or backup");
            }
            Objects.requireNonNull(expectedPrimaryInterval, "expectedPrimaryInterval");
            if (expectedPrimaryInterval.isZero() || expectedPrimaryInterval.isNegative()) {
                throw new IllegalArgumentException("expectedPrimaryInterval must be positive");
            }
            try {
                Duration threshold = expectedPrimaryInterval.multipliedBy(2);
                if (lastPrimaryValidAt != null) {
                    lastPrimaryValidAt.plus(threshold);
                }
            } catch (ArithmeticException | DateTimeException invalidInterval) {
                throw new IllegalArgumentException("expectedPrimaryInterval is too large", invalidInterval);
            }
            if (primaryRecoveryStreak < 0 || primaryRecoveryStreak > 2) {
                throw new IllegalArgumentException("primaryRecoveryStreak must be between 0 and 2");
            }
        }
    }

    public record PositionCandidate(
            UUID terminalId,
            String sourceRole,
            LocationQualityStatus qualityStatus,
            Instant terminalLocatedAt,
            Instant gatewayReceivedAt) {

        public PositionCandidate {
            Objects.requireNonNull(terminalId, "terminalId");
            Objects.requireNonNull(sourceRole, "sourceRole");
            if (sourceRole.isBlank()) {
                throw new IllegalArgumentException("sourceRole must not be blank");
            }
            Objects.requireNonNull(qualityStatus, "qualityStatus");
            Objects.requireNonNull(terminalLocatedAt, "terminalLocatedAt");
            Objects.requireNonNull(gatewayReceivedAt, "gatewayReceivedAt");
        }
    }
}
