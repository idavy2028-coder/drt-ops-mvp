package com.idavy.drtops.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idavy.drtops.domain.area.ServiceArea;
import com.idavy.drtops.domain.area.ServiceAreaRepository;
import com.idavy.drtops.domain.area.VirtualStop;
import com.idavy.drtops.domain.area.VirtualStopRepository;
import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.domain.dispatch.DispatchDecisionRepository;
import com.idavy.drtops.domain.dispatch.DispatchDecisionType;
import com.idavy.drtops.domain.dispatch.DispatchRuleSet;
import com.idavy.drtops.domain.dispatch.DispatchRuleSetRepository;
import com.idavy.drtops.domain.fleet.Driver;
import com.idavy.drtops.domain.fleet.DriverRepository;
import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import com.idavy.drtops.domain.order.OrderStatus;
import com.idavy.drtops.domain.order.RideOrderRepository;
import com.idavy.drtops.domain.task.TaskStop;
import com.idavy.drtops.domain.task.TaskStatus;
import com.idavy.drtops.domain.task.VehicleTask;
import com.idavy.drtops.domain.task.VehicleTaskRepository;
import com.idavy.drtops.integration.algorithm.AlgorithmClient;
import com.idavy.drtops.integration.algorithm.DispatchEvaluateRequest;
import com.idavy.drtops.integration.algorithm.DispatchEvaluateResponse;
import com.jayway.jsonpath.JsonPath;
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
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dispatch_flow_e2e;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
@WithMockUser(authorities = {"ORDER_CREATE", "DISPATCH_EXECUTE", "TASK_READ", "TASK_EXECUTE"})
class DispatchFlowIntegrationTest {

    private static final UUID RULE_SET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SERVICE_AREA_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID VEHICLE_ID = UUID.fromString("33333333-3333-3333-3333-333333333331");
    private static final UUID DRIVER_ID = UUID.fromString("44444444-4444-4444-4444-444444444441");
    private static final UUID BOARDING_STOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555551");
    private static final UUID ALIGHTING_STOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555552");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    RideOrderRepository rideOrderRepository;

    @Autowired
    VehicleTaskRepository vehicleTaskRepository;

    @Autowired
    DispatchDecisionRepository dispatchDecisionRepository;

    @Autowired
    DispatchRuleSetRepository ruleSetRepository;

    @Autowired
    ServiceAreaRepository serviceAreaRepository;

    @Autowired
    VirtualStopRepository virtualStopRepository;

    @Autowired
    VehicleRepository vehicleRepository;

    @Autowired
    DriverRepository driverRepository;

    @Autowired
    AuditLogRepository auditLogRepository;

    @Autowired
    FakeAlgorithmClient algorithmClient;

    @BeforeEach
    void setUp() {
        dispatchDecisionRepository.deleteAll();
        auditLogRepository.deleteAll();
        vehicleTaskRepository.deleteAll();
        rideOrderRepository.deleteAll();
        virtualStopRepository.deleteAll();
        serviceAreaRepository.deleteAll();
        vehicleRepository.deleteAll();
        driverRepository.deleteAll();
        ruleSetRepository.deleteAll();
        algorithmClient.reset();

        ruleSetRepository.save(DispatchRuleSet.defaultRules(RULE_SET_ID));
        serviceAreaRepository.save(ServiceArea.create(
                SERVICE_AREA_ID,
                "演示服务区",
                "POLYGON((120.1400 30.2600,120.1900 30.2600,120.1900 30.2950,120.1400 30.2950,120.1400 30.2600))",
                "06:00",
                "22:00",
                RULE_SET_ID));
        virtualStopRepository.save(VirtualStop.create(
                BOARDING_STOP_ID,
                SERVICE_AREA_ID,
                "演示上车点",
                "POINT(120.1550000 30.2741000)",
                600,
                true,
                false,
                "路侧安全候车"));
        virtualStopRepository.save(VirtualStop.create(
                ALIGHTING_STOP_ID,
                SERVICE_AREA_ID,
                "演示下车点",
                "POINT(120.1688000 30.2799000)",
                600,
                false,
                true,
                "地铁口附近落客"));
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
    void demandToDispatchToTaskCompletionFlow() throws Exception {
        algorithmClient.stubAutoDispatch(VEHICLE_ID);

        String createResponse = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sampleDemand()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING_DISPATCH"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID orderId = UUID.fromString(JsonPath.read(createResponse, "$.data.id"));

        mockMvc.perform(post("/api/orders/" + orderId + "/dispatch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decision").value("AUTO_DISPATCH"));
        mockMvc.perform(get("/api/vehicle-tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("DISPATCHED"));

        VehicleTask task = vehicleTaskRepository.findAll().getFirst();
        List<TaskStop> stops = vehicleTaskRepository.findWithStopsById(task.getId()).orElseThrow().getStops();
        mockMvc.perform(post("/api/vehicle-tasks/" + task.getId() + "/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
        for (TaskStop stop : stops) {
            mockMvc.perform(post("/api/vehicle-tasks/" + task.getId() + "/stops/" + stop.getId() + "/arrive"))
                    .andExpect(status().isOk());
            if ("BOARDING".equals(stop.getStopType())) {
                mockMvc.perform(post("/api/vehicle-tasks/" + task.getId() + "/stops/" + stop.getId() + "/board"))
                        .andExpect(status().isOk());
            } else {
                mockMvc.perform(post("/api/vehicle-tasks/" + task.getId() + "/stops/" + stop.getId() + "/alight"))
                        .andExpect(status().isOk());
            }
        }
        mockMvc.perform(post("/api/vehicle-tasks/" + task.getId() + "/complete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        assertThat(rideOrderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(vehicleTaskRepository.findById(task.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.COMPLETED);
    }

    private String sampleDemand() {
        return """
                {
                  "passengerName": "张三",
                  "passengerPhone": "13800000000",
                  "passengerCount": 1,
                  "requestType": "IMMEDIATE",
                  "originLng": 120.1550,
                  "originLat": 30.2741,
                  "destinationLng": 120.1688,
                  "destinationLat": 30.2799,
                  "requestedDepartureAt": "2026-07-08T10:30:00+08:00"
                }
                """;
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

        @Override
        public DispatchEvaluateResponse evaluate(DispatchEvaluateRequest request) {
            return nextResponse;
        }

        void reset() {
            nextResponse = null;
        }

        void stubAutoDispatch(UUID vehicleId) {
            nextResponse = new DispatchEvaluateResponse(
                    DispatchDecisionType.AUTO_DISPATCH,
                    new DispatchEvaluateResponse.BestPlan(
                            null,
                            vehicleId,
                            new BigDecimal("88.50"),
                            6,
                            3,
                            "SAME_DIRECTION",
                            new BigDecimal("0.67")),
                    1,
                    0,
                    List.of(),
                    Map.of("reason", "演示自动派发"));
        }
    }
}
