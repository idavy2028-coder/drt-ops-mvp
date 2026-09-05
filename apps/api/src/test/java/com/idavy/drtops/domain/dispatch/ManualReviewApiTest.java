package com.idavy.drtops.domain.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idavy.drtops.auth.JwtTokenService;
import com.idavy.drtops.auth.RoleCode;
import com.idavy.drtops.auth.UserAccount;
import com.idavy.drtops.auth.UserAccountRepository;
import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.domain.fleet.Driver;
import com.idavy.drtops.domain.fleet.DriverRepository;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import com.idavy.drtops.domain.map.Coordinate;
import com.idavy.drtops.domain.map.DistanceResult;
import com.idavy.drtops.domain.map.RoutePlanResult;
import com.idavy.drtops.domain.map.RoutePlanningProvider;
import com.idavy.drtops.domain.onboard.OnboardDeviceRoleAssignment;
import com.idavy.drtops.domain.onboard.OnboardDeviceRoleAssignment.Role;
import com.idavy.drtops.domain.onboard.OnboardDeviceRoleAssignmentRepository;
import com.idavy.drtops.domain.onboard.OnboardSystem;
import com.idavy.drtops.domain.onboard.OnboardSystemRepository;
import com.idavy.drtops.domain.onboard.OnboardTestFixtures;
import com.idavy.drtops.domain.order.OrderStatus;
import com.idavy.drtops.domain.order.RideOrder;
import com.idavy.drtops.domain.order.RideOrderRepository;
import com.idavy.drtops.domain.task.TaskStop;
import com.idavy.drtops.domain.task.TaskStatus;
import com.idavy.drtops.domain.task.VehicleTask;
import com.idavy.drtops.domain.task.VehicleTaskRepository;
import com.idavy.drtops.domain.terminal.JtTerminal;
import com.idavy.drtops.domain.terminal.JtTerminalRepository;
import com.idavy.drtops.domain.terminal.JtTerminalSessionLeaseRepository;
import com.idavy.drtops.integration.algorithm.DispatchEvaluateResponse;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:manual_review_api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=250",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
@Import({ManualReviewApiTest.RouteTestConfiguration.class, OnboardTestFixtures.class})
class ManualReviewApiTest {

    private static final UUID DRIVER_ID = UUID.fromString("44444444-4444-4444-4444-444444444441");
    private static final UUID BOARDING_STOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555551");
    private static final UUID ALIGHTING_STOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555552");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ManualReviewService manualReviewService;

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
    DispatchRuleSetRepository ruleSetRepository;

    @Autowired
    UserAccountRepository userAccountRepository;

    @Autowired
    JwtTokenService jwtTokenService;

    @Autowired
    OnboardTestFixtures onboardFixtures;

    @Autowired
    OnboardSystemRepository onboardSystemRepository;

    @Autowired
    OnboardDeviceRoleAssignmentRepository onboardRoleRepository;

    @Autowired
    JtTerminalRepository terminalRepository;

    @Autowired
    JtTerminalSessionLeaseRepository leaseRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    private String dispatcherToken;
    private UUID dispatcherId;
    private UUID vehicleId;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        dispatchDecisionRepository.deleteAll();
        vehicleTaskRepository.deleteAll();
        rideOrderRepository.deleteAll();
        driverRepository.deleteAll();
        ruleSetRepository.deleteAll();
        userAccountRepository.deleteAll();
        onboardFixtures.clear();

        UserAccount dispatcher = UserAccount.create("dispatcher01", "dispatcher01", "not-used-in-manual-review-test");
        dispatcher.assignRoles(Set.of(RoleCode.DISPATCHER));
        dispatcher = userAccountRepository.save(dispatcher);
        dispatcherId = dispatcher.getId();
        dispatcherToken = jwtTokenService.issue(dispatcher).value();

        vehicleId = onboardFixtures.readyDispatchSystemVehicleId();
        ruleSetRepository.save(DispatchRuleSet.defaultRules(UUID.randomUUID()));
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
        assertThat(vehicleRepository.findById(vehicleId).orElseThrow().getCurrentStatus())
                .isEqualTo("DISPATCHED");
        assertThat(driverRepository.findById(DRIVER_ID).orElseThrow().getCurrentStatus())
                .isEqualTo("BUSY");
        assertThat(auditLogRepository.findByEntityId(order.getId()))
                .anyMatch(log -> log.getAction().equals("MANUAL_REVIEW_APPROVED")
                        && log.getActorType().equals("USER")
                        && log.getActorId().equals(dispatcherId.toString()));
    }

    @Test
    void approveManualReviewCanInsertOrderIntoExistingTask() throws Exception {
        UUID existingTaskId = createInProgressTaskWithOneOrder();
        UUID existingOrderId = vehicleTaskRepository.findWithStopsById(existingTaskId)
                .orElseThrow()
                .getStops()
                .getFirst()
                .getRideOrderId();
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
                .extracting(TaskStop::getRideOrderId, TaskStop::getStopType)
                .containsExactly(
                        tuple(existingOrderId, "BOARDING"),
                        tuple(insertedOrder.getId(), "BOARDING"),
                        tuple(insertedOrder.getId(), "ALIGHTING"),
                        tuple(existingOrderId, "ALIGHTING"));
        assertThat(auditLogRepository.findByEntityId(insertedOrder.getId()))
                .anyMatch(log -> log.getAction().equals("MANUAL_REVIEW_APPROVED"));
    }

    @Test
    void approveManualReviewRejectsInsertWhenExistingTaskHasNoSeats() throws Exception {
        UUID existingTaskId = createInProgressTaskWithOneOrder();
        UUID decisionId = createManualReviewDecision(existingTaskId, 8);

        mockMvc.perform(post("/api/dispatch-decisions/" + decisionId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken))
                .andExpect(status().isConflict());

        VehicleTask task = vehicleTaskRepository.findWithStopsById(existingTaskId).orElseThrow();
        assertThat(task.getStops()).hasSize(2);
    }

    @Test
    void approveManualReviewRejectsMissingSelectedExistingTask() throws Exception {
        UUID decisionId = createManualReviewDecision(UUID.randomUUID());

        mockMvc.perform(post("/api/dispatch-decisions/" + decisionId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken))
                .andExpect(status().isConflict());

        assertThat(vehicleTaskRepository.findAll()).isEmpty();
        assertThat(rideOrderRepository.findAll().getFirst().getStatus())
                .isEqualTo(OrderStatus.PENDING_MANUAL_REVIEW);
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

    @Test
    void approveManualReviewRejectsStaleNewTaskReadinessWithoutBusinessMutation() throws Exception {
        // Mutation caught: trusting the old manual-review decision instead of rechecking readiness.
        UUID decisionId = createManualReviewDecision();
        UUID orderId = dispatchDecisionRepository.findById(decisionId).orElseThrow().getRideOrderId();
        JtTerminal terminal = suspendDispatchTerminal();
        long auditCountBefore = auditLogRepository.count();

        String response = mockMvc.perform(post("/api/dispatch-decisions/" + decisionId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.message").value("DISPATCH_ONBOARD_SYSTEM_NOT_READY"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(rideOrderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING_MANUAL_REVIEW);
        assertThat(vehicleTaskRepository.findAll()).isEmpty();
        assertThat(vehicleRepository.findById(vehicleId).orElseThrow().getCurrentStatus()).isEqualTo("IDLE");
        assertThat(driverRepository.findById(DRIVER_ID).orElseThrow().getCurrentStatus()).isEqualTo("AVAILABLE");
        assertThat(auditLogRepository.count()).isEqualTo(auditCountBefore);
        assertThat(response)
                .doesNotContain(terminal.getTerminalPhone())
                .doesNotContain(terminal.getTerminalCode())
                .doesNotContain(terminal.getAuthTokenHash());
    }

    @Test
    void rejectsApprovalWhenDispatchLeaseExpiresAfterCandidateWasCreated() throws Exception {
        UUID decisionId = createManualReviewDecision();
        UUID orderId = dispatchDecisionRepository.findById(decisionId)
                .orElseThrow().getRideOrderId();
        leaseRepository.deleteAll();

        mockMvc.perform(post("/api/dispatch-decisions/" + decisionId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.message")
                        .value("DISPATCH_ONBOARD_SYSTEM_NOT_READY"));

        assertThat(rideOrderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING_MANUAL_REVIEW);
        assertThat(vehicleTaskRepository.findAll()).isEmpty();
    }

    @Test
    void approveManualReviewRejectsStaleInsertionReadinessWithoutBusinessMutation() throws Exception {
        // Mutation caught: checking readiness only on the new-task branch, after insertion has already mutated state.
        UUID existingTaskId = createInProgressTaskWithOneOrder();
        UUID decisionId = createManualReviewDecision(existingTaskId);
        UUID orderId = dispatchDecisionRepository.findById(decisionId).orElseThrow().getRideOrderId();
        suspendDispatchTerminal();
        long auditCountBefore = auditLogRepository.count();

        mockMvc.perform(post("/api/dispatch-decisions/" + decisionId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.message").value("DISPATCH_ONBOARD_SYSTEM_NOT_READY"));

        VehicleTask task = vehicleTaskRepository.findWithStopsById(existingTaskId).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(task.getStops()).hasSize(2);
        assertThat(rideOrderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING_MANUAL_REVIEW);
        assertThat(vehicleTaskRepository.findAll()).hasSize(1);
        assertThat(vehicleRepository.findById(vehicleId).orElseThrow().getCurrentStatus()).isEqualTo("IDLE");
        assertThat(driverRepository.findById(DRIVER_ID).orElseThrow().getCurrentStatus()).isEqualTo("AVAILABLE");
        assertThat(auditLogRepository.count()).isEqualTo(auditCountBefore);
    }

    @Test
    void approveManualReviewPropagatesInfrastructureLockFailureWithoutBusinessMutation() throws Exception {
        // Mutation caught: translating a database/lock failure into business 409 readiness state.
        UUID decisionId = createManualReviewDecision();
        UUID orderId = dispatchDecisionRepository.findById(decisionId).orElseThrow().getRideOrderId();
        UUID systemId = onboardSystemRepository.findActiveByVehicleId(vehicleId).orElseThrow().getId();
        long auditCountBefore = auditLogRepository.count();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> blocker = executor.submit(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    onboardSystemRepository.findLockedById(systemId).orElseThrow();
                    locked.countDown();
                    await(release);
                }));
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

        Throwable failure;
        try {
            failure = catchThrowable(() -> manualReviewService.approve(dispatcherId, decisionId));
        } finally {
            release.countDown();
            blocker.get(5, TimeUnit.SECONDS);
            executor.shutdownNow();
        }

        assertThat(rideOrderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING_MANUAL_REVIEW);
        assertThat(vehicleTaskRepository.findAll()).isEmpty();
        assertThat(vehicleRepository.findById(vehicleId).orElseThrow().getCurrentStatus()).isEqualTo("IDLE");
        assertThat(driverRepository.findById(DRIVER_ID).orElseThrow().getCurrentStatus()).isEqualTo("AVAILABLE");
        assertThat(auditLogRepository.count()).isEqualTo(auditCountBefore);
        assertThat(failure).isNotNull();
        assertThat(findCause(failure, DataAccessException.class)).isNotNull();
        assertThat(findCause(failure, ResponseStatusException.class)).isNull();
    }

    private UUID createManualReviewDecision() {
        return createManualReviewDecision(null);
    }

    private UUID createManualReviewDecision(UUID bestTaskId) {
        return createManualReviewDecision(bestTaskId, 1);
    }

    private UUID createManualReviewDecision(UUID bestTaskId, int passengerCount) {
        RideOrder order = RideOrder.pendingDispatch(new RideOrder.CreateOrderCommand(
                "张三",
                "13800000000",
                passengerCount,
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
                        vehicleId,
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
                vehicleId,
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

    private JtTerminal suspendDispatchTerminal() {
        OnboardSystem system = onboardSystemRepository.findActiveByVehicleId(vehicleId).orElseThrow();
        OnboardDeviceRoleAssignment dispatchRole = onboardRoleRepository
                .findActiveByOnboardSystemIdAndRole(system.getId(), Role.DISPATCH)
                .orElseThrow();
        JtTerminal terminal = terminalRepository.findById(dispatchRole.getTerminalId()).orElseThrow();
        terminal.suspend();
        return terminalRepository.saveAndFlush(terminal);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for system lock test latch");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("system lock test latch interrupted", interrupted);
        }
    }

    private static <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RouteTestConfiguration {

        @Bean
        @Primary
        RoutePlanningProvider routePlanningProvider() {
            return new RoutePlanningProvider() {
                @Override
                public RoutePlanResult drivingRoute(
                        Coordinate origin, Coordinate destination, List<Coordinate> waypoints) {
                    if (origin.longitude().compareTo(destination.longitude()) == 0
                            && origin.latitude().compareTo(destination.latitude()) == 0) {
                        return new RoutePlanResult(0, 0, List.of(origin, destination));
                    }
                    return new RoutePlanResult(1_200, 360, List.of(origin, destination));
                }

                @Override
                public DistanceResult distance(Coordinate origin, Coordinate destination) {
                    return new DistanceResult(1_200, 360);
                }
            };
        }
    }
}
