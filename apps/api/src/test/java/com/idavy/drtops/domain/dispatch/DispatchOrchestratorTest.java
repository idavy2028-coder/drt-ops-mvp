package com.idavy.drtops.domain.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.idavy.drtops.integration.algorithm.AlgorithmClient;
import com.idavy.drtops.integration.algorithm.DispatchEvaluateRequest;
import com.idavy.drtops.integration.algorithm.DispatchEvaluateResponse;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dispatch_orchestrator;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class DispatchOrchestratorTest {

    private static final UUID RULE_SET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOARDING_STOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555551");
    private static final UUID ALIGHTING_STOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555552");
    private static final UUID VEHICLE_ID = UUID.fromString("33333333-3333-3333-3333-333333333331");
    private static final UUID DRIVER_ID = UUID.fromString("44444444-4444-4444-4444-444444444441");

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

    @BeforeEach
    void setUp() {
        dispatchDecisionRepository.deleteAll();
        auditLogRepository.deleteAll();
        vehicleTaskRepository.deleteAll();
        rideOrderRepository.deleteAll();
        vehicleRepository.deleteAll();
        driverRepository.deleteAll();
        ruleSetRepository.deleteAll();
        algorithmClient.reset();

        ruleSetRepository.save(DispatchRuleSet.defaultRules(RULE_SET_ID));
        vehicleRepository.save(Vehicle.create(
                VEHICLE_ID,
                "DRT-201",
                "Microbus",
                8,
                "IDLE",
                "POINT(120.1550000 30.2741000)",
                "演示车队",
                true));
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
        algorithmClient.stubAutoDispatch(VEHICLE_ID);

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
    }

    @Test
    void autoDispatchCanInsertOrderIntoExistingInProgressTask() {
        UUID existingTaskId = createInProgressTaskWithOneOrder();
        UUID orderId = createPendingOrder();
        algorithmClient.stubAutoDispatchIntoTask(existingTaskId, VEHICLE_ID);

        DispatchResult result = orchestrator.dispatchOrder(orderId);

        assertThat(result.vehicleTaskId()).isEqualTo(existingTaskId);
        assertThat(vehicleTaskRepository.findAll()).hasSize(1);
        VehicleTask task = vehicleTaskRepository.findWithStopsById(existingTaskId).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(task.getStops()).hasSize(4);
        assertThat(task.getStops())
                .filteredOn(stop -> orderId.equals(stop.getRideOrderId()))
                .extracting(TaskStop::getStopType)
                .containsExactly("BOARDING", "ALIGHTING");
        assertThat(rideOrderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CONFIRMED);
        assertThat(dispatchDecisionRepository.findByRideOrderId(orderId).getFirst().getBestTaskId())
                .isEqualTo(existingTaskId);
        assertThat(algorithmClient.lastRequest().candidateTasks())
                .extracting(DispatchEvaluateRequest.CandidateTask::taskId)
                .contains(existingTaskId);
    }

    @Test
    void autoDispatchRejectsInsertWhenExistingTaskHasNoSeats() {
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
        UUID orderId = createPendingOrder();
        algorithmClient.stubAutoDispatchIntoTask(existingTaskId, VEHICLE_ID);

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> orchestrator.dispatchOrder(orderId));

        VehicleTask task = vehicleTaskRepository.findWithStopsById(existingTaskId).orElseThrow();
        assertThat(task.getStops()).hasSize(2);
        assertThat(rideOrderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING_DISPATCH);
    }

    @Test
    void manualReviewKeepsOrderPendingManualReview() {
        UUID orderId = createPendingOrder();
        algorithmClient.stubManualReview(VEHICLE_ID);

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
        algorithmClient.stubManualReviewIntoTask(existingTaskId, VEHICLE_ID);

        DispatchResult result = orchestrator.dispatchOrder(orderId);

        assertThat(result.decision()).isEqualTo(DispatchDecisionType.MANUAL_REVIEW);
        assertThat(result.vehicleTaskId()).isNull();
        assertThat(dispatchDecisionRepository.findByRideOrderId(orderId).getFirst().getBestTaskId())
                .isEqualTo(existingTaskId);
    }

    @Test
    void dispatchApiReturnsDecision() throws Exception {
        UUID orderId = createPendingOrder();
        algorithmClient.stubManualReview(VEHICLE_ID);

        mockMvc.perform(post("/api/orders/" + orderId + "/dispatch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decision").value("MANUAL_REVIEW"));
    }

    private UUID createPendingOrder() {
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
        return rideOrderRepository.save(order).getId();
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

    @TestConfiguration
    static class FakeAlgorithmClientConfiguration {

        @Bean
        @Primary
        FakeAlgorithmClient fakeAlgorithmClient() {
            return new FakeAlgorithmClient();
        }
    }

    static class FakeAlgorithmClient implements AlgorithmClient {

        private DispatchEvaluateResponse nextResponse;
        private DispatchEvaluateRequest lastRequest;

        @Override
        public DispatchEvaluateResponse evaluate(DispatchEvaluateRequest request) {
            this.lastRequest = request;
            return nextResponse;
        }

        void reset() {
            nextResponse = null;
            lastRequest = null;
        }

        DispatchEvaluateRequest lastRequest() {
            return lastRequest;
        }

        void stubAutoDispatch(UUID vehicleId) {
            nextResponse = response(DispatchDecisionType.AUTO_DISPATCH, vehicleId);
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

        private DispatchEvaluateResponse response(DispatchDecisionType decision, UUID vehicleId) {
            return response(decision, null, vehicleId);
        }

        private DispatchEvaluateResponse response(DispatchDecisionType decision, UUID taskId, UUID vehicleId) {
            return new DispatchEvaluateResponse(
                    decision,
                    new DispatchEvaluateResponse.BestPlan(
                            taskId,
                            vehicleId,
                            new BigDecimal("88.50"),
                            6,
                            3,
                            "SAME_DIRECTION",
                            new BigDecimal("0.67")),
                    1,
                    0,
                    List.of(),
                    Map.of("reason", decision.name()));
        }
    }
}
