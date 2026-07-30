package com.idavy.drtops.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idavy.drtops.auth.JwtTokenService;
import com.idavy.drtops.auth.RoleCode;
import com.idavy.drtops.auth.UserAccount;
import com.idavy.drtops.auth.UserAccountRepository;
import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.domain.fleet.Driver;
import com.idavy.drtops.domain.fleet.DriverRepository;
import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import com.idavy.drtops.domain.task.TaskStop;
import com.idavy.drtops.domain.task.TaskStatus;
import com.idavy.drtops.domain.task.VehicleTask;
import com.idavy.drtops.domain.task.VehicleTaskRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:order_exception_api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class OrderExceptionApiTest {

    private static final UUID BOARDING_STOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555551");
    private static final UUID ALIGHTING_STOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555552");
    private static final UUID VEHICLE_ID = UUID.fromString("33333333-3333-3333-3333-333333333331");
    private static final UUID DRIVER_ID = UUID.fromString("44444444-4444-4444-4444-444444444441");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    RideOrderRepository rideOrderRepository;

    @Autowired
    AuditLogRepository auditLogRepository;

    @Autowired
    VehicleTaskRepository vehicleTaskRepository;

    @Autowired
    VehicleRepository vehicleRepository;

    @Autowired
    DriverRepository driverRepository;

    @Autowired
    UserAccountRepository userAccountRepository;

    @Autowired
    JwtTokenService jwtTokenService;

    private String dispatcherToken;
    private UUID dispatcherId;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        vehicleTaskRepository.deleteAll();
        rideOrderRepository.deleteAll();
        vehicleRepository.deleteAll();
        driverRepository.deleteAll();
        userAccountRepository.deleteAll();

        UserAccount dispatcher = UserAccount.create("dispatcher01", "dispatcher01", "not-used-in-order-exception-test");
        dispatcher.assignRoles(Set.of(RoleCode.DISPATCHER));
        dispatcher = userAccountRepository.save(dispatcher);
        dispatcherId = dispatcher.getId();
        dispatcherToken = jwtTokenService.issue(dispatcher).value();

        vehicleRepository.save(Vehicle.create(
                VEHICLE_ID, "甘J-NOSHOW", "MINIBUS", 8, "DISPATCHED",
                "POINT(105.240000 35.210000)", "测试车队", true));
        driverRepository.save(Driver.create(
                DRIVER_ID, "爽约测试司机", "13900005001", "QUALIFIED",
                OffsetDateTime.parse("2026-07-30T06:30:00+08:00"),
                OffsetDateTime.parse("2026-07-30T19:00:00+08:00"),
                "BUSY", "测试车队"));
    }

    @Test
    void cancelOrderMarksCancelledAndAuditsReason() throws Exception {
        UUID orderId = createPendingOrder();

        mockMvc.perform(post("/api/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"乘客取消\"}"))
                .andExpect(status().isOk());

        assertThat(rideOrderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
        assertThat(auditLogRepository.findByEntityId(orderId))
                .anyMatch(log -> log.getAction().equals("ORDER_CANCELLED")
                        && log.getActorType().equals("USER")
                        && log.getActorId().equals(dispatcherId.toString()));
    }

    @Test
    void noShowClosesOrderAsExceptionAndAuditsReason() throws Exception {
        UUID orderId = createConfirmedOrder();

        mockMvc.perform(post("/api/orders/" + orderId + "/no-show")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"乘客未上车\"}"))
                .andExpect(status().isOk());

        assertThat(rideOrderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.EXCEPTION_CLOSED);
        assertThat(auditLogRepository.findByEntityId(orderId))
                .anyMatch(log -> log.getAction().equals("ORDER_NO_SHOW")
                        && log.getActorType().equals("USER")
                        && log.getActorId().equals(dispatcherId.toString()));
    }

    @Test
    void noShowCancelsSingleOrderTaskAndReleasesResources() throws Exception {
        UUID orderId = createConfirmedOrder();
        UUID taskId = createTask(orderId);

        noShow(orderId);

        assertThat(vehicleTaskRepository.findById(taskId).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.CANCELLED);
        assertThat(vehicleRepository.findById(VEHICLE_ID).orElseThrow().getCurrentStatus())
                .isEqualTo("IDLE");
        assertThat(driverRepository.findById(DRIVER_ID).orElseThrow().getCurrentStatus())
                .isEqualTo("AVAILABLE");
        assertThat(auditLogRepository.findByEntityId(taskId))
                .anyMatch(log -> log.getAction().equals("TASK_CANCELLED_NO_SHOW"));
    }

    @Test
    void noShowCancelsOnlyMatchingStopsInSharedTask() throws Exception {
        UUID noShowOrderId = createConfirmedOrder();
        UUID remainingOrderId = createConfirmedOrder();
        UUID taskId = createTask(noShowOrderId, remainingOrderId);

        noShow(noShowOrderId);

        VehicleTask task = vehicleTaskRepository.findWithStopsById(taskId).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.DISPATCHED);
        assertThat(task.getStops())
                .filteredOn(stop -> noShowOrderId.equals(stop.getRideOrderId()))
                .extracting(TaskStop::getStatus)
                .containsOnly("CANCELLED");
        assertThat(task.getStops())
                .filteredOn(stop -> remainingOrderId.equals(stop.getRideOrderId()))
                .extracting(TaskStop::getStatus)
                .containsOnly("PLANNED");
        assertThat(vehicleRepository.findById(VEHICLE_ID).orElseThrow().getCurrentStatus())
                .isEqualTo("DISPATCHED");
        assertThat(driverRepository.findById(DRIVER_ID).orElseThrow().getCurrentStatus())
                .isEqualTo("BUSY");
    }

    private UUID createPendingOrder() {
        return rideOrderRepository.save(newOrder()).getId();
    }

    private UUID createConfirmedOrder() {
        RideOrder order = newOrder();
        order.confirm(new RideOrder.OrderPromise(
                OffsetDateTime.parse("2026-07-08T02:36:00Z"),
                OffsetDateTime.parse("2026-07-08T02:50:00Z")));
        return rideOrderRepository.save(order).getId();
    }

    private UUID createTask(UUID... orderIds) {
        VehicleTask task = VehicleTask.pendingDeparture(
                VEHICLE_ID, DRIVER_ID, OffsetDateTime.parse("2026-07-30T09:00:00+08:00"), "TEST");
        int sequence = 1;
        for (UUID orderId : orderIds) {
            task.addStop(TaskStop.planned(
                    BOARDING_STOP_ID, orderId, sequence++, "BOARDING",
                    OffsetDateTime.parse("2026-07-30T09:05:00+08:00").plusMinutes(sequence)));
            task.addStop(TaskStop.planned(
                    ALIGHTING_STOP_ID, orderId, sequence++, "ALIGHTING",
                    OffsetDateTime.parse("2026-07-30T09:15:00+08:00").plusMinutes(sequence)));
        }
        task.dispatch();
        return vehicleTaskRepository.save(task).getId();
    }

    private void noShow(UUID orderId) throws Exception {
        mockMvc.perform(post("/api/orders/" + orderId + "/no-show")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"乘客未上车\"}"))
                .andExpect(status().isOk());
    }

    private RideOrder newOrder() {
        return RideOrder.pendingDispatch(new RideOrder.CreateOrderCommand(
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
    }
}
