package com.idavy.drtops.domain.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.idavy.drtops.domain.dispatch.TaskInsertionPlan;
import com.idavy.drtops.domain.order.RideOrder;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskStopInsertionPolicyTest {

    private static final UUID VEHICLE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DRIVER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID FIRST_ORDER_ID = UUID.fromString("33333333-3333-3333-3333-333333333331");
    private static final UUID SECOND_ORDER_ID = UUID.fromString("33333333-3333-3333-3333-333333333332");
    private static final UUID THIRD_ORDER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
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
    void appendsNewBoardingToEndOfExistingSameStopGroup() {
        VehicleTask task = taskWithFirstOrder();
        task.insertStop(1, TaskStop.planned(
                HIGH_SPEED_RAIL_STOP_ID,
                THIRD_ORDER_ID,
                2,
                "BOARDING",
                BOARDING_AT.minusMinutes(2)));

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
                        tuple(THIRD_ORDER_ID, "BOARDING", 2),
                        tuple(SECOND_ORDER_ID, "BOARDING", 3),
                        tuple(FIRST_ORDER_ID, "ALIGHTING", 4),
                        tuple(SECOND_ORDER_ID, "ALIGHTING", 5));
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
    void normalizesExistingSequenceGapsWhenAppendingDifferentStop() {
        VehicleTask task = taskWithFirstOrderSequenceGap();

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

    @Test
    void appliesPlannedOrderWithoutChangingCompletedHistory() {
        VehicleTask task = taskWithFirstOrder();
        TaskStop completedBoarding = task.getStops().getFirst();
        OffsetDateTime completedPlannedAt = completedBoarding.getPlannedArrivalAt();
        completedBoarding.arriveAt(BOARDING_AT.minusMinutes(3));
        completedBoarding.board();
        TaskStop existingAlighting = task.getStops().get(1);
        OffsetDateTime replannedAlightingAt = BOARDING_AT.plusMinutes(12);
        RideOrder newOrder = order(SECOND_ORDER_ID);
        TaskInsertionPlan plan = new TaskInsertionPlan(
                true,
                null,
                List.of(
                        new TaskInsertionPlan.PlannedTaskStop(
                                OTHER_BOARDING_STOP_ID, SECOND_ORDER_ID, "BOARDING", BOARDING_AT, null),
                        new TaskInsertionPlan.PlannedTaskStop(
                                ANCHUAN_STOP_ID, FIRST_ORDER_ID, "ALIGHTING", replannedAlightingAt,
                                existingAlighting.getId()),
                        new TaskInsertionPlan.PlannedTaskStop(
                                LONGYANG_STOP_ID, SECOND_ORDER_ID, "ALIGHTING", ALIGHTING_AT, null)),
                0,
                2,
                0,
                2,
                2,
                new BigDecimal("0.25"),
                600,
                720,
                86,
                false,
                null);

        policy.applyPlan(task, newOrder, plan);

        assertThat(task.getStops())
                .extracting(TaskStop::getRideOrderId, TaskStop::getStopType, TaskStop::getSequenceNumber)
                .containsExactly(
                        tuple(FIRST_ORDER_ID, "BOARDING", 1),
                        tuple(SECOND_ORDER_ID, "BOARDING", 2),
                        tuple(FIRST_ORDER_ID, "ALIGHTING", 3),
                        tuple(SECOND_ORDER_ID, "ALIGHTING", 4));
        assertThat(task.getStops().getFirst()).isSameAs(completedBoarding);
        assertThat(task.getStops().getFirst().getPlannedArrivalAt()).isEqualTo(completedPlannedAt);
        assertThat(task.getStops().get(2)).isSameAs(existingAlighting);
        assertThat(task.getStops().get(2).getPlannedArrivalAt()).isEqualTo(replannedAlightingAt);
    }

    @Test
    void rejectsInvalidPlanBeforeReschedulingAnyExistingStop() {
        VehicleTask task = taskWithFirstOrder();
        TaskStop existingBoarding = task.getStops().getFirst();
        TaskStop existingAlighting = task.getStops().get(1);
        OffsetDateTime originalBoardingAt = existingBoarding.getPlannedArrivalAt();
        RideOrder newOrder = order(SECOND_ORDER_ID);
        TaskInsertionPlan invalidPlan = new TaskInsertionPlan(
                true,
                null,
                List.of(
                        new TaskInsertionPlan.PlannedTaskStop(
                                OTHER_BOARDING_STOP_ID, SECOND_ORDER_ID, "BOARDING", BOARDING_AT, null),
                        new TaskInsertionPlan.PlannedTaskStop(
                                HIGH_SPEED_RAIL_STOP_ID, FIRST_ORDER_ID, "BOARDING", BOARDING_AT.plusMinutes(1),
                                existingBoarding.getId()),
                        new TaskInsertionPlan.PlannedTaskStop(
                                LONGYANG_STOP_ID, FIRST_ORDER_ID, "ALIGHTING", BOARDING_AT.plusMinutes(12),
                                existingAlighting.getId()),
                        new TaskInsertionPlan.PlannedTaskStop(
                                LONGYANG_STOP_ID, SECOND_ORDER_ID, "ALIGHTING", ALIGHTING_AT, null)),
                0, 3, 0, 2, 2, new BigDecimal("0.25"),
                600, 720, 86, false, null);

        assertThatThrownBy(() -> policy.applyPlan(task, newOrder, invalidPlan))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("insertion plan changed an existing task stop identity");
        assertThat(existingBoarding.getPlannedArrivalAt()).isEqualTo(originalBoardingAt);
    }

    private static RideOrder order(UUID id) {
        RideOrder order = RideOrder.pendingDispatch(new RideOrder.CreateOrderCommand(
                "测试乘客", "13800000000", 1, "IMMEDIATE",
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN,
                OTHER_BOARDING_STOP_ID, LONGYANG_STOP_ID, BOARDING_AT));
        try {
            var field = RideOrder.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(order, id);
            return order;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
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

    private static VehicleTask taskWithFirstOrderSequenceGap() {
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
                4,
                "ALIGHTING",
                BOARDING_AT.plusMinutes(10)));
        return task;
    }
}
