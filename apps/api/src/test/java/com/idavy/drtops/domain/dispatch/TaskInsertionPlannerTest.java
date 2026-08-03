package com.idavy.drtops.domain.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.location.LocationSource;
import com.idavy.drtops.domain.map.Coordinate;
import com.idavy.drtops.domain.order.RideOrder;
import com.idavy.drtops.domain.task.TaskStop;
import com.idavy.drtops.domain.task.VehicleTask;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskInsertionPlannerTest {

    private static final OffsetDateTime DEPARTURE = OffsetDateTime.parse("2026-08-03T08:00:00+08:00");
    private static final UUID EXISTING_ORDER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID NEW_ORDER_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID SECOND_EXISTING_ORDER_ID = UUID.fromString("10000000-0000-0000-0000-000000000003");
    private static final UUID EXISTING_PICKUP = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID NEW_PICKUP = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID EXISTING_DROPOFF = UUID.fromString("20000000-0000-0000-0000-000000000003");
    private static final UUID NEW_DROPOFF = UUID.fromString("20000000-0000-0000-0000-000000000004");

    @Test
    void choosesLegalInsertionWithLowestMaximumPassengerDetour() {
        RideOrder existingOrder = order(EXISTING_ORDER_ID, 1, EXISTING_PICKUP, EXISTING_DROPOFF);
        RideOrder newOrder = order(NEW_ORDER_ID, 1, NEW_PICKUP, NEW_DROPOFF);
        Vehicle vehicle = vehicleAt(0);
        VehicleTask task = VehicleTask.pendingDeparture(
                vehicle.getId(), UUID.randomUUID(), DEPARTURE, "ALGORITHM");
        task.addStop(TaskStop.planned(EXISTING_PICKUP, existingOrder.getId(), 1, "BOARDING", DEPARTURE.plusMinutes(1)));
        task.addStop(TaskStop.planned(EXISTING_DROPOFF, existingOrder.getId(), 2, "ALIGHTING", DEPARTURE.plusMinutes(3)));
        task.dispatch();

        TaskInsertionPlanner planner = new TaskInsertionPlanner(new LinearTravelEstimateService());
        TaskInsertionPlan plan = planner.plan(
                newOrder,
                vehicle,
                task,
                rules(5, 8),
                Map.of(
                        EXISTING_PICKUP, coordinate(1),
                        NEW_PICKUP, coordinate(2),
                        EXISTING_DROPOFF, coordinate(3),
                        NEW_DROPOFF, coordinate(4)),
                Map.of(EXISTING_ORDER_ID, 1, NEW_ORDER_ID, 1));

        assertThat(plan.feasible()).isTrue();
        assertThat(plan.boardingIndex()).isEqualTo(1);
        assertThat(plan.alightingIndex()).isEqualTo(3);
        assertThat(plan.maxPassengerDetourMinutes()).isZero();
        assertThat(plan.orderedStops())
                .extracting(TaskInsertionPlan.PlannedTaskStop::virtualStopId)
                .containsExactly(EXISTING_PICKUP, NEW_PICKUP, EXISTING_DROPOFF, NEW_DROPOFF);
    }

    @Test
    void acceptsWaitAtFiveMinutesAndRejectsWaitAboveFiveMinutes() {
        TaskInsertionPlan accepted = planWithoutExistingStops(1, "5", 5, 8);
        TaskInsertionPlan rejected = planWithoutExistingStops(1, "6", 5, 8);

        assertThat(accepted.feasible()).isTrue();
        assertThat(accepted.estimatedWaitMinutes()).isEqualTo(5);
        assertThat(rejected.feasible()).isFalse();
        assertThat(rejected.rejectionReason()).isEqualTo("WAIT_TIME_EXCEEDED");
    }

    @Test
    void acceptsDetourAtEightMinutesAndRejectsDetourAboveEightMinutes() {
        TaskInsertionPlan accepted = planWithOnboardPassengers(1, 1, "-3", 8);
        TaskInsertionPlan rejected = planWithOnboardPassengers(1, 1, "-3.5", 8);

        assertThat(accepted.feasible()).isTrue();
        assertThat(accepted.maxPassengerDetourMinutes()).isEqualTo(8);
        assertThat(rejected.feasible()).isFalse();
        assertThat(rejected.rejectionReason()).isEqualTo("DETOUR_TIME_EXCEEDED");
    }

    @Test
    void includesAlreadyBoardedPassengersInPeakCapacity() {
        TaskInsertionPlan accepted = planWithOnboardPassengers(7, 1, "2", 8);
        TaskInsertionPlan rejected = planWithOnboardPassengers(7, 2, "2", 8);

        assertThat(accepted.feasible()).isTrue();
        assertThat(accepted.peakOccupiedSeats()).isEqualTo(8);
        assertThat(rejected.feasible()).isFalse();
        assertThat(rejected.rejectionReason()).isEqualTo("INSUFFICIENT_CAPACITY");
    }

    @Test
    void appendsSameStopBoardingToEndOfContiguousBoardingGroup() {
        RideOrder first = order(EXISTING_ORDER_ID, 1, EXISTING_PICKUP, EXISTING_DROPOFF);
        RideOrder second = order(SECOND_EXISTING_ORDER_ID, 1, EXISTING_PICKUP, NEW_DROPOFF);
        RideOrder inserted = order(NEW_ORDER_ID, 1, EXISTING_PICKUP, NEW_DROPOFF);
        Vehicle vehicle = vehicleAt(0);
        VehicleTask task = VehicleTask.pendingDeparture(
                vehicle.getId(), UUID.randomUUID(), DEPARTURE, "ALGORITHM");
        task.addStop(TaskStop.planned(EXISTING_PICKUP, first.getId(), 1, "BOARDING", DEPARTURE.plusMinutes(1)));
        task.addStop(TaskStop.planned(EXISTING_PICKUP, second.getId(), 2, "BOARDING", DEPARTURE.plusMinutes(1)));
        task.addStop(TaskStop.planned(EXISTING_DROPOFF, first.getId(), 3, "ALIGHTING", DEPARTURE.plusMinutes(5)));
        task.addStop(TaskStop.planned(NEW_DROPOFF, second.getId(), 4, "ALIGHTING", DEPARTURE.plusMinutes(6)));
        task.dispatch();

        TaskInsertionPlan plan = new TaskInsertionPlanner(new LinearTravelEstimateService()).plan(
                inserted,
                vehicle,
                task,
                rules(5, 8),
                Map.of(
                        EXISTING_PICKUP, coordinate("1"),
                        EXISTING_DROPOFF, coordinate("5"),
                        NEW_DROPOFF, coordinate("6")),
                Map.of(EXISTING_ORDER_ID, 1, SECOND_EXISTING_ORDER_ID, 1, NEW_ORDER_ID, 1));

        assertThat(plan.feasible()).isTrue();
        assertThat(plan.boardingIndex()).isEqualTo(2);
        assertThat(plan.orderedStops().subList(0, 3))
                .extracting(TaskInsertionPlan.PlannedTaskStop::rideOrderId)
                .containsExactly(EXISTING_ORDER_ID, SECOND_EXISTING_ORDER_ID, NEW_ORDER_ID);
    }

    private static TaskInsertionPlan planWithoutExistingStops(
            int newPassengers,
            String pickupPosition,
            int maxWait,
            int maxDetour) {
        RideOrder newOrder = order(NEW_ORDER_ID, newPassengers, NEW_PICKUP, NEW_DROPOFF);
        Vehicle vehicle = vehicleAt(0);
        VehicleTask task = VehicleTask.pendingDeparture(
                vehicle.getId(), UUID.randomUUID(), DEPARTURE, "ALGORITHM");
        task.dispatch();
        return new TaskInsertionPlanner(new LinearTravelEstimateService()).plan(
                newOrder,
                vehicle,
                task,
                rules(maxWait, maxDetour),
                Map.of(NEW_PICKUP, coordinate(pickupPosition), NEW_DROPOFF, coordinate("7")),
                Map.of(NEW_ORDER_ID, newPassengers));
    }

    private static TaskInsertionPlan planWithOnboardPassengers(
            int existingPassengers,
            int newPassengers,
            String newDropoffPosition,
            int maxDetour) {
        RideOrder existingOrder = order(
                EXISTING_ORDER_ID, existingPassengers, EXISTING_PICKUP, EXISTING_DROPOFF);
        RideOrder newOrder = order(NEW_ORDER_ID, newPassengers, NEW_PICKUP, NEW_DROPOFF);
        Vehicle vehicle = vehicleAt(0);
        VehicleTask task = VehicleTask.pendingDeparture(
                vehicle.getId(), UUID.randomUUID(), DEPARTURE, "ALGORITHM");
        TaskStop completedBoarding = TaskStop.planned(
                EXISTING_PICKUP, existingOrder.getId(), 1, "BOARDING", DEPARTURE);
        completedBoarding.arriveAt(DEPARTURE);
        completedBoarding.board();
        task.addStop(completedBoarding);
        task.addStop(TaskStop.planned(
                EXISTING_DROPOFF, existingOrder.getId(), 2, "ALIGHTING", DEPARTURE.plusMinutes(10)));
        task.dispatch();
        task.startExecution();

        return new TaskInsertionPlanner(new LinearTravelEstimateService()).plan(
                newOrder,
                vehicle,
                task,
                rules(5, maxDetour),
                Map.of(
                        EXISTING_PICKUP, coordinate("0"),
                        NEW_PICKUP, coordinate("1"),
                        EXISTING_DROPOFF, coordinate("10"),
                        NEW_DROPOFF, coordinate(newDropoffPosition)),
                Map.of(EXISTING_ORDER_ID, existingPassengers, NEW_ORDER_ID, newPassengers));
    }

    private static RideOrder order(
            UUID expectedId,
            int passengers,
            UUID pickup,
            UUID dropoff) {
        RideOrder order = RideOrder.pendingDispatch(new RideOrder.CreateOrderCommand(
                "测试乘客",
                "13800000000",
                passengers,
                "IMMEDIATE",
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.TEN,
                BigDecimal.TEN,
                pickup,
                dropoff,
                DEPARTURE));
        setId(order, expectedId);
        return order;
    }

    private static void setId(RideOrder order, UUID id) {
        try {
            var field = RideOrder.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(order, id);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Vehicle vehicleAt(int position) {
        Vehicle vehicle = Vehicle.create(
                UUID.randomUUID(), "甘JTEST1", "测试车", 8, "DISPATCHED",
                "POINT(" + position + " 0)", "测试车队", true);
        vehicle.applyLocationSnapshot(
                "POINT(" + position + " 0)", "测试位置", LocationSource.MANUAL_DISPATCHER,
                "GCJ-02", DEPARTURE, DEPARTURE, UUID.randomUUID(), null);
        return vehicle;
    }

    private static DispatchRuleSet rules(int maxWait, int maxDetour) {
        return DispatchRuleSet.create(
                UUID.randomUUID(), "测试规则", maxWait, maxDetour, 60,
                new BigDecimal("82"), new BigDecimal("62"),
                new BigDecimal("0.35"), new BigDecimal("0.20"),
                new BigDecimal("0.30"), new BigDecimal("0.15"),
                "REALTIME_INSERTION");
    }

    private static Coordinate coordinate(int position) {
        return coordinate(Integer.toString(position));
    }

    private static Coordinate coordinate(String position) {
        return new Coordinate(position, "0");
    }

    private static final class LinearTravelEstimateService extends TravelEstimateService {

        private LinearTravelEstimateService() {
            super(null, null);
        }

        @Override
        public TravelEstimate estimateBetween(Coordinate origin, Coordinate destination) {
            BigDecimal distance = destination.longitude().subtract(origin.longitude()).abs();
            int durationSeconds = distance.multiply(BigDecimal.valueOf(60)).intValueExact();
            return new TravelEstimate(distance.multiply(BigDecimal.valueOf(1_000)).intValueExact(),
                    durationSeconds, "TEST", false, null);
        }
    }
}
