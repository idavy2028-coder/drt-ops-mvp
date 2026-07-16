package com.idavy.drtops.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idavy.drtops.auth.JwtTokenService;
import com.idavy.drtops.auth.RoleCode;
import com.idavy.drtops.auth.UserAccount;
import com.idavy.drtops.auth.UserAccountRepository;
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
import com.idavy.drtops.domain.location.IdempotencyKeyLock;
import com.idavy.drtops.domain.location.LocationEventType;
import com.idavy.drtops.domain.location.ServiceAreaLocationChecker;
import com.idavy.drtops.domain.location.VehicleLocationEvent;
import com.idavy.drtops.domain.location.VehicleLocationEventRepository;
import com.idavy.drtops.domain.location.VehicleLocationMetrics;
import com.idavy.drtops.domain.order.OrderStatus;
import com.idavy.drtops.domain.order.RideOrderRepository;
import com.idavy.drtops.domain.task.TaskStatus;
import com.idavy.drtops.domain.task.TaskStop;
import com.idavy.drtops.domain.task.VehicleTask;
import com.idavy.drtops.domain.task.VehicleTaskRepository;
import com.idavy.drtops.integration.algorithm.AlgorithmClient;
import com.idavy.drtops.integration.algorithm.DispatchEvaluateRequest;
import com.idavy.drtops.integration.algorithm.DispatchEvaluateResponse;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:vehicle_location_flow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class VehicleLocationFlowIntegrationTest {

    private static final UUID RULE_SET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SERVICE_AREA_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID VEHICLE_ID = UUID.fromString("33333333-3333-3333-3333-333333333331");
    private static final UUID BOARDING_STOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555551");
    private static final UUID ALIGHTING_STOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555552");

    @Autowired MockMvc mockMvc;
    @Autowired RideOrderRepository rideOrderRepository;
    @Autowired VehicleTaskRepository vehicleTaskRepository;
    @Autowired DispatchDecisionRepository dispatchDecisionRepository;
    @Autowired DispatchRuleSetRepository ruleSetRepository;
    @Autowired ServiceAreaRepository serviceAreaRepository;
    @Autowired VirtualStopRepository virtualStopRepository;
    @Autowired VehicleRepository vehicleRepository;
    @Autowired DriverRepository driverRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired UserAccountRepository userAccountRepository;
    @Autowired VehicleLocationEventRepository eventRepository;
    @Autowired JwtTokenService jwtTokenService;
    @Autowired MeterRegistry meterRegistry;
    @Autowired VehicleLocationMetrics locationMetrics;
    @Autowired FakeAlgorithmClient algorithmClient;

    private String dispatcherToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        dispatchDecisionRepository.deleteAll();
        auditLogRepository.deleteAll();
        eventRepository.deleteAll();
        vehicleTaskRepository.deleteAll();
        rideOrderRepository.deleteAll();
        virtualStopRepository.deleteAll();
        serviceAreaRepository.deleteAll();
        vehicleRepository.deleteAll();
        driverRepository.deleteAll();
        ruleSetRepository.deleteAll();
        userAccountRepository.deleteAll();
        algorithmClient.reset();

        UserAccount dispatcher = UserAccount.create("location-dispatcher", "location-dispatcher", "not-used");
        dispatcher.assignRoles(Set.of(RoleCode.DISPATCHER, RoleCode.OPERATOR));
        dispatcherToken = jwtTokenService.issue(userAccountRepository.save(dispatcher)).value();
        UserAccount admin = UserAccount.create("location-admin", "location-admin", "not-used");
        admin.assignRoles(Set.of(RoleCode.SYSTEM_ADMIN));
        adminToken = jwtTokenService.issue(userAccountRepository.save(admin)).value();

        ruleSetRepository.save(DispatchRuleSet.defaultRules(RULE_SET_ID));
        serviceAreaRepository.save(ServiceArea.create(
                SERVICE_AREA_ID,
                "通渭试点服务区",
                "POLYGON((120.1400 30.2600,120.1900 30.2600,120.1900 30.2950,120.1400 30.2950,120.1400 30.2600))",
                "06:30",
                "19:00",
                RULE_SET_ID));
        virtualStopRepository.save(VirtualStop.create(
                BOARDING_STOP_ID, SERVICE_AREA_ID, "通渭县人民医院", "POINT(120.1550000 30.2741000)",
                600, true, false, "医院门口"));
        virtualStopRepository.save(VirtualStop.create(
                ALIGHTING_STOP_ID, SERVICE_AREA_ID, "通渭县客运站", "POINT(120.1688000 30.2799000)",
                600, false, true, "客运站落客区"));

        for (int index = 1; index <= 4; index++) {
            UUID vehicleId = index == 1 ? VEHICLE_ID : UUID.fromString("33333333-3333-3333-3333-33333333333" + index);
            vehicleRepository.save(Vehicle.create(
                    vehicleId, "甘JDRT-" + index, "Microbus", 8, "IDLE",
                    "POINT(120.1550000 30.2741000)", "通渭试点车队", true));
            driverRepository.save(Driver.create(
                    UUID.fromString("44444444-4444-4444-4444-44444444444" + index),
                    "试点驾驶员" + index,
                    "1390000200" + index,
                    "QUALIFIED",
                    OffsetDateTime.parse("2026-07-14T06:30:00+08:00"),
                    OffsetDateTime.parse("2026-07-14T19:00:00+08:00"),
                    "AVAILABLE",
                    "通渭试点车队"));
        }
    }

    @Test
    void verifiesVehicleLocationOperationsAcrossTaskHistorySnapshotAuditAndMetrics() throws Exception {
        algorithmClient.stubAutoDispatch(VEHICLE_ID);

        String createResponse = mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sampleDemand()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID orderId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(createResponse, "$.data.id"));

        mockMvc.perform(post("/api/orders/" + orderId + "/dispatch")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken))
                .andExpect(status().isOk());
        VehicleTask task = vehicleTaskRepository.findAll().getFirst();
        List<TaskStop> stops = vehicleTaskRepository.findWithStopsById(task.getId()).orElseThrow().getStops().stream()
                .sorted(Comparator.comparingInt(TaskStop::getSequenceNumber))
                .toList();

        UUID startKey = UUID.randomUUID();
        mockMvc.perform(post("/api/vehicle-tasks/" + task.getId() + "/start")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionRequest(null, startKey, 0, "120.1550000", "30.2741000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task.status").value("IN_PROGRESS"));
        mockMvc.perform(post("/api/vehicle-tasks/" + task.getId() + "/start")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionRequest(null, startKey, 0, "120.1550000", "30.2741000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(true));

        int actionSequence = 1;
        for (TaskStop stop : stops) {
            mockMvc.perform(post("/api/vehicle-tasks/" + task.getId() + "/stops/" + stop.getId() + "/arrive")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(actionRequest(stop.getVirtualStopId(), UUID.randomUUID(), actionSequence++,
                                    "120.1560000", "30.2750000")))
                    .andExpect(status().isOk());
            if ("BOARDING".equals(stop.getStopType())) {
                mockMvc.perform(post("/api/vehicle-tasks/" + task.getId() + "/stops/" + stop.getId() + "/board")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(actionRequest(stop.getVirtualStopId(), UUID.randomUUID(), actionSequence++,
                                        "120.1570000", "30.2760000")))
                        .andExpect(status().isOk());
            } else {
                mockMvc.perform(post("/api/vehicle-tasks/" + task.getId() + "/stops/" + stop.getId() + "/alight")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(actionRequest(stop.getVirtualStopId(), UUID.randomUUID(), actionSequence++,
                                        "120.1580000", "30.2770000")))
                        .andExpect(status().isOk());
            }
        }

        mockMvc.perform(post("/api/vehicle-tasks/" + task.getId() + "/complete")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionRequest(null, UUID.randomUUID(), actionSequence, "122.2000000", "30.3000000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.warnings[0]").value("OUTSIDE_SERVICE_AREA"));

        List<VehicleLocationEvent> taskEvents = eventRepository.findByVehicleTaskIdOrderByDriverReportedAtAsc(task.getId());
        assertThat(taskEvents).hasSize(6);
        assertThat(taskEvents).extracting(VehicleLocationEvent::getEventType).containsExactly(
                LocationEventType.TASK_STARTED,
                LocationEventType.PICKUP_ARRIVED,
                LocationEventType.PASSENGER_BOARDED,
                LocationEventType.DROPOFF_ARRIVED,
                LocationEventType.PASSENGER_ALIGHTED,
                LocationEventType.TASK_COMPLETED);
        assertThat(taskEvents).filteredOn(VehicleLocationEvent::isOutsideServiceArea).hasSize(1);

        VehicleLocationEvent latestTaskEvent = taskEvents.getLast();
        UUID oldEventId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(
                mockMvc.perform(post("/api/vehicles/" + VEHICLE_ID + "/location-reports")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(independentReportRequest(task.getId(), "2026-07-14T00:50:00Z", UUID.randomUUID())))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.snapshotApplied").value(false))
                        .andExpect(jsonPath("$.data.warnings[0]").value("HISTORICAL_EVENT_NOT_APPLIED_TO_SNAPSHOT"))
                        .andReturn().getResponse().getContentAsString(),
                "$.data.event.id"));

        mockMvc.perform(post("/api/vehicles/" + VEHICLE_ID + "/location-reports")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionRequest(task.getId(), oldEventId, UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.event.correctsEventId").value(oldEventId.toString()));

        assertThat(rideOrderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.COMPLETED);
        VehicleTask reloadedTask = vehicleTaskRepository.findById(task.getId()).orElseThrow();
        assertThat(reloadedTask.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(vehicleRepository.findById(VEHICLE_ID).orElseThrow().getCurrentLocationEventId())
                .isEqualTo(latestTaskEvent.getId());
        assertThat(eventRepository.findById(oldEventId)).isPresent();
        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(task.getId()))
                .extracting(log -> log.getAction())
                .contains("TASK_STARTED", "TASK_STOP_ARRIVED", "PASSENGER_BOARDED",
                        "PASSENGER_ALIGHTED", "TASK_COMPLETED");
        assertThat(meterRegistry.find("drt.vehicle.location.report.total").tag("result", "success").counter().count())
                .isEqualTo(8.0);
        assertThat(meterRegistry.find("drt.vehicle.location.report.total").tag("result", "replay").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.find("drt.vehicle.location.outside_area.total").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("drt.vehicle.location.correction.total").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("drt.vehicle.location.recording.delay").timer().count()).isEqualTo(8);

        mockMvc.perform(get("/api/vehicle-tasks/" + task.getId() + "/location-events")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(8));
        assertThat(meterRegistry.find("drt.vehicle.location.query.duration").timer().count()).isEqualTo(1);
        locationMetrics.updateMissingTaskNodes(0);
        assertThat(meterRegistry.find("drt.vehicle.location.missing_task_nodes").gauge().value()).isZero();
    }

    private String sampleDemand() {
        return """
                {
                  "passengerName": "李四",
                  "passengerPhone": "13800000001",
                  "passengerCount": 1,
                  "requestType": "IMMEDIATE",
                  "originLng": 120.1550,
                  "originLat": 30.2741,
                  "destinationLng": 120.1688,
                  "destinationLat": 30.2799,
                  "requestedDepartureAt": "2026-07-14T09:00:00+08:00"
                }
                """;
    }

    private String actionRequest(UUID virtualStopId, UUID idempotencyKey, int sequence, String longitude, String latitude) {
        String virtualStop = virtualStopId == null ? "null" : "\"" + virtualStopId + "\"";
        return """
                {
                  "locationReport": {
                    "longitude": %s,
                    "latitude": %s,
                    "standardizedAddress": "通渭县任务节点 %d",
                    "driverReportedAt": "2026-07-14T01:%02d:00Z",
                    "virtualStopId": %s,
                    "note": "电话反馈后由调度员录入",
                    "idempotencyKey": "%s"
                  }
                }
                """.formatted(longitude, latitude, sequence, sequence, virtualStop, idempotencyKey);
    }

    private String independentReportRequest(UUID taskId, String reportedAt, UUID idempotencyKey) {
        return """
                {
                  "vehicleTaskId": "%s",
                  "eventType": "TASK_STARTED",
                  "longitude": 120.1500000,
                  "latitude": 30.2700000,
                  "standardizedAddress": "通渭县历史补报",
                  "driverReportedAt": "%s",
                  "note": "补录较早反馈",
                  "idempotencyKey": "%s"
                }
                """.formatted(taskId, reportedAt, idempotencyKey);
    }

    private String correctionRequest(UUID taskId, UUID correctsEventId, UUID idempotencyKey) {
        return """
                {
                  "vehicleTaskId": "%s",
                  "eventType": "MANUAL_CORRECTION",
                  "longitude": 120.1510000,
                  "latitude": 30.2710000,
                  "standardizedAddress": "通渭县管理员修正点",
                  "driverReportedAt": "2026-07-14T01:04:30Z",
                  "note": "管理员修正位置",
                  "correctionReason": "调度复核发现位置偏移",
                  "correctsEventId": "%s",
                  "idempotencyKey": "%s"
                }
                """.formatted(taskId, correctsEventId, idempotencyKey);
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
        IdempotencyKeyLock idempotencyKeyLock() {
            return idempotencyKey -> { };
        }

        @Bean
        @Primary
        ServiceAreaLocationChecker serviceAreaLocationChecker() {
            return (longitude, latitude) -> longitude.compareTo(new BigDecimal("122.0000000")) < 0;
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
                            null, vehicleId, new BigDecimal("88.50"), 6, 3, "SAME_DIRECTION", new BigDecimal("0.67")),
                    1,
                    0,
                    List.of(),
                    Map.of("reason", "车辆位置全链路派单"));
        }
    }
}
