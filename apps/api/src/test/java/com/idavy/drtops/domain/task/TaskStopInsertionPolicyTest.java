package com.idavy.drtops.domain.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskStopInsertionPolicyTest {

    private static final UUID VEHICLE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DRIVER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID FIRST_ORDER_ID = UUID.fromString("33333333-3333-3333-3333-333333333331");
    private static final UUID SECOND_ORDER_ID = UUID.fromString("33333333-3333-3333-3333-333333333332");
    private static final UUID HIGH_SPEED_RAIL_STOP_ID = UUID.fromString("44444444-4444-4444-4444-444444444441");
    private static final UUID ANCHUAN_STOP_ID = UUID.fromString("44444444-4444-4444-4444-444444444442");
    private static final UUID LONGYANG_STOP_ID = UUID.fromString("44444444-4444-4444-4444-444444444443");
    private static final UUID OTHER_BOARDING_STOP_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final OffsetDateTime BOARDING_AT = OffsetDateTime.parse("2026-08-03T12:01:00+08:00");
    private static final OffsetDateTime ALIGHTING_AT = OffsetDateTime.parse("2026-08-03T12:15:00+08:00");

    private final TaskStopInsertionPolicy policy = new TaskStopInsertionPolicy();

    @Test
    void insertsNewBoardingBesideIncompleteBoardingAtSameStop() {
        VehicleTask task = taskWithFirstOrder();

        policy.insertOrderStops(
                task,
                HIGH_SPEED_RAIL_STOP_ID,
                LONGYANG_STOP_ID,
                SECOND_ORDER_ID,
                BOARDING_AT,
                ALIGHTING_AT);

        assertThat(task.getStops())
                .extracting(TaskStop::getRideOrderId, TaskStop::getStopType, TaskStop::getSequenceNumber)
                .containsExactly(
                        tuple(FIRST_ORDER_ID, "BOARDING", 1),
                        tuple(SECOND_ORDER_ID, "BOARDING", 2),
                        tuple(FIRST_ORDER_ID, "ALIGHTING", 3),
                        tuple(SECOND_ORDER_ID, "ALIGHTING", 4));
    }

    @Test
    void appendsNewOrderWhenBoardingStopDiffers() {
        VehicleTask task = taskWithFirstOrder();

        policy.insertOrderStops(
                task,
                OTHER_BOARDING_STOP_ID,
                LONGYANG_STOP_ID,
                SECOND_ORDER_ID,
                BOARDING_AT,
                ALIGHTING_AT);

        assertThat(task.getStops())
                .extracting(TaskStop::getRideOrderId, TaskStop::getStopType, TaskStop::getSequenceNumber)
                .containsExactly(
                        tuple(FIRST_ORDER_ID, "BOARDING", 1),
                        tuple(FIRST_ORDER_ID, "ALIGHTING", 2),
                        tuple(SECOND_ORDER_ID, "BOARDING", 3),
                        tuple(SECOND_ORDER_ID, "ALIGHTING", 4));
    }

    @Test
    void doesNotInsertBesideCompletedHistoricalBoardingStop() {
        VehicleTask task = taskWithFirstOrder();
        TaskStop completedBoarding = task.getStops().getFirst();
        completedBoarding.arrive();
        completedBoarding.board();

        policy.insertOrderStops(
                task,
                HIGH_SPEED_RAIL_STOP_ID,
                LONGYANG_STOP_ID,
                SECOND_ORDER_ID,
                BOARDING_AT,
                ALIGHTING_AT);

        assertThat(task.getStops())
                .extracting(TaskStop::getRideOrderId, TaskStop::getStopType, TaskStop::getSequenceNumber)
                .containsExactly(
                        tuple(FIRST_ORDER_ID, "BOARDING", 1),
                        tuple(FIRST_ORDER_ID, "ALIGHTING", 2),
                        tuple(SECOND_ORDER_ID, "BOARDING", 3),
                        tuple(SECOND_ORDER_ID, "ALIGHTING", 4));
    }

    private static VehicleTask taskWithFirstOrder() {
        VehicleTask task = VehicleTask.pendingDeparture(VEHICLE_ID, DRIVER_ID, BOARDING_AT.minusMinutes(5), "TEST");
        task.addStop(TaskStop.planned(
                HIGH_SPEED_RAIL_STOP_ID,
                FIRST_ORDER_ID,
                1,
                "BOARDING",
                BOARDING_AT.minusMinutes(4)));
        task.addStop(TaskStop.planned(
                ANCHUAN_STOP_ID,
                FIRST_ORDER_ID,
                2,
                "ALIGHTING",
                BOARDING_AT.plusMinutes(10)));
        return task;
    }
}
