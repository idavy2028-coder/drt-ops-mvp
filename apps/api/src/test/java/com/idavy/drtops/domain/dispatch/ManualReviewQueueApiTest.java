package com.idavy.drtops.domain.dispatch;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idavy.drtops.domain.order.RideOrder;
import com.idavy.drtops.domain.order.RideOrderRepository;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:manual_review_queue_api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
@WithMockUser(authorities = "MANUAL_REVIEW")
class ManualReviewQueueApiTest {

    private static final UUID VEHICLE_ID = UUID.fromString("33333333-3333-3333-3333-333333333331");
    private static final UUID BOARDING_STOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555551");
    private static final UUID ALIGHTING_STOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555552");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    RideOrderRepository rideOrderRepository;

    @Autowired
    DispatchDecisionRepository dispatchDecisionRepository;

    @BeforeEach
    void setUp() {
        dispatchDecisionRepository.deleteAll();
        rideOrderRepository.deleteAll();
    }

    @Test
    void listsOnlyPendingManualReviewOrdersWithManualReviewDecisions() throws Exception {
        RideOrder currentOrder = createOrder("Manual review rider", 2, "2026-07-08T02:30:00Z");
        currentOrder.markPendingManualReview("score");
        currentOrder = rideOrderRepository.save(currentOrder);
        DispatchDecision currentDecision = dispatchDecisionRepository.save(manualReviewDecision(
                currentOrder.getId(),
                DispatchDecisionType.MANUAL_REVIEW,
                3));

        RideOrder legacyOrder = createOrder("Legacy review rider", 1, "2026-07-08T02:35:00Z");
        legacyOrder.markPendingManualReview("score");
        legacyOrder = rideOrderRepository.save(legacyOrder);
        DispatchDecision legacyDecision = dispatchDecisionRepository.save(DispatchDecision.manualReview(
                legacyOrder.getId(),
                1,
                VEHICLE_ID,
                null,
                "0.0.9",
                "SYSTEM",
                "legacy-dispatch"));

        RideOrder pendingOrder = rideOrderRepository.save(createOrder("Pending dispatch rider", 1, "2026-07-08T02:40:00Z"));
        dispatchDecisionRepository.save(manualReviewDecision(
                pendingOrder.getId(),
                DispatchDecisionType.MANUAL_REVIEW,
                2));

        mockMvc.perform(get("/api/dispatch-decisions/manual-review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].decisionId").value(currentDecision.getId().toString()))
                .andExpect(jsonPath("$.data[0].orderId").value(currentOrder.getId().toString()))
                .andExpect(jsonPath("$.data[0].passengerName").value("Manual review rider"))
                .andExpect(jsonPath("$.data[0].passengerCount").value(2))
                .andExpect(jsonPath("$.data[0].bestVehicleId").value(VEHICLE_ID.toString()))
                .andExpect(jsonPath("$.data[0].candidateCount").value(3))
                .andExpect(jsonPath("$.data[1].decisionId").value(legacyDecision.getId().toString()));
    }

    private DispatchDecision manualReviewDecision(
            UUID orderId,
            DispatchDecisionType decisionType,
            int candidateCount) {
        DispatchEvaluateResponse response = new DispatchEvaluateResponse(
                decisionType,
                new DispatchEvaluateResponse.BestPlan(
                        null,
                        VEHICLE_ID,
                        new BigDecimal("72.50"),
                        7,
                        4,
                        "SAME_DIRECTION",
                        new BigDecimal("0.67")),
                candidateCount,
                0,
                List.of(),
                Map.of("reason", "MANUAL_REVIEW_THRESHOLD_REACHED"));
        return DispatchDecision.fromAlgorithm(
                orderId,
                response,
                null,
                "[]",
                "{\"reason\":\"MANUAL_REVIEW_THRESHOLD_REACHED\"}",
                "0.1.0",
                "SYSTEM",
                "dispatch-orchestrator");
    }

    private RideOrder createOrder(String passengerName, int passengerCount, String requestedDepartureAt) {
        return RideOrder.pendingDispatch(new RideOrder.CreateOrderCommand(
                passengerName,
                "13800000000",
                passengerCount,
                "IMMEDIATE",
                new BigDecimal("120.1550000"),
                new BigDecimal("30.2741000"),
                new BigDecimal("120.1688000"),
                new BigDecimal("30.2799000"),
                BOARDING_STOP_ID,
                ALIGHTING_STOP_ID,
                OffsetDateTime.parse(requestedDepartureAt)));
    }
}
