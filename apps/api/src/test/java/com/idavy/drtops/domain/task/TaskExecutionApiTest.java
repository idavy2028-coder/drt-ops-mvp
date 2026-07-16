package com.idavy.drtops.domain.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idavy.drtops.auth.JwtTokenService;
import com.idavy.drtops.auth.RoleCode;
import com.idavy.drtops.auth.UserAccount;
import com.idavy.drtops.auth.UserAccountRepository;
import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import com.idavy.drtops.domain.location.LocationEventType;
import com.idavy.drtops.domain.location.IdempotencyKeyLock;
import com.idavy.drtops.domain.location.ServiceAreaLocationChecker;
import com.idavy.drtops.domain.location.VehicleLocationEventRepository;
import com.idavy.drtops.domain.order.OrderStatus;
import com.idavy.drtops.domain.order.RideOrder;
import com.idavy.drtops.domain.order.RideOrderRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:task_execution_api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
@Import(TaskExecutionApiTest.LocationTestConfiguration.class)
class TaskExecutionApiTest {

    private static final UUID VEHICLE_ID = UUID.fromString("33333333-3333-3333-3333-333333333331");
    private static final UUID DRIVER_ID = UUID.fromString("44444444-4444-4444-4444-444444444441");
    private static final UUID BOARDING_STOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555551");
    private static final UUID ALIGHTING_STOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555552");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    VehicleTaskRepository vehicleTaskRepository;

    @Autowired
    RideOrderRepository rideOrderRepository;

    @Autowired
    AuditLogRepository auditLogRepository;

    @Autowired
    VehicleLocationEventRepository vehicleLocationEventRepository;

    @Autowired
    VehicleRepository vehicleRepository;

    @Autowired
    UserAccountRepository userAccountRepository;

    @Autowired
    JwtTokenService jwtTokenService;

    private String dispatcherToken;
    private UUID dispatcherId;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        vehicleLocationEventRepository.deleteAll();
        vehicleTaskRepository.deleteAll();
        rideOrderRepository.deleteAll();
        vehicleRepository.deleteAll();
        userAccountRepository.deleteAll();

        vehicleRepository.save(Vehicle.create(
                VEHICLE_ID,
                "浙A00001",
                "MINIBUS",
                8,
                "AVAILABLE",
                "POINT(120.1550 30.2741)",
                "测试车队",
                true));

        UserAccount dispatcher = UserAccount.create("dispatcher01", "dispatcher01", "not-used-in-task-execution-test");
        dispatcher.assignRoles(Set.of(RoleCode.DISPATCHER));
        dispatcher = userAccountRepository.save(dispatcher);
        dispatcherId = dispatcher.getId();
        dispatcherToken = jwtTokenService.issue(dispatcher).value();
    }

    @Test
    void taskCanMoveThroughStartArriveBoardAlightComplete() throws Exception {
        UUID taskId = createConfirmedTaskWithOneOrder();
        String startRequest = actionRequest(UUID.randomUUID(), null, 0);

        mockMvc.perform(post("/api/vehicle-tasks/" + taskId + "/start")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.locationEvent.eventType").value("TASK_STARTED"))
                .andExpect(jsonPath("$.data.replayed").value(false));

        mockMvc.perform(post("/api/vehicle-tasks/" + taskId + "/start")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.locationEvent.eventType").value("TASK_STARTED"))
                .andExpect(jsonPath("$.data.replayed").value(true));
        assertThat(vehicleLocationEventRepository.count()).isOne();
        assertThat(auditLogRepository.findByEntityId(taskId)).hasSize(1);

        UUID boardingTaskStopId = firstTaskStop(taskId, "BOARDING");
        mockMvc.perform(post("/api/vehicle-tasks/" + taskId + "/stops/" + boardingTaskStopId + "/arrive")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionRequest(UUID.randomUUID(), BOARDING_STOP_ID, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.locationEvent.eventType").value("PICKUP_ARRIVED"))
                .andExpect(jsonPath("$.data.locationEvent.taskStopId").value(boardingTaskStopId.toString()))
                .andExpect(jsonPath("$.data.locationEvent.virtualStopId").value(BOARDING_STOP_ID.toString()));
        mockMvc.perform(post("/api/vehicle-tasks/" + taskId + "/stops/" + boardingTaskStopId + "/board")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionRequest(UUID.randomUUID(), BOARDING_STOP_ID, 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.locationEvent.eventType").value("PASSENGER_BOARDED"));
        UUID alightingTaskStopId = firstTaskStop(taskId, "ALIGHTING");
        mockMvc.perform(post("/api/vehicle-tasks/" + taskId + "/stops/" + alightingTaskStopId + "/arrive")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionRequest(UUID.randomUUID(), ALIGHTING_STOP_ID, 3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.locationEvent.eventType").value("DROPOFF_ARRIVED"));
        mockMvc.perform(post("/api/vehicle-tasks/" + taskId + "/stops/" + alightingTaskStopId + "/alight")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionRequest(UUID.randomUUID(), ALIGHTING_STOP_ID, 4)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.locationEvent.eventType").value("PASSENGER_ALIGHTED"));
        mockMvc.perform(post("/api/vehicle-tasks/" + taskId + "/complete")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionRequest(UUID.randomUUID(), null, 5)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.locationEvent.eventType").value("TASK_COMPLETED"));

        VehicleTask task = vehicleTaskRepository.findById(taskId).orElseThrow();
        RideOrder order = rideOrderRepository.findAll().getFirst();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(vehicleLocationEventRepository.findByVehicleTaskIdOrderByDriverReportedAtAsc(taskId))
                .extracting(event -> event.getEventType())
                .containsExactly(
                        LocationEventType.TASK_STARTED,
                        LocationEventType.PICKUP_ARRIVED,
                        LocationEventType.PASSENGER_BOARDED,
                        LocationEventType.DROPOFF_ARRIVED,
                        LocationEventType.PASSENGER_ALIGHTED,
                        LocationEventType.TASK_COMPLETED);
        assertThat(auditLogRepository.findByEntityId(taskId))
                .hasSize(6)
                .allMatch(log -> log.getActorType().equals("USER")
                        && log.getActorId().equals(dispatcherId.toString())
                        && log.getMetadataJson().contains("locationEventId"))
                .noneMatch(log -> List.of("VEHICLE_LOCATION_REPORTED", "VEHICLE_LOCATION_CORRECTED")
                        .contains(log.getAction()));
    }

    @Test
    void rejectsVirtualStopThatDoesNotMatchTaskStop() throws Exception {
        UUID taskId = createConfirmedTaskWithOneOrder();
        performAction("/api/vehicle-tasks/" + taskId + "/start", actionRequest(UUID.randomUUID(), null, 0))
                .andExpect(status().isOk());
        UUID boardingTaskStopId = firstTaskStop(taskId, "BOARDING");

        performAction(
                        "/api/vehicle-tasks/" + taskId + "/stops/" + boardingTaskStopId + "/arrive",
                        actionRequest(UUID.randomUUID(), ALIGHTING_STOP_ID, 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.message").value("虚拟站点与当前任务节点不一致"));

        assertThat(vehicleLocationEventRepository.count()).isOne();
        assertThat(auditLogRepository.findByEntityId(taskId)).hasSize(1);
        assertThat(firstTaskStopEntity(taskId, "BOARDING").getStatus()).isEqualTo("PLANNED");
    }

    @Test
    void vehicleFailureClosesTaskAsExceptionAndAuditsReason() throws Exception {
        UUID taskId = createConfirmedTaskWithOneOrder();

        mockMvc.perform(post("/api/vehicle-tasks/" + taskId + "/exception")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"车辆故障\"}"))
                .andExpect(status().isOk());

        VehicleTask task = vehicleTaskRepository.findById(taskId).orElseThrow();
        RideOrder order = rideOrderRepository.findAll().getFirst();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.EXCEPTION);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.EXCEPTION_CLOSED);
        assertThat(auditLogRepository.findByEntityId(taskId))
                .anyMatch(log -> log.getAction().equals("TASK_EXCEPTION"));
    }

    @Test
    void severeDelayClosesTaskAsExceptionAndAuditsDelay() throws Exception {
        UUID taskId = createConfirmedTaskWithOneOrder();

        mockMvc.perform(post("/api/vehicle-tasks/" + taskId + "/delay")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"预计到达严重延误\"}"))
                .andExpect(status().isOk());

        VehicleTask task = vehicleTaskRepository.findById(taskId).orElseThrow();
        RideOrder order = rideOrderRepository.findAll().getFirst();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.EXCEPTION);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.EXCEPTION_CLOSED);
        assertThat(auditLogRepository.findByEntityId(taskId))
                .anyMatch(log -> log.getAction().equals("TASK_SEVERE_DELAY")
                        && log.getReason().equals("预计到达严重延误"));
    }

    private UUID createConfirmedTaskWithOneOrder() {
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
        order.confirm(new RideOrder.OrderPromise(
                OffsetDateTime.parse("2026-07-08T02:36:00Z"),
                OffsetDateTime.parse("2026-07-08T02:50:00Z")));
        RideOrder savedOrder = rideOrderRepository.save(order);

        VehicleTask task = VehicleTask.pendingDeparture(
                VEHICLE_ID,
                DRIVER_ID,
                OffsetDateTime.parse("2026-07-08T02:36:00Z"),
                "ALGORITHM");
        task.addStop(TaskStop.planned(
                BOARDING_STOP_ID,
                savedOrder.getId(),
                1,
                "BOARDING",
                OffsetDateTime.parse("2026-07-08T02:36:00Z")));
        task.addStop(TaskStop.planned(
                ALIGHTING_STOP_ID,
                savedOrder.getId(),
                2,
                "ALIGHTING",
                OffsetDateTime.parse("2026-07-08T02:50:00Z")));
        return vehicleTaskRepository.save(task).getId();
    }

    private UUID firstTaskStop(UUID taskId, String stopType) {
        return firstTaskStopEntity(taskId, stopType).getId();
    }

    private TaskStop firstTaskStopEntity(UUID taskId, String stopType) {
        return vehicleTaskRepository.findWithStopsById(taskId).orElseThrow().getStops().stream()
                .filter(stop -> stopType.equals(stop.getStopType()))
                .findFirst()
                .orElseThrow();
    }

    private org.springframework.test.web.servlet.ResultActions performAction(String path, String request)
            throws Exception {
        return mockMvc.perform(post(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request));
    }

    private String actionRequest(UUID idempotencyKey, UUID virtualStopId, int minute) {
        String virtualStop = virtualStopId == null ? "null" : "\"" + virtualStopId + "\"";
        return """
                {
                  "locationReport": {
                    "longitude": 120.1550000,
                    "latitude": 30.2741000,
                    "standardizedAddress": "浙江省杭州市测试道路 %d 号",
                    "driverReportedAt": "2026-07-14T01:%02d:00Z",
                    "virtualStopId": %s,
                    "note": "任务动作位置",
                    "idempotencyKey": "%s"
                  }
                }
                """.formatted(minute + 1, minute, virtualStop, idempotencyKey);
    }

    @TestConfiguration
    static class LocationTestConfiguration {

        @Bean
        @Primary
        IdempotencyKeyLock idempotencyKeyLock() {
            return idempotencyKey -> { };
        }

        @Bean
        @Primary
        ServiceAreaLocationChecker serviceAreaLocationChecker() {
            return (longitude, latitude) -> true;
        }
    }
}
