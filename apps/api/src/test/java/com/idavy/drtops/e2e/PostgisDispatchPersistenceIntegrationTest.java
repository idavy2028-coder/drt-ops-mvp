package com.idavy.drtops.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.idavy.drtops.domain.dispatch.DispatchDecision;
import com.idavy.drtops.domain.dispatch.DispatchDecisionRepository;
import com.idavy.drtops.domain.dispatch.DispatchDecisionType;
import com.idavy.drtops.domain.dispatch.DispatchOrchestrator;
import com.idavy.drtops.domain.dispatch.DispatchResult;
import com.idavy.drtops.domain.order.RideOrder;
import com.idavy.drtops.domain.order.RideOrderRepository;
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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

@EnabledIfSystemProperty(named = "drt.integration.postgis", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=${drt.integration.postgis-url:jdbc:postgresql://127.0.0.1:15432/drt_ops}",
        "spring.datasource.username=drt_ops",
        "spring.datasource.password=drt_ops",
        "dispatch.algorithm.base-url=http://127.0.0.1:8090"
})
@Transactional
class PostgisDispatchPersistenceIntegrationTest {

    private static final UUID BOARDING_STOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555551");
    private static final UUID ALIGHTING_STOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555552");
    private static final UUID VEHICLE_ID = UUID.fromString("33333333-3333-3333-3333-333333333331");
    private static final UUID SYNTHETIC_TASK_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Autowired
    private DispatchOrchestrator dispatchOrchestrator;

    @Autowired
    private RideOrderRepository rideOrderRepository;

    @Autowired
    private DispatchDecisionRepository dispatchDecisionRepository;

    @Autowired
    private FakeAlgorithmClient algorithmClient;

    @BeforeEach
    void setUp() {
        algorithmClient.stubAutoDispatch();
    }

    @Test
    void autoDispatchPersistsDecisionAfterCreatingTheReferencedTask() {
        RideOrder order = createPendingOrder();

        DispatchResult result = dispatchOrchestrator.dispatchOrder(order.getId());
        DispatchDecision decision = dispatchDecisionRepository.findById(result.dispatchDecisionId()).orElseThrow();

        assertThat(result.vehicleTaskId()).isNotNull();
        assertThat(decision.getBestTaskId()).isEqualTo(result.vehicleTaskId());
    }

    @Test
    void manualReviewDoesNotPersistSyntheticCandidateTaskId() {
        algorithmClient.stubManualReview();
        RideOrder order = createPendingOrder();

        DispatchResult result = dispatchOrchestrator.dispatchOrder(order.getId());
        DispatchDecision decision = dispatchDecisionRepository.findById(result.dispatchDecisionId()).orElseThrow();

        assertThat(result.decision()).isEqualTo(DispatchDecisionType.MANUAL_REVIEW);
        assertThat(result.vehicleTaskId()).isNull();
        assertThat(decision.getBestTaskId()).isNull();
    }

    private RideOrder createPendingOrder() {
        return rideOrderRepository.save(RideOrder.pendingDispatch(new RideOrder.CreateOrderCommand(
                "PostGIS dispatch test",
                "13900009999",
                1,
                "IMMEDIATE",
                new BigDecimal("116.3120"),
                new BigDecimal("39.9400"),
                new BigDecimal("116.3250"),
                new BigDecimal("39.9360"),
                BOARDING_STOP_ID,
                ALIGHTING_STOP_ID,
                OffsetDateTime.now().plusMinutes(15))));
    }

    @TestConfiguration
    static class FakeAlgorithmClientConfiguration {

        @Bean
        @Primary
        FakeAlgorithmClient algorithmClient() {
            return new FakeAlgorithmClient();
        }
    }

    static class FakeAlgorithmClient implements AlgorithmClient {

        private DispatchDecisionType decision = DispatchDecisionType.AUTO_DISPATCH;

        @Override
        public DispatchEvaluateResponse evaluate(DispatchEvaluateRequest request) {
            return new DispatchEvaluateResponse(
                    decision,
                    new DispatchEvaluateResponse.BestPlan(
                            SYNTHETIC_TASK_ID,
                            VEHICLE_ID,
                            new BigDecimal("90.00"),
                            6,
                            3,
                            "SAME_DIRECTION",
                            new BigDecimal("0.12")),
                    1,
                    0,
                    List.of(),
                    Map.of("reason", "integration-test"));
        }

        void stubAutoDispatch() {
            decision = DispatchDecisionType.AUTO_DISPATCH;
        }

        void stubManualReview() {
            decision = DispatchDecisionType.MANUAL_REVIEW;
        }
    }
}
