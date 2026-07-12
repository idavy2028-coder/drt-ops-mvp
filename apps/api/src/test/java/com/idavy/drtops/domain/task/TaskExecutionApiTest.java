package com.idavy.drtops.domain.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.domain.order.OrderStatus;
import com.idavy.drtops.domain.order.RideOrder;
import com.idavy.drtops.domain.order.RideOrderRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
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
@WithMockUser(authorities = "TASK_EXECUTE")
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

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        vehicleTaskRepository.deleteAll();
        rideOrderRepository.deleteAll();
    }

    @Test
    void taskCanMoveThroughStartArriveBoardAlightComplete() throws Exception {
        UUID taskId = createConfirmedTaskWithOneOrder();

        mockMvc.perform(post("/api/vehicle-tasks/" + taskId + "/start"))
                .andExpect(status().isOk());
        UUID boardingTaskStopId = firstTaskStop(taskId, "BOARDING");
        mockMvc.perform(post("/api/vehicle-tasks/" + taskId + "/stops/" + boardingTaskStopId + "/arrive"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/vehicle-tasks/" + taskId + "/stops/" + boardingTaskStopId + "/board"))
                .andExpect(status().isOk());
        UUID alightingTaskStopId = firstTaskStop(taskId, "ALIGHTING");
        mockMvc.perform(post("/api/vehicle-tasks/" + taskId + "/stops/" + alightingTaskStopId + "/arrive"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/vehicle-tasks/" + taskId + "/stops/" + alightingTaskStopId + "/alight"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/vehicle-tasks/" + taskId + "/complete"))
                .andExpect(status().isOk());

        VehicleTask task = vehicleTaskRepository.findById(taskId).orElseThrow();
        RideOrder order = rideOrderRepository.findAll().getFirst();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    void vehicleFailureClosesTaskAsExceptionAndAuditsReason() throws Exception {
        UUID taskId = createConfirmedTaskWithOneOrder();

        mockMvc.perform(post("/api/vehicle-tasks/" + taskId + "/exception")
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
        return vehicleTaskRepository.findWithStopsById(taskId).orElseThrow().getStops().stream()
                .filter(stop -> stopType.equals(stop.getStopType()))
                .findFirst()
                .orElseThrow()
                .getId();
    }
}
