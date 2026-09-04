package com.idavy.drtops.domain.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.domain.fleet.Driver;
import com.idavy.drtops.domain.fleet.DriverRepository;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import com.idavy.drtops.domain.map.Coordinate;
import com.idavy.drtops.domain.map.DistanceResult;
import com.idavy.drtops.domain.map.RoutePlanResult;
import com.idavy.drtops.domain.map.RoutePlanningProvider;
import com.idavy.drtops.domain.onboard.OnboardSystemRepository;
import com.idavy.drtops.domain.onboard.OnboardTestFixtures;
import com.idavy.drtops.domain.order.OrderStatus;
import com.idavy.drtops.domain.order.RideOrder;
import com.idavy.drtops.domain.order.RideOrderRepository;
import com.idavy.drtops.domain.task.TaskStop;
import com.idavy.drtops.domain.task.TaskStatus;
import com.idavy.drtops.domain.task.VehicleTask;
import com.idavy.drtops.domain.task.VehicleTaskRepository;
import com.idavy.drtops.domain.terminal.JtTerminalSessionLeaseRepository;
import com.idavy.drtops.integration.algorithm.AlgorithmClient;
import com.idavy.drtops.integration.algorithm.DispatchEvaluateRequest;
import com.idavy.drtops.integration.algorithm.DispatchEvaluateResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dispatch_orchestrator;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=250",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
@WithMockUser(authorities = "DISPATCH_EXECUTE")
@Import(OnboardTestFixtures.class)
class DispatchOrchestratorTest {

    private static final UUID RULE_SET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOARDING_STOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555551");
    private static final UUID ALIGHTING_STOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555552");
    private static final UUID INSERTED_BOARDING_STOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555553");
    private static final UUID INSERTED_ALIGHTING_STOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555554");
    private static final UUID DRIVER_ID = UUID.fromString("44444444-4444-4444-4444-444444444441");
    private static final String READINESS_FAILURE_METRIC =
            "drt.dispatch.onboard.readiness.evaluation.failures";

    private UUID vehicleId;

    @Autowired
    DispatchOrchestrator orchestrator;

    @Autowired
    RideOrderRepository rideOrderRepository;

    @Autowired
    VehicleRepository vehicleRepository;

    @Autowired
    DriverRepository driverRepository;

    @Autowired
    VehicleTaskRepository vehicleTaskRepository;

    @Autowired
    DispatchRuleSetRepository ruleSetRepository;

    @Autowired
    DispatchDecisionRepository dispatchDecisionRepository;

    @Autowired
    AuditLogRepository auditLogRepository;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    FakeAlgorithmClient algorithmClient;

    @Autowired
    OnboardTestFixtures onboardFixtures;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    EntityManager entityManager;

    @Autowired
    OnboardSystemRepository onboardSystemRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JtTerminalSessionLeaseRepository leaseRepository;

    @Autowired
    MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        dispatchDecisionRepository.deleteAll();
        auditLogRepository.deleteAll();
        vehicleTaskRepository.deleteAll();
        rideOrderRepository.deleteAll();
        driverRepository.deleteAll();
        ruleSetRepository.deleteAll();
        onboardFixtures.clear();
        algorithmClient.reset();

        ruleSetRepository.save(DispatchRuleSet.defaultRules(RULE_SET_ID));
        vehicleId = onboardFixtures.readyDispatchSystemVehicleId();
        driverRepository.save(Driver.create(
                DRIVER_ID,
                "王师傅",
                "13900002001",
                "QUALIFIED",
                OffsetDateTime.parse("2026-07-08T08:00:00+08:00"),
                OffsetDateTime.parse("2026-07-08T18:00:00+08:00"),
                "AVAILABLE",
                "演示车队"));
    }

    @Test
    void autoDispatchConfirmsOrderAndCreatesVehicleTask() {
        UUID orderId = createPendingOrder();
        algorithmClient.stubAutoDispatch(vehicleId);

        DispatchResult result = orchestrator.dispatchOrder(orderId);

        assertThat(result.decision()).isEqualTo(DispatchDecisionType.AUTO_DISPATCH);
        assertThat(rideOrderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CONFIRMED);
        assertThat(vehicleTaskRepository.findAll()).hasSize(1);
        assertThat(dispatchDecisionRepository.findByRideOrderId(orderId)).hasSize(1);
        assertThat(auditLogRepository.findByEntityId(orderId))
                .anyMatch(log -> log.getAction().equals("ORDER_AUTO_DISPATCHED"));
        assertThat(algorithmClient.lastRequest().order().orderId()).isEqualTo(orderId);
        assertThat(algorithmClient.lastRequest().candidateTasks()).hasSize(1);
        assertThat(vehicleRepository.findById(vehicleId).orElseThrow().getCurrentStatus())
                .isEqualTo("DISPATCHED");
        assertThat(driverRepository.findById(DRIVER_ID).orElseThrow().getCurrentStatus())
                .isEqualTo("BUSY");
    }

    @Test
    void automaticAssemblyExcludesUnreadyIdleVehicleButRetainsReadyNeighbor() {
        // Mutation caught: filtering only Vehicle.dispatchable leaves an unready idle vehicle in the request.
        UUID unreadyVehicleId = createStaleOnboardVehicle();
        driverRepository.save(Driver.create(
                UUID.randomUUID(),
                "邻车驾驶员",
                "13900002002",
                "QUALIFIED",
                OffsetDateTime.parse("2026-07-08T08:00:00+08:00"),
                OffsetDateTime.parse("2026-07-08T18:00:00+08:00"),
                "AVAILABLE",
                "演示车队"));
        UUID orderId = createPendingOrder();
        algorithmClient.stubAutoDispatch(vehicleId);

        orchestrator.dispatchOrder(orderId);

        assertThat(algorithmClient.lastRequest().candidateTasks())
                .extracting(DispatchEvaluateRequest.CandidateTask::vehicleId)
                .containsExactly(vehicleId)
                .doesNotContain(unreadyVehicleId);
    }

    @Test
    void excludesHistoricallyAuthenticatedVehicleWhenDispatchLeaseExpiredButBackupLocationIsFresh() {
        UUID expiredLeaseVehicleId = vehicleId;
        leaseRepository.deleteAll();
        UUID readyNeighbor = onboardFixtures.readyDispatchSystemVehicleId();
        driverRepository.save(Driver.create(
                UUID.randomUUID(),
                "租约邻车驾驶员",
                "13900002009",
                "QUALIFIED",
                OffsetDateTime.parse("2026-07-08T08:00:00+08:00"),
                OffsetDateTime.parse("2026-07-08T18:00:00+08:00"),
                "AVAILABLE",
                "演示车队"));
        UUID orderId = createPendingOrder();
        algorithmClient.stubAutoDispatch(readyNeighbor);

        orchestrator.dispatchOrder(orderId);

        assertThat(algorithmClient.lastRequest().candidateTasks())
                .extracting(DispatchEvaluateRequest.CandidateTask::vehicleId)
                .containsExactly(readyNeighbor)
                .doesNotContain(expiredLeaseVehicleId);
    }

    @Test
    void automaticAssemblyExcludesUnreadyExistingTaskVehicleButRetainsReadyNeighbor() {
        // Mutation caught: filtering only new idle candidates leaves an unready active-task vehicle insertable.
        UUID unreadyVehicleId = createStaleOnboardVehicle();
        UUID unreadyTaskId = createInProgressTaskWithOneOrder(unreadyVehicleId, DRIVER_ID);
        UUID orderId = createPendingOrder();
        algorithmClient.stubAutoDispatch(vehicleId);

        orchestrator.dispatchOrder(orderId);

        assertThat(algorithmClient.lastRequest().candidateTasks())
                .extracting(DispatchEvaluateRequest.CandidateTask::vehicleId)
                .containsExactly(vehicleId)
                .doesNotContain(unreadyVehicleId);
        assertThat(algorithmClient.lastRequest().candidateTasks())
                .extracting(DispatchEvaluateRequest.CandidateTask::taskId)
                .doesNotContain(unreadyTaskId);
    }

    @Test
    void automaticAssemblyObservesLockTimeoutAndRetainsReadyNeighbor() throws Exception {
        // Mutation caught: silently swallowing an infrastructure failure without a metric or safe log.
        UUID lockedVehicleId = onboardFixtures.readyDispatchSystemVehicleId();
        driverRepository.save(Driver.create(
                UUID.randomUUID(),
                "锁超时邻车驾驶员",
                "13900002003",
                "QUALIFIED",
                OffsetDateTime.parse("2026-07-08T08:00:00+08:00"),
                OffsetDateTime.parse("2026-07-08T18:00:00+08:00"),
                "AVAILABLE",
                "演示车队"));
        UUID orderId = createPendingOrder();
        algorithmClient.stubAutoDispatch(vehicleId);
        UUID systemId = onboardSystemRepository.findActiveByVehicleId(lockedVehicleId).orElseThrow().getId();
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

        Logger logger = (Logger) LoggerFactory.getLogger(CandidateTaskAssembler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Counter failureCounter = meterRegistry.counter(READINESS_FAILURE_METRIC);
        double failuresBefore = failureCounter.count();
        try {
            orchestrator.dispatchOrder(orderId);
        } finally {
            release.countDown();
            blocker.get(5, TimeUnit.SECONDS);
            executor.shutdownNow();
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(algorithmClient.lastRequest().candidateTasks())
                .extracting(DispatchEvaluateRequest.CandidateTask::vehicleId)
                .containsExactly(vehicleId)
                .doesNotContain(lockedVehicleId);
        assertThat(failureCounter.count()).isEqualTo(failuresBefore + 1D);
        assertThat(appender.list)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getFormattedMessage())
                            .contains("event=ONBOARD_READINESS_EVALUATION_FAILED")
                            .contains("failureType=")
                            .doesNotContain(lockedVehicleId.toString());
                    assertThat(event.getThrowableProxy()).isNull();
                });
    }

    @Test
    void autoDispatchCreatesVehicleTaskWhenAlgorithmEchoesNewCandidateTaskId() {
        UUID orderId = createPendingOrder();
        algorithmClient.stubAutoDispatchEchoingCandidateTaskId();

        DispatchResult result = orchestrator.dispatchOrder(orderId);

        assertThat(result.decision()).isEqualTo(DispatchDecisionType.AUTO_DISPATCH);
        assertThat(vehicleTaskRepository.findAll()).hasSize(1);
        assertThat(result.vehicleTaskId()).isEqualTo(vehicleTaskRepository.findAll().getFirst().getId());
        assertThat(dispatchDecisionRepository.findByRideOrderId(orderId).getFirst().getBestTaskId())
                .isEqualTo(result.vehicleTaskId());
    }

    @Test
    void autoDispatchCanInsertOrderIntoExistingInProgressTask() {
        UUID existingTaskId = createInProgressTaskWithOneOrder();
        UUID existingOrderId = vehicleTaskRepository.findWithStopsById(existingTaskId)
                .orElseThrow()
                .getStops()
                .getFirst()
                .getRideOrderId();
        UUID orderId = createPendingOrder();
        algorithmClient.stubAutoDispatchIntoTask(existingTaskId, vehicleId);

        DispatchResult result = orchestrator.dispatchOrder(orderId);

        assertThat(result.vehicleTaskId()).isEqualTo(existingTaskId);
        assertThat(vehicleTaskRepository.findAll()).hasSize(1);
        VehicleTask task = vehicleTaskRepository.findWithStopsById(existingTaskId).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(task.getStops()).hasSize(4);
        assertThat(task.getStops())
                .extracting(TaskStop::getRideOrderId, TaskStop::getStopType)
                .containsExactly(
                        tuple(existingOrderId, "BOARDING"),
                        tuple(orderId, "BOARDING"),
                        tuple(orderId, "ALIGHTING"),
                        tuple(existingOrderId, "ALIGHTING"));
        assertThat(rideOrderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CONFIRMED);
        assertThat(dispatchDecisionRepository.findByRideOrderId(orderId).getFirst().getBestTaskId())
                .isEqualTo(existingTaskId);
        assertThat(algorithmClient.lastRequest().candidateTasks())
                .extracting(DispatchEvaluateRequest.CandidateTask::taskId)
                .containsExactly(existingTaskId);
        DispatchEvaluateRequest.CandidateTask candidate =
                algorithmClient.lastRequest().candidateTasks().getFirst();
        assertThat(candidate.candidateType()).isEqualTo("EXISTING_TASK");
        assertThat(candidate.activationCost()).isZero();
        assertThat(candidate.estimatedDetourMinutes()).isZero();
        assertThat(candidate.precheckRejectionReason()).isNull();
    }

    @Test
    void autoDispatchAppliesRoutePlannedStopOrderInsteadOfAppending() {
        UUID existingTaskId = createInProgressTaskWithOneOrder();
        UUID existingOrderId = vehicleTaskRepository.findWithStopsById(existingTaskId)
                .orElseThrow().getStops().getFirst().getRideOrderId();
        UUID orderId = createPendingOrder(
                INSERTED_BOARDING_STOP_ID,
                INSERTED_ALIGHTING_STOP_ID,
                new BigDecimal("120.1550000"),
                new BigDecimal("30.2741000"),
                new BigDecimal("120.1688000"),
                new BigDecimal("30.2799000"));
        algorithmClient.stubAutoDispatchIntoTask(existingTaskId, vehicleId);

        orchestrator.dispatchOrder(orderId);

        assertThat(vehicleTaskRepository.findWithStopsById(existingTaskId).orElseThrow().getStops())
                .extracting(TaskStop::getRideOrderId, TaskStop::getStopType)
                .containsExactly(
                        tuple(orderId, "BOARDING"),
                        tuple(existingOrderId, "BOARDING"),
                        tuple(orderId, "ALIGHTING"),
                        tuple(existingOrderId, "ALIGHTING"));
        String explanationJson = dispatchDecisionRepository.findByRideOrderId(orderId)
                .getFirst().getExplanationJson();
        assertThat(explanationJson)
                .contains("\"candidateType\":\"EXISTING_TASK\"")
                .contains("\"activationCost\":0")
                .contains("\"selectionReason\":\"EXISTING_TASK_PREFERRED\"")
                .contains("\"baselineRouteDurationSeconds\"")
                .contains("\"plannedRouteDurationSeconds\"")
                .contains("\"maxPassengerDetourMinutes\"")
                .contains("\"peakOccupiedSeats\"")
                .contains("\"insertionBoardingPosition\"")
                .contains("\"insertionAlightingPosition\"");
    }

    @Test
    void threeSequentialCompatibleOrdersReuseOneVehicleTask() {
        algorithmClient.stubPreferFeasibleExistingTask();
        UUID firstOrderId = createPendingOrder(1);
        UUID secondOrderId = createPendingOrder(2);
        UUID thirdOrderId = createPendingOrder(1);

        DispatchResult firstResult = orchestrator.dispatchOrder(firstOrderId);
        DispatchResult secondResult = orchestrator.dispatchOrder(secondOrderId);
        DispatchResult thirdResult = orchestrator.dispatchOrder(thirdOrderId);

        assertThat(vehicleTaskRepository.findAll()).hasSize(1);
        assertThat(secondResult.vehicleTaskId()).isEqualTo(firstResult.vehicleTaskId());
        assertThat(thirdResult.vehicleTaskId()).isEqualTo(firstResult.vehicleTaskId());
        assertThat(vehicleTaskRepository.findWithStopsById(firstResult.vehicleTaskId()).orElseThrow()
                .activeOrderIds())
                .containsExactlyInAnyOrder(firstOrderId, secondOrderId, thirdOrderId);
        assertThat(algorithmClient.lastRequest().candidateTasks())
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.candidateType()).isEqualTo("EXISTING_TASK");
                    assertThat(candidate.activationCost()).isZero();
                    assertThat(candidate.precheckRejectionReason()).isNull();
                    assertThat(candidate.utilizationAfterInsert()).isEqualByComparingTo("0.50");
                });
    }

    @Test
    void autoDispatchRejectsInsertWhenExistingTaskHasNoSeats() {
        UUID existingTaskId = createInProgressTaskWithOneOrder();
        UUID orderId = createPendingOrder(8);
        algorithmClient.stubAutoDispatchIntoTask(existingTaskId, vehicleId);

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> orchestrator.dispatchOrder(orderId));

        VehicleTask task = vehicleTaskRepository.findWithStopsById(existingTaskId).orElseThrow();
        assertThat(task.getStops()).hasSize(2);
        assertThat(rideOrderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING_DISPATCH);
    }

    @Test
    void autoDispatchRejectsMissingSelectedExistingTask() {
        UUID missingTaskId = UUID.randomUUID();
        UUID orderId = createPendingOrder();
        algorithmClient.stubAutoDispatchIntoTask(missingTaskId, vehicleId);

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> orchestrator.dispatchOrder(orderId));

        assertThat(vehicleTaskRepository.findAll()).isEmpty();
        assertThat(rideOrderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING_DISPATCH);
    }

    @Test
    void autoDispatchRollsBackWhenSelectedTaskChangesAfterEvaluation() {
        UUID existingTaskId = createInProgressTaskWithOneOrder();
        UUID orderId = createPendingOrder();
        algorithmClient.stubAutoDispatchIntoTask(existingTaskId, vehicleId);
        algorithmClient.afterEvaluation(() -> vehicleTaskRepository.findById(existingTaskId)
                .orElseThrow().markException("并发状态变化"));

        org.springframework.web.server.ResponseStatusException exception =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.springframework.web.server.ResponseStatusException.class,
                        () -> orchestrator.dispatchOrder(orderId));

        assertThat(exception.getReason()).isEqualTo("DISPATCH_CANDIDATE_STALE");
        assertThat(rideOrderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING_DISPATCH);
        assertThat(vehicleTaskRepository.findWithStopsById(existingTaskId).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.IN_PROGRESS);
    }

    @Test
    void manualReviewKeepsOrderPendingManualReview() {
        UUID orderId = createPendingOrder();
        algorithmClient.stubManualReview(vehicleId);

        DispatchResult result = orchestrator.dispatchOrder(orderId);

        assertThat(result.decision()).isEqualTo(DispatchDecisionType.MANUAL_REVIEW);
        assertThat(rideOrderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING_MANUAL_REVIEW);
        assertThat(vehicleTaskRepository.findAll()).isEmpty();
        assertThat(dispatchDecisionRepository.findByRideOrderId(orderId)).hasSize(1);
    }

    @Test
    void manualReviewPreservesExistingTaskCandidateForApproval() {
        UUID existingTaskId = createInProgressTaskWithOneOrder();
        UUID orderId = createPendingOrder();
        algorithmClient.stubManualReviewIntoTask(existingTaskId, vehicleId);

        DispatchResult result = orchestrator.dispatchOrder(orderId);

        assertThat(result.decision()).isEqualTo(DispatchDecisionType.MANUAL_REVIEW);
        assertThat(result.vehicleTaskId()).isNull();
        assertThat(dispatchDecisionRepository.findByRideOrderId(orderId).getFirst().getBestTaskId())
                .isEqualTo(existingTaskId);
    }

    @Test
    void dispatchApiReturnsDecision() throws Exception {
        UUID orderId = createPendingOrder();
        algorithmClient.stubManualReview(vehicleId);

        mockMvc.perform(post("/api/orders/" + orderId + "/dispatch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decision").value("MANUAL_REVIEW"));
    }

    private UUID createPendingOrder() {
        return createPendingOrder(1);
    }

    private UUID createPendingOrder(int passengerCount) {
        return createPendingOrder(
                BOARDING_STOP_ID,
                ALIGHTING_STOP_ID,
                new BigDecimal("120.1550000"),
                new BigDecimal("30.2741000"),
                new BigDecimal("120.1688000"),
                new BigDecimal("30.2799000"),
                passengerCount);
    }

    private UUID createPendingOrder(
            UUID boardingStopId,
            UUID alightingStopId,
            BigDecimal originLng,
            BigDecimal originLat,
            BigDecimal destinationLng,
            BigDecimal destinationLat) {
        return createPendingOrder(
                boardingStopId,
                alightingStopId,
                originLng,
                originLat,
                destinationLng,
                destinationLat,
                1);
    }

    private UUID createPendingOrder(
            UUID boardingStopId,
            UUID alightingStopId,
            BigDecimal originLng,
            BigDecimal originLat,
            BigDecimal destinationLng,
            BigDecimal destinationLat,
            int passengerCount) {
        RideOrder order = RideOrder.pendingDispatch(new RideOrder.CreateOrderCommand(
                "张三",
                "13800000000",
                passengerCount,
                "IMMEDIATE",
                originLng,
                originLat,
                destinationLng,
                destinationLat,
                boardingStopId,
                alightingStopId,
                OffsetDateTime.parse("2026-07-08T02:30:00Z")));
        return rideOrderRepository.save(order).getId();
    }

    private UUID createInProgressTaskWithOneOrder() {
        return createInProgressTaskWithOneOrder(vehicleId, DRIVER_ID);
    }

    private UUID createStaleOnboardVehicle() {
        UUID staleVehicleId = onboardFixtures.readyDispatchSystemVehicleId();
        jdbcTemplate.update(
                "update vehicles set current_location_stale = true where id = ?",
                staleVehicleId);
        entityManager.clear();
        return staleVehicleId;
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

    private UUID createInProgressTaskWithOneOrder(UUID assignedVehicleId, UUID assignedDriverId) {
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
                assignedVehicleId,
                assignedDriverId,
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

    @TestConfiguration
    static class FakeAlgorithmClientConfiguration {

        @Bean
        @Primary
        FakeAlgorithmClient fakeAlgorithmClient() {
            return new FakeAlgorithmClient();
        }

        @Bean
        @Primary
        RoutePlanningProvider routePlanningProvider() {
            return new RoutePlanningProvider() {
                @Override
                public RoutePlanResult drivingRoute(Coordinate origin, Coordinate destination, List<Coordinate> waypoints) {
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

    static class FakeAlgorithmClient implements AlgorithmClient {

        private DispatchEvaluateResponse nextResponse;
        private DispatchEvaluateRequest lastRequest;
        private boolean echoCandidateTaskId;
        private boolean preferFeasibleExistingTask;
        private Runnable afterEvaluation;

        @Override
        public DispatchEvaluateResponse evaluate(DispatchEvaluateRequest request) {
            this.lastRequest = request;
            if (afterEvaluation != null) {
                afterEvaluation.run();
            }
            if (preferFeasibleExistingTask) {
                DispatchEvaluateRequest.CandidateTask candidate = request.candidateTasks().stream()
                        .filter(item -> item.precheckRejectionReason() == null)
                        .min(Comparator.comparingInt(DispatchEvaluateRequest.CandidateTask::activationCost)
                                .thenComparing(DispatchEvaluateRequest.CandidateTask::taskDisruptionScore))
                        .orElseThrow();
                return response(DispatchDecisionType.AUTO_DISPATCH, candidate.taskId(), candidate.vehicleId());
            }
            if (echoCandidateTaskId) {
                DispatchEvaluateRequest.CandidateTask candidate = request.candidateTasks().getFirst();
                return response(DispatchDecisionType.AUTO_DISPATCH, candidate.taskId(), candidate.vehicleId());
            }
            return nextResponse;
        }

        void reset() {
            nextResponse = null;
            lastRequest = null;
            echoCandidateTaskId = false;
            preferFeasibleExistingTask = false;
            afterEvaluation = null;
        }

        DispatchEvaluateRequest lastRequest() {
            return lastRequest;
        }

        void stubAutoDispatch(UUID vehicleId) {
            nextResponse = response(DispatchDecisionType.AUTO_DISPATCH, vehicleId);
        }

        void stubAutoDispatchEchoingCandidateTaskId() {
            echoCandidateTaskId = true;
        }

        void stubPreferFeasibleExistingTask() {
            preferFeasibleExistingTask = true;
        }

        void stubAutoDispatchIntoTask(UUID taskId, UUID vehicleId) {
            nextResponse = response(DispatchDecisionType.AUTO_DISPATCH, taskId, vehicleId);
        }

        void stubManualReview(UUID vehicleId) {
            nextResponse = response(DispatchDecisionType.MANUAL_REVIEW, vehicleId);
        }

        void stubManualReviewIntoTask(UUID taskId, UUID vehicleId) {
            nextResponse = response(DispatchDecisionType.MANUAL_REVIEW, taskId, vehicleId);
        }

        void afterEvaluation(Runnable action) {
            afterEvaluation = action;
        }

        private DispatchEvaluateResponse response(DispatchDecisionType decision, UUID vehicleId) {
            return response(decision, null, vehicleId);
        }

        private DispatchEvaluateResponse response(DispatchDecisionType decision, UUID taskId, UUID vehicleId) {
            boolean existingTask = taskId != null;
            return new DispatchEvaluateResponse(
                    decision,
                    new DispatchEvaluateResponse.BestPlan(
                            taskId,
                            vehicleId,
                            new BigDecimal("88.50"),
                            6,
                            3,
                            "SAME_DIRECTION",
                            new BigDecimal("0.67"),
                            existingTask ? "EXISTING_TASK" : "NEW_TASK",
                            existingTask ? 0 : 1,
                            existingTask ? "EXISTING_TASK_PREFERRED" : "NEW_VEHICLE_REQUIRED"),
                    1,
                    0,
                    List.of(),
                    Map.of("reason", decision.name()));
        }
    }
}
