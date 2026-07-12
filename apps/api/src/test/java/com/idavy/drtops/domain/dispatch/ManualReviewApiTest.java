package com.idavy.drtops.domain.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idavy.drtops.auth.JwtTokenService;
import com.idavy.drtops.auth.RoleCode;
import com.idavy.drtops.auth.UserAccount;
import com.idavy.drtops.auth.UserAccountRepository;
import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.domain.fleet.Driver;
import com.idavy.drtops.domain.fleet.DriverRepository;
import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import com.idavy.drtops.domain.order.OrderStatus;
import com.idavy.drtops.domain.order.RideOrder;
import com.idavy.drtops.domain.order.RideOrderRepository;
import com.idavy.drtops.domain.task.TaskStop;
import com.idavy.drtops.domain.task.TaskStatus;
import com.idavy.drtops.domain.task.VehicleTask;
import com.idavy.drtops.domain.task.VehicleTaskRepository;
import com.idavy.drtops.integration.algorithm.DispatchEvaluateResponse;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:manual_review_api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class ManualReviewApiTest {

    private static final UUID VEHICLE_ID = UUID.fromString("33333333-3333-3333-3333-333333333331");
    private static final UUID DRIVER_ID = UUID.fromString("44444444-4444-4444-4444-444444444441");
    private static final UUID BOARDING_STOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555551");
    private static final UUID ALIGHTING_STOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555552");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    RideOrderRepository rideOrderRepository;

    @Autowired
    DispatchDecisionRepository dispatchDecisionRepository;

    @Autowired
    VehicleRepository vehicleRepository;

    @Autowired
    DriverRepository driverRepository;

    @Autowired
    VehicleTaskRepository vehicleTaskRepository;

    @Autowired
    AuditLogRepository auditLogRepository;

    @Autowired
    UserAccountRepository userAccountRepository;

    @Autowired
    JwtTokenService jwtTokenService;

    private String dispatcherToken;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        dispatchDecisionRepository.deleteAll();
        vehicleTaskRepository.deleteAll();
        rideOrderRepository.deleteAll();
        vehicleRepository.deleteAll();
        driverRepository.deleteAll();
        userAccountRepository.deleteAll();

        UserAccount dispatcher = UserAccount.create("dispatcher01", "dispatcher01", "not-used-in-manual-review-test");
        dispatcher.assignRoles(Set.of(RoleCode.DISPATCHER));
        dispatcherToken = jwtTokenService.issue(userAccountRepository.save(dispatcher)).value();

        vehicleRepository.save(Vehicle.create(
                VEHICLE_ID,
                "DRT-301",
                "Microbus",
                8,
                "IDLE",
                "POINT(120.1550000 30.2741000)",
                "演示车队",
                true));
        driverRepository.save(Driver.create(
                DRIVER_ID,
                "王师傅",
                "13900003001",
                "QUALIFIED",
                OffsetDateTime.parse("2026-07-08T08:00:00+08:00"),
                OffsetDateTime.parse("2026-07-08T18:00:00+08:00"),
                "AVAILABLE",
                "演示车队"));
    }

    @Test
    void approveManualReviewConfirmsOrderAndCreatesTask() throws Exception {
        UUID decisionId = createManualReviewDecision();

        mockMvc.perform(post("/api/dispatch-decisions/" + decisionId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken))
                .andExpect(status().isOk());

        RideOrder order = rideOrderRepository.findAll().getFirst();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(vehicleTaskRepository.findAll()).hasSize(1);
        assertThat(auditLogRepository.findByEntityId(order.getId()))
                .anyMatch(log -> log.getAction().equals("MANUAL_REVIEW_APPROVED"));
    }

    @Test
    void approveManualReviewCanInsertOrderIntoExistingTask() throws Exception {
        UUID existingTaskId = createInProgressTaskWithOneOrder();
        UUID decisionId = createManualReviewDecision(existingTaskId);

        mockMvc.perform(post("/api/dispatch-decisions/" + decisionId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken))
                .andExpect(status().isOk());

        RideOrder insertedOrder = rideOrderRepository.findAll().stream()
                .filter(order -> order.getStatus() == OrderStatus.CONFIRMED)
                .findFirst()
                .orElseThrow();
        VehicleTask task = vehicleTaskRepository.findWithStopsById(existingTaskId).orElseThrow();
        assertThat(vehicleTaskRepository.findAll()).hasSize(1);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(task.getStops()).hasSize(4);
        assertThat(task.getStops())
                .filteredOn(stop -> insertedOrder.getId().equals(stop.getRideOrderId()))
                .extracting(TaskStop::getStopType)
                .containsExactly("BOARDING", "ALIGHTING");
        assertThat(auditLogRepository.findByEntityId(insertedOrder.getId()))
                .anyMatch(log -> log.getAction().equals("MANUAL_REVIEW_APPROVED"));
    }

    @Test
    void approveManualReviewRejectsInsertWhenExistingTaskHasNoSeats() throws Exception {
        vehicleRepository.deleteAll();
        vehicleRepository.save(Vehicle.create(
                VEHICLE_ID,
                "DRT-201",
                "Microbus",
                1,
                "IDLE",
                "POINT(120.1550000 30.2741000)",
                "演示车队",
                true));
        UUID existingTaskId = createInProgressTaskWithOneOrder();
        UUID decisionId = createManualReviewDecision(existingTaskId);

        mockMvc.perform(post("/api/dispatch-decisions/" + decisionId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken))
                .andExpect(status().isConflict());

        VehicleTask task = vehicleTaskRepository.findWithStopsById(existingTaskId).orElseThrow();
        assertThat(task.getStops()).hasSize(2);
    }

    @Test
    void rejectManualReviewMarksOrderUnserviceable() throws Exception {
        UUID decisionId = createManualReviewDecision();

        mockMvc.perform(post("/api/dispatch-decisions/" + decisionId + "/reject")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"调度员拒绝\"}"))
                .andExpect(status().isOk());

        RideOrder order = rideOrderRepository.findAll().getFirst();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.UNSERVICEABLE);
        assertThat(vehicleTaskRepository.findAll()).isEmpty();
        assertThat(auditLogRepository.findByEntityId(order.getId()))
                .anyMatch(log -> log.getAction().equals("MANUAL_REVIEW_REJECTED"));
    }

    private UUID createManualReviewDecision() {
        return createManualReviewDecision(null);
    }

    private UUID createManualReviewDecision(UUID bestTaskId) {
        RideOrder order = RideOrder.pendingDispatch(new RideOrder.CreateOrderCommand(
                "张三",
                "13800000000",
                1,
                "IMMEDIATE",
                new BigDecimal("120.1550000"),
                new BigDecimal("30.2741000"),
                new BigDecimal("120.1688000"),
                new BigDecimal("30.2799000"),
                BOARDING_STOP_ID,
                ALIGHTING_STOP_ID,
                OffsetDateTime.parse("2026-07-08T02:30:00Z")));
        order.markPendingManualReview("MANUAL_REVIEW_THRESHOLD_REACHED");
        RideOrder savedOrder = rideOrderRepository.save(order);

        DispatchEvaluateResponse response = new DispatchEvaluateResponse(
                DispatchDecisionType.MANUAL_REVIEW,
                new DispatchEvaluateResponse.BestPlan(
                        bestTaskId,
                        VEHICLE_ID,
                        new BigDecimal("72.50"),
                        7,
                        4,
                        "SAME_DIRECTION",
                        new BigDecimal("0.67")),
                1,
                0,
                List.of(),
                Map.of("reason", "MANUAL_REVIEW_THRESHOLD_REACHED"));
        DispatchDecision decision = DispatchDecision.fromAlgorithm(
                savedOrder.getId(),
                response,
                bestTaskId,
                "[]",
                "{\"reason\":\"MANUAL_REVIEW_THRESHOLD_REACHED\"}",
                "0.1.0",
                "SYSTEM",
                "dispatch-orchestrator");
        return dispatchDecisionRepository.save(decision).getId();
    }

    private UUID createInProgressTaskWithOneOrder() {
        RideOrder order = RideOrder.pendingDispatch(new RideOrder.CreateOrderCommand(
                "李四",
                "13800000001",
                1,
                "IMMEDIATE",
                new BigDecimal("120.1550000"),
                new BigDecimal("30.2741000"),
                new BigDecimal("120.1688000"),
                new BigDecimal("30.2799000"),
                BOARDING_STOP_ID,
                ALIGHTING_STOP_ID,
                OffsetDateTime.parse("2026-07-08T02:20:00Z")));
        order.confirm(new RideOrder.OrderPromise(
                OffsetDateTime.parse("2026-07-08T02:26:00Z"),
                OffsetDateTime.parse("2026-07-08T02:40:00Z")));
        order.startExecution();
        RideOrder savedOrder = rideOrderRepository.save(order);

        VehicleTask task = VehicleTask.pendingDeparture(
                VEHICLE_ID,
                DRIVER_ID,
                OffsetDateTime.parse("2026-07-08T02:26:00Z"),
                "ALGORITHM");
        task.addStop(TaskStop.planned(
                BOARDING_STOP_ID,
                savedOrder.getId(),
                1,
                "BOARDING",
                OffsetDateTime.parse("2026-07-08T02:26:00Z")));
        task.addStop(TaskStop.planned(
                ALIGHTING_STOP_ID,
                savedOrder.getId(),
                2,
                "ALIGHTING",
                OffsetDateTime.parse("2026-07-08T02:40:00Z")));
        task.dispatch();
        task.startExecution();
        return vehicleTaskRepository.save(task).getId();
    }
}
