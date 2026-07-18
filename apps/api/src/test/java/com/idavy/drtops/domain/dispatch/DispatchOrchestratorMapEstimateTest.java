package com.idavy.drtops.domain.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.domain.fleet.Driver;
import com.idavy.drtops.domain.fleet.DriverRepository;
import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import com.idavy.drtops.domain.location.LocationSource;
import com.idavy.drtops.domain.map.Coordinate;
import com.idavy.drtops.domain.map.DistanceResult;
import com.idavy.drtops.domain.map.MapProviderException;
import com.idavy.drtops.domain.map.MapProviderStatus;
import com.idavy.drtops.domain.map.RoutePlanResult;
import com.idavy.drtops.domain.map.RoutePlanningProvider;
import com.idavy.drtops.domain.order.OrderStatus;
import com.idavy.drtops.domain.order.RideOrder;
import com.idavy.drtops.domain.order.RideOrderRepository;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dispatch_map_estimate;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import(DispatchOrchestratorMapEstimateTest.MapEstimateTestConfiguration.class)
class DispatchOrchestratorMapEstimateTest {

    private static final UUID VEHICLE_ID = UUID.fromString("33333333-3333-3333-3333-333333333331");
    private static final UUID DRIVER_ID = UUID.fromString("44444444-4444-4444-4444-444444444441");

    @Autowired private DispatchOrchestrator orchestrator;
    @Autowired private RideOrderRepository rideOrderRepository;
    @Autowired private VehicleRepository vehicleRepository;
    @Autowired private DriverRepository driverRepository;
    @Autowired private VehicleTaskRepository vehicleTaskRepository;
    @Autowired private DispatchRuleSetRepository ruleSetRepository;
    @Autowired private DispatchDecisionRepository decisionRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private FakeAlgorithmClient algorithmClient;
    @Autowired private FailingRoutePlanningProvider routePlanningProvider;

    @BeforeEach
    void setUp() {
        decisionRepository.deleteAll();
        auditLogRepository.deleteAll();
        vehicleTaskRepository.deleteAll();
        rideOrderRepository.deleteAll();
        vehicleRepository.deleteAll();
        driverRepository.deleteAll();
        ruleSetRepository.deleteAll();
        algorithmClient.reset();
        routePlanningProvider.failureReason = "request-timeout";
        ruleSetRepository.save(DispatchRuleSet.defaultRules(UUID.randomUUID()));

        Vehicle vehicle = Vehicle.create(
                VEHICLE_ID, "DRT-201", "Microbus", 8, "IDLE", "POINT(105.230000 35.200000)", "Test", true);
        OffsetDateTime reportedAt = OffsetDateTime.parse("2026-07-18T08:00:00+08:00");
        vehicle.applyLocationSnapshot(
                "POINT(105.230000 35.200000)", "测试车辆位置", LocationSource.MANUAL_DISPATCHER, "GCJ-02",
                reportedAt, reportedAt, UUID.randomUUID(), null);
        vehicleRepository.save(vehicle);
        driverRepository.save(Driver.create(
                DRIVER_ID, "王师傅", "13900002001", "QUALIFIED",
                OffsetDateTime.parse("2026-07-18T00:00:00Z"), OffsetDateTime.parse("2026-07-18T12:00:00Z"),
                "AVAILABLE", "Test"));
    }

    @Test
    void degradesAutoDispatchToManualReviewWhenRouteEstimateIsUnavailable() {
        RideOrder order = rideOrderRepository.save(RideOrder.pendingDispatch(new RideOrder.CreateOrderCommand(
                "张三", "13800000000", 1, "IMMEDIATE",
                new BigDecimal("105.242100"), new BigDecimal("35.212300"),
                new BigDecimal("105.252100"), new BigDecimal("35.218300"),
                UUID.randomUUID(), UUID.randomUUID(), OffsetDateTime.parse("2026-07-18T01:00:00Z"))));
        algorithmClient.nextResponse = new DispatchEvaluateResponse(
                DispatchDecisionType.AUTO_DISPATCH,
                new DispatchEvaluateResponse.BestPlan(null, VEHICLE_ID, new BigDecimal("90.00"), 2, 1,
                        "SAME_DIRECTION", new BigDecimal("0.20")),
                1, 0, List.of(), Map.of("reason", "AUTO_DISPATCH"));

        DispatchResult result = orchestrator.dispatchOrder(order.getId());

        assertThat(result.decision()).isEqualTo(DispatchDecisionType.MANUAL_REVIEW);
        assertThat(rideOrderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING_MANUAL_REVIEW);
        DispatchDecision decision = decisionRepository.findByRideOrderId(order.getId()).getFirst();
        assertThat(decision.isMapDegraded()).isTrue();
        assertThat(decision.getMapDegradedReason()).isEqualTo("request-timeout");
        assertThat(decision.getPickupToDestinationDurationSeconds()).isPositive();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MapEstimateTestConfiguration {

        @Bean @Primary
        FakeAlgorithmClient fakeAlgorithmClient() {
            return new FakeAlgorithmClient();
        }

        @Bean @Primary
        FailingRoutePlanningProvider routePlanningProvider() {
            return new FailingRoutePlanningProvider();
        }
    }

    static class FakeAlgorithmClient implements AlgorithmClient {

        private DispatchEvaluateResponse nextResponse;

        @Override
        public DispatchEvaluateResponse evaluate(DispatchEvaluateRequest request) {
            return nextResponse;
        }

        void reset() {
            nextResponse = null;
        }
    }

    static class FailingRoutePlanningProvider implements RoutePlanningProvider {

        private String failureReason;

        @Override
        public RoutePlanResult drivingRoute(Coordinate origin, Coordinate destination, List<Coordinate> waypoints) {
            throw new MapProviderException(MapProviderStatus.degraded("AMAP", failureReason, "GCJ-02"));
        }

        @Override
        public DistanceResult distance(Coordinate origin, Coordinate destination) {
            throw new MapProviderException(MapProviderStatus.degraded("AMAP", failureReason, "GCJ-02"));
        }
    }
}
