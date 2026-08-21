package com.idavy.drtops.domain.location;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LocationQualityEvaluatorTest {
    private static final Instant NOW = Instant.parse("2026-08-12T09:00:00Z");
    private final LocationQualityEvaluator evaluator = new LocationQualityEvaluator();

    @Test
    void classifiesReceiveDelayBoundaries() {
        assertDecision(inputAt(NOW.minusSeconds(30)), LocationQualityStatus.GOOD, Set.of(), true);
        assertDecision(inputAt(NOW.minusSeconds(30).minusMillis(1)), LocationQualityStatus.WARNING,
                Set.of(LocationQualityReason.RECEIVE_DELAY), true);
        assertDecision(inputAt(NOW.minusSeconds(31)), LocationQualityStatus.WARNING,
                Set.of(LocationQualityReason.RECEIVE_DELAY), true);
        assertDecision(inputAt(NOW.minusSeconds(120)), LocationQualityStatus.WARNING,
                Set.of(LocationQualityReason.RECEIVE_DELAY), true);
        assertDecision(inputAt(NOW.minusSeconds(120).minusMillis(1)), LocationQualityStatus.QUARANTINED,
                Set.of(LocationQualityReason.RECEIVE_DELAY_EXCEEDED), false);
        assertDecision(inputAt(NOW.minusSeconds(121)), LocationQualityStatus.QUARANTINED,
                Set.of(LocationQualityReason.RECEIVE_DELAY_EXCEEDED), false);
    }

    @Test
    void classifiesFutureTerminalClockBoundaries() {
        assertDecision(input(NOW.plusMillis(1), NOW, null, null, null, 0x02, new BigDecimal("50"), true),
                LocationQualityStatus.WARNING, Set.of(LocationQualityReason.TERMINAL_TIME_AHEAD), true);
        assertDecision(input(NOW.plusSeconds(120), NOW, null, null, null, 0x02, new BigDecimal("50"), true),
                LocationQualityStatus.WARNING, Set.of(LocationQualityReason.TERMINAL_TIME_AHEAD), true);
        assertDecision(input(NOW.plusSeconds(120).plusMillis(1), NOW, null, null, null, 0x02, new BigDecimal("50"), true),
                LocationQualityStatus.QUARANTINED, Set.of(LocationQualityReason.TERMINAL_TIME_AHEAD_EXCEEDED), false);
        assertDecision(input(NOW.plusSeconds(121), NOW, null, null, null, 0x02, new BigDecimal("50"), true),
                LocationQualityStatus.QUARANTINED, Set.of(LocationQualityReason.TERMINAL_TIME_AHEAD_EXCEEDED), false);
    }

    @Test
    void preservesHistoryForOutOfOrderButNeverAppliesSnapshot() {
        assertDecision(input(NOW, NOW, NOW.plusSeconds(1), null, null, 0x02, new BigDecimal("50"), true),
                LocationQualityStatus.WARNING, Set.of(LocationQualityReason.OUT_OF_ORDER), false);
    }

    @Test
    void rejectsInvalidCoordinatesBeforeAnyOtherDecision() {
        assertDecision(input(NOW, NOW, null, BigDecimal.ZERO, new BigDecimal("35.21"), 0x02, new BigDecimal("50"), true),
                LocationQualityStatus.REJECTED, Set.of(LocationQualityReason.INVALID_COORDINATE), false);
        assertDecision(input(NOW, NOW, null, new BigDecimal("181"), new BigDecimal("35.21"), 0x02, new BigDecimal("50"), true),
                LocationQualityStatus.REJECTED, Set.of(LocationQualityReason.INVALID_COORDINATE), false);
    }

    @Test
    void quarantinesInvalidPositionAndSpeedAbove140While120And140AreWarning() {
        assertDecision(input(NOW, NOW, null, null, null, 0, new BigDecimal("50"), true),
                LocationQualityStatus.QUARANTINED, Set.of(LocationQualityReason.POSITION_INVALID), false);
        assertDecision(input(NOW, NOW, null, null, null, 0x02, new BigDecimal("120"), true),
                LocationQualityStatus.WARNING, Set.of(LocationQualityReason.SPEED_WARNING), true);
        assertDecision(input(NOW, NOW, null, null, null, 0x02, new BigDecimal("140"), true),
                LocationQualityStatus.WARNING, Set.of(LocationQualityReason.SPEED_WARNING), true);
        assertDecision(input(NOW, NOW, null, null, null, 0x02, new BigDecimal("140.01"), true),
                LocationQualityStatus.QUARANTINED, Set.of(LocationQualityReason.SPEED_EXCEEDED), false);
    }

    @Test
    void keepsAllReasonsAndUsesHighestSeverityIncludingOutsideAreaMissingOptionalAndThirdQuarantine() {
        assertDecision(new LocationQualityEvaluator.Input(new BigDecimal("105.2421"), new BigDecimal("35.2103"),
                        NOW.minusSeconds(121), NOW, NOW, null, 0, new BigDecimal("141"), null, false, null, 3),
                LocationQualityStatus.QUARANTINED, Set.of(
                        LocationQualityReason.RECEIVE_DELAY_EXCEEDED, LocationQualityReason.POSITION_INVALID,
                        LocationQualityReason.SPEED_EXCEEDED, LocationQualityReason.OUTSIDE_SERVICE_AREA,
                        LocationQualityReason.OPTIONAL_FIELDS_MISSING, LocationQualityReason.CONSECUTIVE_QUARANTINE), false);
    }

    @Test
    void quarantinesOnlyImpliedSpeedStrictlyAbove180Kph() {
        assertDecision(input(NOW, NOW, null, null, null, 0x02, new BigDecimal("50"), true, 180d),
                LocationQualityStatus.GOOD, Set.of(), true);
        assertDecision(input(NOW, NOW, null, null, null, 0x02, new BigDecimal("50"), true, 180.01d),
                LocationQualityStatus.QUARANTINED, Set.of(LocationQualityReason.IMPLIED_SPEED_EXCEEDED), false);
    }

    private LocationQualityEvaluator.Input inputAt(Instant locatedAt) {
        return input(locatedAt, NOW, null, null, null, 0x02, new BigDecimal("50"), true);
    }

    private LocationQualityEvaluator.Input input(Instant locatedAt, Instant receivedAt, Instant latestTrustedAt,
            BigDecimal longitude, BigDecimal latitude, long statusBits, BigDecimal speed, boolean inside) {
        return input(locatedAt, receivedAt, latestTrustedAt, longitude, latitude, statusBits, speed, inside, null);
    }

    private LocationQualityEvaluator.Input input(Instant locatedAt, Instant receivedAt, Instant latestTrustedAt,
            BigDecimal longitude, BigDecimal latitude, long statusBits, BigDecimal speed, boolean inside,
            Double impliedSpeedKph) {
        return new LocationQualityEvaluator.Input(
                longitude == null ? new BigDecimal("105.2421") : longitude,
                latitude == null ? new BigDecimal("35.2103") : latitude,
                locatedAt, receivedAt, NOW, latestTrustedAt, statusBits, speed, 8, inside, impliedSpeedKph, 0);
    }

    private void assertDecision(LocationQualityEvaluator.Input input, LocationQualityStatus status,
            Set<LocationQualityReason> reasons, boolean applySnapshot) {
        LocationQualityDecision decision = evaluator.evaluate(input);
        assertThat(decision.status()).isEqualTo(status);
        assertThat(decision.reasons()).containsExactlyInAnyOrderElementsOf(reasons);
        assertThat(decision.persistEvent()).isEqualTo(status != LocationQualityStatus.REJECTED);
        assertThat(decision.applySnapshot()).isEqualTo(applySnapshot);
    }
}
