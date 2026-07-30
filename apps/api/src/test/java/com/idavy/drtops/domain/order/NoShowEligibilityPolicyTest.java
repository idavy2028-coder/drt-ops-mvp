package com.idavy.drtops.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.idavy.drtops.domain.task.TaskStatus;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class NoShowEligibilityPolicyTest {

    private static final OffsetDateTime ESTIMATED_BOARDING =
            OffsetDateTime.parse("2026-07-30T12:05:00+08:00");
    private static final NoShowEligibilityPolicy POLICY = new NoShowEligibilityPolicy();

    @Test
    void rejectsConfirmedOrderBeforeTaskStarts() {
        NoShowEligibility result = POLICY.evaluate(
                OrderStatus.CONFIRMED,
                TaskStatus.DISPATCHED,
                "PLANNED",
                ESTIMATED_BOARDING,
                null,
                false,
                OffsetDateTime.parse("2026-07-30T12:10:00+08:00"));

        assertThat(result.reasonCode()).isEqualTo("NO_SHOW_ORDER_NOT_IN_PROGRESS");
    }

    @Test
    void rejectsTaskThatIsNotInProgress() {
        NoShowEligibility result = POLICY.evaluate(
                OrderStatus.IN_PROGRESS,
                TaskStatus.DISPATCHED,
                "ARRIVED",
                ESTIMATED_BOARDING,
                OffsetDateTime.parse("2026-07-30T12:05:00+08:00"),
                true,
                OffsetDateTime.parse("2026-07-30T12:10:00+08:00"));

        assertThat(result.reasonCode()).isEqualTo("NO_SHOW_TASK_NOT_IN_PROGRESS");
    }

    @Test
    void rejectsPickupThatHasNotArrived() {
        NoShowEligibility result = POLICY.evaluate(
                OrderStatus.IN_PROGRESS,
                TaskStatus.IN_PROGRESS,
                "PLANNED",
                ESTIMATED_BOARDING,
                null,
                false,
                OffsetDateTime.parse("2026-07-30T12:10:00+08:00"));

        assertThat(result.reasonCode()).isEqualTo("NO_SHOW_PICKUP_NOT_ARRIVED");
    }

    @Test
    void rejectsArrivedPickupWithoutArrivalEvent() {
        NoShowEligibility result = POLICY.evaluate(
                OrderStatus.IN_PROGRESS,
                TaskStatus.IN_PROGRESS,
                "ARRIVED",
                ESTIMATED_BOARDING,
                OffsetDateTime.parse("2026-07-30T12:05:00+08:00"),
                false,
                OffsetDateTime.parse("2026-07-30T12:10:00+08:00"));

        assertThat(result.reasonCode()).isEqualTo("NO_SHOW_PICKUP_EVENT_MISSING");
    }

    @Test
    void rejectsPassengerThatAlreadyBoarded() {
        NoShowEligibility result = POLICY.evaluate(
                OrderStatus.IN_PROGRESS,
                TaskStatus.IN_PROGRESS,
                "BOARDED",
                ESTIMATED_BOARDING,
                OffsetDateTime.parse("2026-07-30T12:05:00+08:00"),
                true,
                OffsetDateTime.parse("2026-07-30T12:10:00+08:00"));

        assertThat(result.reasonCode()).isEqualTo("NO_SHOW_PASSENGER_ALREADY_BOARDED");
    }

    @Test
    void earlyArrivalWaitsFiveMinutesAfterEstimatedBoarding() {
        NoShowEligibility result = POLICY.evaluate(
                OrderStatus.IN_PROGRESS,
                TaskStatus.IN_PROGRESS,
                "ARRIVED",
                ESTIMATED_BOARDING,
                OffsetDateTime.parse("2026-07-30T12:00:00+08:00"),
                true,
                OffsetDateTime.parse("2026-07-30T12:09:59+08:00"));

        assertThat(result.eligible()).isFalse();
        assertThat(result.eligibleAt()).isEqualTo("2026-07-30T12:10:00+08:00");
        assertThat(result.reasonCode()).isEqualTo("NO_SHOW_WAITING_PERIOD_NOT_ELAPSED");
    }

    @Test
    void lateArrivalAllowsAtExactlyFiveMinutes() {
        NoShowEligibility result = POLICY.evaluate(
                OrderStatus.IN_PROGRESS,
                TaskStatus.IN_PROGRESS,
                "ARRIVED",
                OffsetDateTime.parse("2026-07-30T12:00:00+08:00"),
                OffsetDateTime.parse("2026-07-30T12:05:00+08:00"),
                true,
                OffsetDateTime.parse("2026-07-30T12:10:00+08:00"));

        assertThat(result.eligible()).isTrue();
        assertThat(result.eligibleAt()).isEqualTo("2026-07-30T12:10:00+08:00");
        assertThat(result.waitedSeconds()).isEqualTo(300);
        assertThat(result.reasonCode()).isNull();
    }

    @Test
    void rejectsTerminalOrder() {
        NoShowEligibility result = POLICY.evaluate(
                OrderStatus.COMPLETED,
                TaskStatus.COMPLETED,
                "ALIGHTED",
                ESTIMATED_BOARDING,
                OffsetDateTime.parse("2026-07-30T12:05:00+08:00"),
                true,
                OffsetDateTime.parse("2026-07-30T12:10:00+08:00"));

        assertThat(result.reasonCode()).isEqualTo("NO_SHOW_ORDER_ALREADY_TERMINAL");
    }
}
