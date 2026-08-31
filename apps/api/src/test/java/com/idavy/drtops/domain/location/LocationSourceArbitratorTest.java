package com.idavy.drtops.domain.location;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idavy.drtops.domain.location.LocationSourceArbitrator.ArbitrationState;
import com.idavy.drtops.domain.location.LocationSourceArbitrator.PositionCandidate;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LocationSourceArbitratorTest {

    private static final UUID PRIMARY = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID BACKUP = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID UNKNOWN = UUID.fromString("10000000-0000-0000-0000-000000000003");
    private static final Instant BASE = Instant.parse("2026-08-29T02:00:00Z");

    private final LocationSourceArbitrator arbitrator = new LocationSourceArbitrator();

    @Test
    void selectsEligiblePrimaryForTheInitialSnapshot() {
        LocationSourceDecision decision = arbitrator.decide(
                state(BACKUP, null, null, null, true, 0, Duration.ofSeconds(10)),
                primary(LocationQualityStatus.GOOD, BASE, BASE.plusSeconds(1)));

        assertThat(decision.applySnapshot()).isTrue();
        assertThat(decision.switchSource()).isTrue();
        assertThat(decision.selectedTerminalId()).isEqualTo(PRIMARY);
        assertThat(decision.primaryEligible()).isTrue();
        assertThat(decision.primaryRecoveryStreak()).isZero();
        assertThat(decision.reasonCode()).isEqualTo("PRIMARY_SELECTED");
    }

    @Test
    void selectsEligibleBackupOnlyWhenNoValidPrimaryHasEverBeenObserved() {
        LocationSourceDecision initialBackup = arbitrator.decide(
                state(BACKUP, null, null, null, true, 0, Duration.ofSeconds(10)),
                backup(LocationQualityStatus.WARNING, BASE, BASE.plusSeconds(1)));
        LocationSourceDecision backupAfterPrimaryHistory = arbitrator.decide(
                state(BACKUP, null, BASE, null, true, 0, Duration.ofSeconds(10)),
                backup(LocationQualityStatus.GOOD, BASE.plusSeconds(1), BASE.plusSeconds(31)));

        assertThat(initialBackup.applySnapshot()).isTrue();
        assertThat(initialBackup.switchSource()).isTrue();
        assertThat(initialBackup.selectedTerminalId()).isEqualTo(BACKUP);
        assertThat(initialBackup.primaryEligible()).isTrue();
        assertThat(initialBackup.reasonCode()).isEqualTo("PRIMARY_STALE");
        assertThat(backupAfterPrimaryHistory.applySnapshot()).isFalse();
        assertThat(backupAfterPrimaryHistory.switchSource()).isFalse();
        assertThat(backupAfterPrimaryHistory.selectedTerminalId()).isNull();
        assertThat(backupAfterPrimaryHistory.reasonCode()).isEqualTo("NON_ACTIVE_SOURCE_IGNORED");
    }

    @Test
    void usesThirtySecondFloorAndTakesOverAtTheExactBoundary() {
        ArbitrationState state = state(
                BACKUP, PRIMARY, BASE, BASE, true, 0, Duration.ofSeconds(10));

        LocationSourceDecision beforeBoundary = arbitrator.decide(
                state,
                backup(LocationQualityStatus.GOOD, BASE.plusSeconds(1),
                        BASE.plusSeconds(30).minusNanos(1)));
        LocationSourceDecision atBoundary = arbitrator.decide(
                state,
                backup(LocationQualityStatus.GOOD, BASE.plusSeconds(1), BASE.plusSeconds(30)));

        assertThat(beforeBoundary.applySnapshot()).isFalse();
        assertThat(beforeBoundary.selectedTerminalId()).isEqualTo(PRIMARY);
        assertThat(atBoundary.applySnapshot()).isTrue();
        assertThat(atBoundary.switchSource()).isTrue();
        assertThat(atBoundary.selectedTerminalId()).isEqualTo(BACKUP);
        assertThat(atBoundary.primaryEligible()).isTrue();
        assertThat(atBoundary.reasonCode()).isEqualTo("PRIMARY_STALE");
    }

    @Test
    void usesTwiceTheExpectedIntervalWhenItExceedsTheFloor() {
        ArbitrationState state = state(
                BACKUP, PRIMARY, BASE, BASE, true, 0, Duration.ofSeconds(20));

        LocationSourceDecision beforeBoundary = arbitrator.decide(
                state,
                backup(LocationQualityStatus.GOOD, BASE.plusSeconds(1),
                        BASE.plusSeconds(40).minusNanos(1)));
        LocationSourceDecision atBoundary = arbitrator.decide(
                state,
                backup(LocationQualityStatus.GOOD, BASE.plusSeconds(1), BASE.plusSeconds(40)));

        assertThat(beforeBoundary.applySnapshot()).isFalse();
        assertThat(atBoundary.applySnapshot()).isTrue();
        assertThat(atBoundary.reasonCode()).isEqualTo("PRIMARY_STALE");
    }

    @Test
    void invalidPrimaryQualityMarksItIneligibleAndFreshBackupTakesOver() {
        ArbitrationState activePrimary = state(
                BACKUP, PRIMARY, BASE, BASE, true, 2, Duration.ofSeconds(60));

        for (LocationQualityStatus invalid : new LocationQualityStatus[] {
                LocationQualityStatus.QUARANTINED, LocationQualityStatus.REJECTED}) {
            LocationSourceDecision rejected = arbitrator.decide(
                    activePrimary,
                    primary(invalid, BASE.plusSeconds(1), BASE.plusSeconds(1)));
            assertThat(rejected.applySnapshot()).isFalse();
            assertThat(rejected.switchSource()).isFalse();
            assertThat(rejected.selectedTerminalId()).isEqualTo(PRIMARY);
            assertThat(rejected.primaryEligible()).isFalse();
            assertThat(rejected.primaryRecoveryStreak()).isZero();
            assertThat(rejected.reasonCode()).isEqualTo("POSITION_NOT_ELIGIBLE");

            LocationSourceDecision takeover = arbitrator.decide(
                    state(BACKUP, PRIMARY, BASE, BASE, false, 0, Duration.ofSeconds(60)),
                    backup(LocationQualityStatus.GOOD, BASE.plusSeconds(2), BASE.plusSeconds(2)));
            assertThat(takeover.applySnapshot()).isTrue();
            assertThat(takeover.switchSource()).isTrue();
            assertThat(takeover.selectedTerminalId()).isEqualTo(BACKUP);
            assertThat(takeover.primaryEligible()).isFalse();
            assertThat(takeover.reasonCode()).isEqualTo("PRIMARY_QUALITY_REJECTED");
        }
    }

    @Test
    void freshBackupCannotOverridePrimaryButActiveBackupRemainsAuthoritative() {
        LocationSourceDecision ignored = arbitrator.decide(
                state(BACKUP, PRIMARY, BASE, BASE, true, 0, Duration.ofSeconds(30)),
                backup(LocationQualityStatus.GOOD, BASE.plusSeconds(1), BASE.plusSeconds(29)));
        LocationSourceDecision activeBackup = arbitrator.decide(
                state(BACKUP, BACKUP, BASE, BASE, true, 2, Duration.ofSeconds(30)),
                backup(LocationQualityStatus.WARNING, BASE.plusSeconds(1), BASE.plusSeconds(1)));

        assertThat(ignored.applySnapshot()).isFalse();
        assertThat(ignored.selectedTerminalId()).isEqualTo(PRIMARY);
        assertThat(ignored.reasonCode()).isEqualTo("NON_ACTIVE_SOURCE_IGNORED");
        assertThat(activeBackup.applySnapshot()).isTrue();
        assertThat(activeBackup.switchSource()).isFalse();
        assertThat(activeBackup.selectedTerminalId()).isEqualTo(BACKUP);
        assertThat(activeBackup.primaryEligible()).isTrue();
        assertThat(activeBackup.primaryRecoveryStreak()).isEqualTo(2);
        assertThat(activeBackup.reasonCode()).isEqualTo("ACTIVE_SOURCE_ACCEPTED");
    }

    @Test
    void preservesEligibilityDuringThreeReportFailbackAfterStaleTakeover() {
        LocationSourceDecision first = arbitrator.decide(
                state(BACKUP, BACKUP, BASE, BASE, true, 0, Duration.ofSeconds(30)),
                primary(LocationQualityStatus.GOOD, BASE.plusSeconds(1), BASE.plusSeconds(1)));
        LocationSourceDecision second = arbitrator.decide(
                state(BACKUP, BACKUP, BASE.plusSeconds(1), BASE, true, 1, Duration.ofSeconds(30)),
                primary(LocationQualityStatus.WARNING, BASE.plusSeconds(2), BASE.plusSeconds(2)));
        LocationSourceDecision third = arbitrator.decide(
                state(BACKUP, BACKUP, BASE.plusSeconds(2), BASE, true, 2, Duration.ofSeconds(30)),
                primary(LocationQualityStatus.GOOD, BASE.plusSeconds(3), BASE.plusSeconds(3)));

        assertThat(first.applySnapshot()).isFalse();
        assertThat(first.switchSource()).isFalse();
        assertThat(first.selectedTerminalId()).isEqualTo(BACKUP);
        assertThat(first.primaryEligible()).isTrue();
        assertThat(first.primaryRecoveryStreak()).isEqualTo(1);
        assertThat(first.reasonCode()).isEqualTo("PRIMARY_RECOVERING");
        assertThat(second.applySnapshot()).isFalse();
        assertThat(second.primaryRecoveryStreak()).isEqualTo(2);
        assertThat(second.reasonCode()).isEqualTo("PRIMARY_RECOVERING");
        assertThat(third.applySnapshot()).isTrue();
        assertThat(third.switchSource()).isTrue();
        assertThat(third.selectedTerminalId()).isEqualTo(PRIMARY);
        assertThat(third.primaryRecoveryStreak()).isZero();
        assertThat(third.reasonCode()).isEqualTo("PRIMARY_RECOVERED");
    }

    @Test
    void keepsSelectedPrimaryIneligibleUntilThirdRecoveryAndLetsBackupTakeOverAfterFirst() {
        LocationSourceDecision first = arbitrator.decide(
                state(BACKUP, PRIMARY, BASE, BASE, false, 0, Duration.ofSeconds(10)),
                primary(LocationQualityStatus.GOOD, BASE.plusSeconds(1), BASE.plusSeconds(1)));
        LocationSourceDecision backupAfterFirst = arbitrator.decide(
                state(BACKUP, PRIMARY, BASE.plusSeconds(1), BASE,
                        first.primaryEligible(), first.primaryRecoveryStreak(), Duration.ofSeconds(10)),
                backup(LocationQualityStatus.GOOD, BASE.plusSeconds(2), BASE.plusSeconds(2)));
        LocationSourceDecision second = arbitrator.decide(
                state(BACKUP, PRIMARY, BASE.plusSeconds(1), BASE,
                        first.primaryEligible(), first.primaryRecoveryStreak(), Duration.ofSeconds(10)),
                primary(LocationQualityStatus.GOOD, BASE.plusSeconds(2), BASE.plusSeconds(2)));
        LocationSourceDecision third = arbitrator.decide(
                state(BACKUP, PRIMARY, BASE.plusSeconds(2), BASE,
                        second.primaryEligible(), second.primaryRecoveryStreak(), Duration.ofSeconds(10)),
                primary(LocationQualityStatus.GOOD, BASE.plusSeconds(3), BASE.plusSeconds(3)));

        assertThat(first.applySnapshot()).isFalse();
        assertThat(first.switchSource()).isFalse();
        assertThat(first.selectedTerminalId()).isEqualTo(PRIMARY);
        assertThat(first.primaryEligible()).isFalse();
        assertThat(first.primaryRecoveryStreak()).isEqualTo(1);
        assertThat(first.reasonCode()).isEqualTo("PRIMARY_RECOVERING");
        assertThat(backupAfterFirst.applySnapshot()).isTrue();
        assertThat(backupAfterFirst.switchSource()).isTrue();
        assertThat(backupAfterFirst.selectedTerminalId()).isEqualTo(BACKUP);
        assertThat(backupAfterFirst.primaryEligible()).isFalse();
        assertThat(backupAfterFirst.reasonCode()).isEqualTo("PRIMARY_QUALITY_REJECTED");
        assertThat(second.applySnapshot()).isFalse();
        assertThat(second.switchSource()).isFalse();
        assertThat(second.primaryEligible()).isFalse();
        assertThat(second.primaryRecoveryStreak()).isEqualTo(2);
        assertThat(second.reasonCode()).isEqualTo("PRIMARY_RECOVERING");
        assertThat(third.applySnapshot()).isTrue();
        assertThat(third.switchSource()).isFalse();
        assertThat(third.selectedTerminalId()).isEqualTo(PRIMARY);
        assertThat(third.primaryEligible()).isTrue();
        assertThat(third.primaryRecoveryStreak()).isZero();
        assertThat(third.reasonCode()).isEqualTo("PRIMARY_RECOVERED");
    }

    @Test
    void lateValidPrimaryLeavesEligibilityAndRecoveryStateUnchanged() {
        LocationSourceDecision decision = arbitrator.decide(
                state(BACKUP, BACKUP, BASE.plusSeconds(10), BASE.plusSeconds(20),
                        false, 2, Duration.ofSeconds(10)),
                primary(LocationQualityStatus.GOOD, BASE.plusSeconds(15), BASE.plusSeconds(21)));

        assertThat(decision.applySnapshot()).isFalse();
        assertThat(decision.switchSource()).isFalse();
        assertThat(decision.selectedTerminalId()).isEqualTo(BACKUP);
        assertThat(decision.primaryEligible()).isFalse();
        assertThat(decision.primaryRecoveryStreak()).isEqualTo(2);
        assertThat(decision.reasonCode()).isEqualTo("POSITION_NOT_ELIGIBLE");
    }

    @Test
    void nonMonotonicPrimaryRecoveryTimestampDoesNotAdvanceTheStreak() {
        LocationSourceDecision decision = arbitrator.decide(
                state(BACKUP, BACKUP, BASE.plusSeconds(10), BASE.plusSeconds(5),
                        true, 1, Duration.ofSeconds(10)),
                primary(LocationQualityStatus.GOOD, BASE.plusSeconds(10), BASE.plusSeconds(11)));

        assertThat(decision.applySnapshot()).isFalse();
        assertThat(decision.primaryEligible()).isTrue();
        assertThat(decision.primaryRecoveryStreak()).isEqualTo(1);
        assertThat(decision.reasonCode()).isEqualTo("POSITION_NOT_ELIGIBLE");
    }

    @Test
    void invalidPrimaryResetsRecoveryButInvalidOrLateBackupDoesNotMutatePrimaryState() {
        ArbitrationState recovering = state(
                BACKUP, BACKUP, BASE, BASE, true, 2, Duration.ofSeconds(10));
        LocationSourceDecision invalidPrimary = arbitrator.decide(
                recovering,
                primary(LocationQualityStatus.QUARANTINED, BASE.plusSeconds(1), BASE.plusSeconds(1)));
        LocationSourceDecision invalidBackup = arbitrator.decide(
                recovering,
                backup(LocationQualityStatus.REJECTED, BASE.plusSeconds(1), BASE.plusSeconds(1)));
        LocationSourceDecision lateBackup = arbitrator.decide(
                recovering,
                backup(LocationQualityStatus.GOOD, BASE.minusSeconds(1), BASE.plusSeconds(1)));

        assertThat(invalidPrimary.primaryEligible()).isFalse();
        assertThat(invalidPrimary.primaryRecoveryStreak()).isZero();
        assertThat(invalidBackup.primaryEligible()).isTrue();
        assertThat(invalidBackup.primaryRecoveryStreak()).isEqualTo(2);
        assertThat(lateBackup.primaryEligible()).isTrue();
        assertThat(lateBackup.primaryRecoveryStreak()).isEqualTo(2);
        assertThat(invalidBackup.applySnapshot()).isFalse();
        assertThat(lateBackup.applySnapshot()).isFalse();
    }

    @Test
    void singleDeviceInitializesThenRequiresThreeReportsAfterQualityInvalidation() {
        LocationSourceDecision initial = arbitrator.decide(
                state(null, null, null, null, true, 0, Duration.ofSeconds(10)),
                primary(LocationQualityStatus.GOOD, BASE, BASE));
        LocationSourceDecision invalid = arbitrator.decide(
                state(null, PRIMARY, BASE, BASE, true, 0, Duration.ofSeconds(10)),
                primary(LocationQualityStatus.REJECTED, BASE.plusSeconds(1), BASE.plusSeconds(1)));
        LocationSourceDecision first = arbitrator.decide(
                state(null, PRIMARY, BASE, BASE, false, 0, Duration.ofSeconds(10)),
                primary(LocationQualityStatus.GOOD, BASE.plusSeconds(2), BASE.plusSeconds(2)));
        LocationSourceDecision second = arbitrator.decide(
                state(null, PRIMARY, BASE.plusSeconds(2), BASE, false, 1, Duration.ofSeconds(10)),
                primary(LocationQualityStatus.GOOD, BASE.plusSeconds(3), BASE.plusSeconds(3)));
        LocationSourceDecision third = arbitrator.decide(
                state(null, PRIMARY, BASE.plusSeconds(3), BASE, false, 2, Duration.ofSeconds(10)),
                primary(LocationQualityStatus.GOOD, BASE.plusSeconds(4), BASE.plusSeconds(4)));

        assertThat(initial.applySnapshot()).isTrue();
        assertThat(initial.switchSource()).isTrue();
        assertThat(invalid.applySnapshot()).isFalse();
        assertThat(invalid.primaryEligible()).isFalse();
        assertThat(first.applySnapshot()).isFalse();
        assertThat(first.primaryEligible()).isFalse();
        assertThat(first.primaryRecoveryStreak()).isEqualTo(1);
        assertThat(second.applySnapshot()).isFalse();
        assertThat(second.primaryEligible()).isFalse();
        assertThat(second.primaryRecoveryStreak()).isEqualTo(2);
        assertThat(third.applySnapshot()).isTrue();
        assertThat(third.switchSource()).isFalse();
        assertThat(third.primaryEligible()).isTrue();
        assertThat(third.reasonCode()).isEqualTo("PRIMARY_RECOVERED");
    }

    @Test
    void unknownTerminalOrMismatchedRoleIsSafelyIgnoredWithoutStateMutation() {
        ArbitrationState state = state(
                BACKUP, PRIMARY, BASE, BASE, true, 2, Duration.ofSeconds(10));

        for (PositionCandidate candidate : new PositionCandidate[] {
                new PositionCandidate(UNKNOWN, "LOCATION_PRIMARY", LocationQualityStatus.GOOD,
                        BASE.plusSeconds(1), BASE.plusSeconds(1)),
                new PositionCandidate(PRIMARY, "LOCATION_BACKUP", LocationQualityStatus.GOOD,
                        BASE.plusSeconds(1), BASE.plusSeconds(1)),
                new PositionCandidate(BACKUP, "LOCATION_PRIMARY", LocationQualityStatus.GOOD,
                        BASE.plusSeconds(1), BASE.plusSeconds(1))}) {
            LocationSourceDecision decision = arbitrator.decide(state, candidate);
            assertThat(decision.applySnapshot()).isFalse();
            assertThat(decision.switchSource()).isFalse();
            assertThat(decision.selectedTerminalId()).isEqualTo(PRIMARY);
            assertThat(decision.primaryEligible()).isTrue();
            assertThat(decision.primaryRecoveryStreak()).isEqualTo(2);
            assertThat(decision.reasonCode()).isEqualTo("POSITION_NOT_ELIGIBLE");
        }
    }

    @Test
    void validatesStateAndCandidateContractsBeforeArbitration() {
        assertThatThrownBy(() -> new ArbitrationState(
                null, BACKUP, null, null, null, Duration.ofSeconds(10), true, 0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ArbitrationState(
                PRIMARY, PRIMARY, null, null, null, Duration.ofSeconds(10), true, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArbitrationState(
                PRIMARY, BACKUP, UNKNOWN, null, null, Duration.ofSeconds(10), true, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArbitrationState(
                PRIMARY, BACKUP, null, null, null, Duration.ZERO, true, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArbitrationState(
                PRIMARY, BACKUP, null, null, null, Duration.ofSeconds(Long.MAX_VALUE), true, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArbitrationState(
                PRIMARY, BACKUP, null, null, null, Duration.ofSeconds(10), true, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PositionCandidate(
                null, "LOCATION_PRIMARY", LocationQualityStatus.GOOD, BASE, BASE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PositionCandidate(
                PRIMARY, " ", LocationQualityStatus.GOOD, BASE, BASE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PositionCandidate(
                PRIMARY, "LOCATION_PRIMARY", null, BASE, BASE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PositionCandidate(
                PRIMARY, "LOCATION_PRIMARY", LocationQualityStatus.GOOD, null, BASE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PositionCandidate(
                PRIMARY, "LOCATION_PRIMARY", LocationQualityStatus.GOOD, BASE, null))
                .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest(name = "ArbitrationState rejects recovery streak {0}")
    @ValueSource(ints = {3, Integer.MAX_VALUE})
    void rejectsArbitrationStateRecoveryStreakAboveTwoBeforeDecision(int invalidStreak) {
        assertThatThrownBy(() -> new ArbitrationState(
                PRIMARY,
                BACKUP,
                PRIMARY,
                BASE,
                BASE,
                Duration.ofSeconds(10),
                false,
                invalidStreak))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("primaryRecoveryStreak");
    }

    @ParameterizedTest(name = "LocationSourceDecision rejects recovery streak {0}")
    @ValueSource(ints = {3, Integer.MAX_VALUE})
    void rejectsDecisionRecoveryStreakAboveTwo(int invalidStreak) {
        assertThatThrownBy(() -> new LocationSourceDecision(
                false,
                false,
                PRIMARY,
                false,
                invalidStreak,
                "PRIMARY_RECOVERING"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("primaryRecoveryStreak");
    }

    private static ArbitrationState state(
            UUID backup,
            UUID active,
            Instant lastPrimaryValidAt,
            Instant lastSnapshotAt,
            boolean primaryEligible,
            int recoveryStreak,
            Duration expectedInterval) {
        return new ArbitrationState(
                PRIMARY,
                backup,
                active,
                lastPrimaryValidAt,
                lastSnapshotAt,
                expectedInterval,
                primaryEligible,
                recoveryStreak);
    }

    private static PositionCandidate primary(
            LocationQualityStatus quality, Instant locatedAt, Instant receivedAt) {
        return new PositionCandidate(
                PRIMARY, "LOCATION_PRIMARY", quality, locatedAt, receivedAt);
    }

    private static PositionCandidate backup(
            LocationQualityStatus quality, Instant locatedAt, Instant receivedAt) {
        return new PositionCandidate(
                BACKUP, "LOCATION_BACKUP", quality, locatedAt, receivedAt);
    }
}
