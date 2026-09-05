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

        Instant sourceCursor = primary
                ? state.primaryTerminalCursorAt()
                : state.backupTerminalCursorAt();
        if (sourceCursor != null
                && !candidate.terminalLocatedAt().isAfter(sourceCursor)) {
            return unchanged(state, "POSITION_NOT_ELIGIBLE");
        }
        if (state.lastSnapshotGatewayReceivedAt() != null
                && !candidate.gatewayReceivedAt().isAfter(
                        state.lastSnapshotGatewayReceivedAt())) {
            return unchanged(state, "POSITION_NOT_ELIGIBLE");
        }

        Instant nextPrimaryCursor = primary
                ? candidate.terminalLocatedAt()
                : state.primaryTerminalCursorAt();
        Instant nextBackupCursor = backup
                ? candidate.terminalLocatedAt()
                : state.backupTerminalCursorAt();
        boolean eligibleQuality = candidate.qualityStatus() == LocationQualityStatus.GOOD
                || candidate.qualityStatus() == LocationQualityStatus.WARNING;
        if (!eligibleQuality) {
            return primary
                    ? decision(
                            false,
                            false,
                            state.activeTerminalId(),
                            false,
                            0,
                            state.lastPrimaryValidGatewayReceivedAt(),
                            nextPrimaryCursor,
                            nextBackupCursor,
                            "POSITION_NOT_ELIGIBLE")
                    : decision(
                            false,
                            false,
                            state.activeTerminalId(),
                            state.primaryEligible(),
                            state.primaryRecoveryStreak(),
                            state.lastPrimaryValidGatewayReceivedAt(),
                            nextPrimaryCursor,
                            nextBackupCursor,
                            "POSITION_NOT_ELIGIBLE");
        }

        if (primary) {
            return decidePrimary(
                    state, candidate, nextPrimaryCursor, nextBackupCursor);
        }
        return decideBackup(state, candidate, nextPrimaryCursor, nextBackupCursor);
    }

    private LocationSourceDecision decidePrimary(
            ArbitrationState state,
            PositionCandidate candidate,
            Instant nextPrimaryCursor,
            Instant nextBackupCursor) {
        Instant nextPrimaryGateway = later(
                state.lastPrimaryValidGatewayReceivedAt(),
                candidate.gatewayReceivedAt());
        boolean recoveryRequired = !state.primaryEligible()
                || state.primaryRecoveryStreak() > 0
                || (state.activeTerminalId() != null
                        && !state.activeTerminalId().equals(state.primaryTerminalId()));
        if (recoveryRequired) {
            int nextStreak = state.primaryRecoveryStreak() + 1;
            if (nextStreak < 3) {
                return decision(
                        false,
                        false,
                        state.activeTerminalId(),
                        state.primaryEligible(),
                        nextStreak,
                        nextPrimaryGateway,
                        nextPrimaryCursor,
                        nextBackupCursor,
                        "PRIMARY_RECOVERING");
            }
            boolean sourceChanged = !state.primaryTerminalId().equals(
                    state.activeTerminalId());
            return decision(
                    true,
                    sourceChanged,
                    state.primaryTerminalId(),
                    true,
                    0,
                    nextPrimaryGateway,
                    nextPrimaryCursor,
                    nextBackupCursor,
                    "PRIMARY_RECOVERED");
        }

        boolean initialSelection = state.activeTerminalId() == null;
        return decision(
                true,
                initialSelection,
                state.primaryTerminalId(),
                true,
                0,
                nextPrimaryGateway,
                nextPrimaryCursor,
                nextBackupCursor,
                initialSelection ? "PRIMARY_SELECTED" : "ACTIVE_SOURCE_ACCEPTED");
    }

    private LocationSourceDecision decideBackup(
            ArbitrationState state,
            PositionCandidate candidate,
            Instant nextPrimaryCursor,
            Instant nextBackupCursor) {
        if (state.backupTerminalId().equals(state.activeTerminalId())) {
            return decision(
                    true,
                    false,
                    state.backupTerminalId(),
                    state.primaryEligible(),
                    state.primaryRecoveryStreak(),
                    state.lastPrimaryValidGatewayReceivedAt(),
                    nextPrimaryCursor,
                    nextBackupCursor,
                    "ACTIVE_SOURCE_ACCEPTED");
        }

        boolean primaryUnavailable = !state.primaryEligible()
                || state.lastPrimaryValidGatewayReceivedAt() == null
                || !candidate.gatewayReceivedAt().isBefore(
                        state.lastPrimaryValidGatewayReceivedAt()
                                .plus(staleAfter(state.expectedPrimaryInterval())));
        if (!primaryUnavailable) {
            return decision(
                    false,
                    false,
                    state.activeTerminalId(),
                    state.primaryEligible(),
                    state.primaryRecoveryStreak(),
                    state.lastPrimaryValidGatewayReceivedAt(),
                    nextPrimaryCursor,
                    nextBackupCursor,
                    "NON_ACTIVE_SOURCE_IGNORED");
        }

        return decision(
                true,
                !state.backupTerminalId().equals(state.activeTerminalId()),
                state.backupTerminalId(),
                state.primaryEligible(),
                0,
                state.lastPrimaryValidGatewayReceivedAt(),
                nextPrimaryCursor,
                nextBackupCursor,
                state.primaryEligible() ? "PRIMARY_STALE" : "PRIMARY_QUALITY_REJECTED");
    }

    private static LocationSourceDecision unchanged(
            ArbitrationState state,
            String reasonCode) {
        return decision(
                false,
                false,
                state.activeTerminalId(),
                state.primaryEligible(),
                state.primaryRecoveryStreak(),
                state.lastPrimaryValidGatewayReceivedAt(),
                state.primaryTerminalCursorAt(),
                state.backupTerminalCursorAt(),
                reasonCode);
    }

    private static LocationSourceDecision decision(
            boolean applySnapshot,
            boolean switchSource,
            UUID selectedTerminalId,
            boolean primaryEligible,
            int primaryRecoveryStreak,
            Instant lastPrimaryValidGatewayReceivedAt,
            Instant primaryTerminalCursorAt,
            Instant backupTerminalCursorAt,
            String reasonCode) {
        return new LocationSourceDecision(
                applySnapshot,
                switchSource,
                selectedTerminalId,
                primaryEligible,
                primaryRecoveryStreak,
                lastPrimaryValidGatewayReceivedAt,
                primaryTerminalCursorAt,
                backupTerminalCursorAt,
                reasonCode);
    }

    private static Duration staleAfter(Duration expectedPrimaryInterval) {
        Duration doubled = expectedPrimaryInterval.multipliedBy(2);
        return doubled.compareTo(MINIMUM_PRIMARY_STALE_AFTER) < 0
                ? MINIMUM_PRIMARY_STALE_AFTER
                : doubled;
    }

    private static Instant later(Instant current, Instant candidate) {
        return current != null && current.isAfter(candidate) ? current : candidate;
    }

    public record ArbitrationState(
            UUID primaryTerminalId,
            UUID backupTerminalId,
            UUID activeTerminalId,
            Instant lastPrimaryValidGatewayReceivedAt,
            Instant primaryTerminalCursorAt,
            Instant backupTerminalCursorAt,
            Instant lastSnapshotGatewayReceivedAt,
            Duration expectedPrimaryInterval,
            boolean primaryEligible,
            int primaryRecoveryStreak) {

        public ArbitrationState {
            Objects.requireNonNull(primaryTerminalId, "primaryTerminalId");
            if (primaryTerminalId.equals(backupTerminalId)) {
                throw new IllegalArgumentException(
                        "backup terminal must differ from primary terminal");
            }
            if (activeTerminalId != null
                    && !activeTerminalId.equals(primaryTerminalId)
                    && !activeTerminalId.equals(backupTerminalId)) {
                throw new IllegalArgumentException(
                        "active terminal must be the configured primary or backup");
            }
            Objects.requireNonNull(expectedPrimaryInterval, "expectedPrimaryInterval");
            if (expectedPrimaryInterval.isZero() || expectedPrimaryInterval.isNegative()) {
                throw new IllegalArgumentException(
                        "expectedPrimaryInterval must be positive");
            }
            try {
                Duration threshold = expectedPrimaryInterval.multipliedBy(2);
                if (lastPrimaryValidGatewayReceivedAt != null) {
                    lastPrimaryValidGatewayReceivedAt.plus(threshold);
                }
            } catch (ArithmeticException | DateTimeException invalidInterval) {
                throw new IllegalArgumentException(
                        "expectedPrimaryInterval is too large", invalidInterval);
            }
            if (primaryRecoveryStreak < 0 || primaryRecoveryStreak > 2) {
                throw new IllegalArgumentException(
                        "primaryRecoveryStreak must be between 0 and 2");
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
