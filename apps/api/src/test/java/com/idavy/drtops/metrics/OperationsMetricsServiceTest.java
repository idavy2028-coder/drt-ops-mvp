package com.idavy.drtops.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.idavy.drtops.domain.dispatch.DispatchDecision;
import com.idavy.drtops.domain.dispatch.DispatchDecisionRepository;
import com.idavy.drtops.domain.dispatch.DispatchDecisionType;
import com.idavy.drtops.domain.order.RideOrder;
import com.idavy.drtops.domain.order.RideOrderRepository;
import com.idavy.drtops.domain.task.VehicleTask;
import com.idavy.drtops.domain.task.VehicleTaskRepository;
import com.idavy.drtops.integration.algorithm.DispatchEvaluateResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:operations_metrics;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class OperationsMetricsServiceTest {

    private static final UUID VEHICLE_ID = UUID.fromString("33333333-3333-3333-3333-333333333331");
    private static final UUID OTHER_VEHICLE_ID = UUID.fromString("33333333-3333-3333-3333-333333333332");
    private static final UUID DRIVER_ID = UUID.fromString("44444444-4444-4444-4444-444444444441");
    private static final UUID BOARDING_STOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555551");
    private static final UUID ALIGHTING_STOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555552");

    @Autowired
    OperationsMetricsService metricsService;

    @Autowired
    RideOrderRepository rideOrderRepository;

    @Autowired
    DispatchDecisionRepository dispatchDecisionRepository;

    @Autowired
    VehicleTaskRepository vehicleTaskRepository;

    @BeforeEach
    void setUp() {
        dispatchDecisionRepository.deleteAll();
        vehicleTaskRepository.deleteAll();
        rideOrderRepository.deleteAll();
    }

    @Test
    void computesOperationsSummaryFromOrdersTasksAndDecisions() {
        RideOrder autoOrder = saveCompletedAutoDispatchOrder();
        RideOrder manualOrder = saveManualReviewOrder();
        saveExceptionClosedOrder();
        dispatchDecisionRepository.save(dispatchDecision(
                autoOrder.getId(),
                DispatchDecisionType.AUTO_DISPATCH,
                VEHICLE_ID,
                6,
                4));
        dispatchDecisionRepository.save(dispatchDecision(
                manualOrder.getId(),
                DispatchDecisionType.MANUAL_REVIEW,
                OTHER_VEHICLE_ID,
                8,
                5));
        saveCompletedTask(VEHICLE_ID, OffsetDateTime.parse("2026-07-08T02:36:00Z"));
        saveInProgressTask(OTHER_VEHICLE_ID, OffsetDateTime.parse("2026-07-08T02:40:00Z"));
        RideOrder nextDayOrder = saveNextDayAutoDispatchOrder();
        dispatchDecisionRepository.save(dispatchDecision(
                nextDayOrder.getId(),
                DispatchDecisionType.AUTO_DISPATCH,
                VEHICLE_ID,
                3,
                2));
        saveCompletedTask(VEHICLE_ID, OffsetDateTime.parse("2026-07-09T02:36:00Z"));

        OperationsSummary summary = metricsService.calculateSummary(LocalDate.parse("2026-07-08"));

        assertThat(summary.orderCount()).isEqualTo(3L);
        assertThat(summary.confirmationRate()).isEqualByComparingTo("0.6667");
        assertThat(summary.autoDispatchRate()).isEqualByComparingTo("0.3333");
        assertThat(summary.manualReviewRate()).isEqualByComparingTo("0.3333");
        assertThat(summary.averageWaitMinutes()).isEqualByComparingTo("7.00");
        assertThat(summary.averageDetourMinutes()).isEqualByComparingTo("4.50");
        assertThat(summary.taskCompletionRate()).isEqualByComparingTo("0.5000");
        assertThat(summary.exceptionCloseRate()).isEqualByComparingTo("0.3333");
    }

    @Test
    void assignsUtcBoundaryOrdersAndTasksToShanghaiOperatingDay() {
        OffsetDateTime utcBoundary = OffsetDateTime.parse("2026-07-12T23:17:00Z");
        RideOrder order = newOrder("13800009005", utcBoundary);
        order.confirm(new RideOrder.OrderPromise(utcBoundary.plusMinutes(3), utcBoundary.plusMinutes(15)));
        order.startExecution();
        order.complete();
        rideOrderRepository.save(order);
        saveCompletedTask(VEHICLE_ID, utcBoundary);

        OperationsSummary shanghaiDay = metricsService.calculateSummary(LocalDate.parse("2026-07-13"));
        OperationsSummary utcDay = metricsService.calculateSummary(LocalDate.parse("2026-07-12"));

        assertThat(shanghaiDay.orderCount()).isEqualTo(1L);
        assertThat(shanghaiDay.taskCompletionRate()).isEqualByComparingTo("1.0000");
        assertThat(utcDay.orderCount()).isZero();
        assertThat(utcDay.taskCompletionRate()).isEqualByComparingTo("0.0000");
    }

    private RideOrder saveCompletedAutoDispatchOrder() {
        RideOrder order = newOrder("13800009001");
        order.confirm(new RideOrder.OrderPromise(
                OffsetDateTime.parse("2026-07-08T02:36:00Z"),
                OffsetDateTime.parse("2026-07-08T02:50:00Z")));
        order.startExecution();
        order.complete();
        return rideOrderRepository.save(order);
    }

    private RideOrder saveManualReviewOrder() {
        RideOrder order = newOrder("13800009002");
        order.markPendingManualReview("MANUAL_REVIEW_THRESHOLD_REACHED");
        return rideOrderRepository.save(order);
    }

    private RideOrder saveExceptionClosedOrder() {
        RideOrder order = newOrder("13800009003");
        order.closeException("NO_SHOW");
        return rideOrderRepository.save(order);
    }

    private RideOrder saveNextDayAutoDispatchOrder() {
        RideOrder order = newOrder("13800009004", OffsetDateTime.parse("2026-07-09T02:30:00Z"));
        order.confirm(new RideOrder.OrderPromise(
                OffsetDateTime.parse("2026-07-09T02:33:00Z"),
                OffsetDateTime.parse("2026-07-09T02:45:00Z")));
        order.startExecution();
        order.complete();
        return rideOrderRepository.save(order);
    }

    private RideOrder newOrder(String phone) {
        return newOrder(phone, OffsetDateTime.parse("2026-07-08T02:30:00Z"));
    }

    private RideOrder newOrder(String phone, OffsetDateTime requestedDepartureAt) {
        return RideOrder.pendingDispatch(new RideOrder.CreateOrderCommand(
                "Passenger-" + phone.substring(phone.length() - 2),
                phone,
                1,
                "IMMEDIATE",
                new BigDecimal("120.1550000"),
                new BigDecimal("30.2741000"),
                new BigDecimal("120.1688000"),
                new BigDecimal("30.2799000"),
                BOARDING_STOP_ID,
                ALIGHTING_STOP_ID,
                requestedDepartureAt));
    }

    private DispatchDecision dispatchDecision(
            UUID orderId,
            DispatchDecisionType decisionType,
            UUID vehicleId,
            int estimatedWaitMinutes,
            int estimatedDetourMinutes) {
        DispatchEvaluateResponse response = new DispatchEvaluateResponse(
                decisionType,
                new DispatchEvaluateResponse.BestPlan(
                        null,
                        vehicleId,
                        new BigDecimal("80.00"),
                        estimatedWaitMinutes,
                        estimatedDetourMinutes,
                        "SAME_DIRECTION",
                        new BigDecimal("0.75")),
                2,
                0,
                List.of(),
                Map.of("reason", decisionType.name()));
        return DispatchDecision.fromAlgorithm(
                orderId,
                response,
                null,
                "[]",
                "{\"reason\":\"" + decisionType.name() + "\"}",
                "0.1.0",
                "SYSTEM",
                "metrics-test");
    }

    private void saveCompletedTask(UUID vehicleId, OffsetDateTime plannedStartAt) {
        VehicleTask task = VehicleTask.pendingDeparture(
                vehicleId,
                DRIVER_ID,
                plannedStartAt,
                "ALGORITHM");
        task.startExecution();
        task.complete();
        vehicleTaskRepository.save(task);
    }

    private void saveInProgressTask(UUID vehicleId, OffsetDateTime plannedStartAt) {
        VehicleTask task = VehicleTask.pendingDeparture(
                vehicleId,
                DRIVER_ID,
                plannedStartAt,
                "MANUAL_REVIEW");
        task.startExecution();
        vehicleTaskRepository.save(task);
    }
}
