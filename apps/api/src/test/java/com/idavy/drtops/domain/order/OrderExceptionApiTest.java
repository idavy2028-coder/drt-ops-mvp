package com.idavy.drtops.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import com.idavy.drtops.domain.location.LocationEventType;
import com.idavy.drtops.domain.location.LocationSource;
import com.idavy.drtops.domain.location.VehicleLocationEvent;
import com.idavy.drtops.domain.location.VehicleLocationEventRepository;
import com.idavy.drtops.domain.task.TaskStop;
import com.idavy.drtops.domain.task.TaskStatus;
import com.idavy.drtops.domain.task.VehicleTask;
import com.idavy.drtops.domain.task.VehicleTaskRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;

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
    VehicleLocationEventRepository locationEventRepository;

    @Autowired
    UserAccountRepository userAccountRepository;

    @Autowired
    JwtTokenService jwtTokenService;

    private String dispatcherToken;
    private UUID dispatcherId;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        locationEventRepository.deleteAll();
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
        UUID taskId = createTask(orderId);
        prepareEligibleNoShow(orderId, taskId, 301);

        mockMvc.perform(post("/api/orders/" + orderId + "/no-show")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"乘客未上车","idempotencyKey":"22222222-2222-2222-2222-222222222222"}
                                """))
                .andExpect(status().isOk());

        assertThat(rideOrderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.EXCEPTION_CLOSED);
        assertThat(auditLogRepository.findByEntityId(orderId))
                .anyMatch(log -> log.getAction().equals("ORDER_NO_SHOW")
                        && log.getActorType().equals("USER")
                        && log.getActorId().equals(dispatcherId.toString())
                        && log.getMetadataJson().contains("\"waitedSeconds\":")
                        && log.getMetadataJson().contains("\"cancelledStopCount\":2")
                        && log.getMetadataJson().contains("\"resourcesReleased\":true"));
    }

    @Test
    void noShowCancelsSingleOrderTaskAndReleasesResources() throws Exception {
        UUID orderId = createConfirmedOrder();
        UUID taskId = createTask(orderId);
        prepareEligibleNoShow(orderId, taskId, 301);

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
        prepareEligibleNoShow(noShowOrderId, taskId, 301);

        noShow(noShowOrderId);

        VehicleTask task = vehicleTaskRepository.findWithStopsById(taskId).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
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

    @Test
    void noShowBeforeFiveMinutesReturnsConflictWithoutClosingOrder() throws Exception {
        UUID orderId = createConfirmedOrder();
        UUID taskId = createTask(orderId);
        prepareEligibleNoShow(orderId, taskId, 299);

        mockMvc.perform(noShowRequest(orderId, UUID.fromString("44444444-4444-4444-4444-444444444444")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.code").value("NO_SHOW_WAITING_PERIOD_NOT_ELAPSED"));

        assertThat(rideOrderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.IN_PROGRESS);
    }

    @Test
    void repeatedNoShowWithSameIdempotencyKeyDoesNotDuplicateAudit() throws Exception {
        UUID orderId = createConfirmedOrder();
        UUID taskId = createTask(orderId);
        prepareEligibleNoShow(orderId, taskId, 301);
        UUID idempotencyKey = UUID.fromString("55555555-5555-5555-5555-555555555555");

        mockMvc.perform(noShowRequest(orderId, idempotencyKey)).andExpect(status().isOk());
        mockMvc.perform(noShowRequest(orderId, idempotencyKey)).andExpect(status().isOk());

        assertThat(auditLogRepository.findByEntityId(orderId))
                .filteredOn(log -> log.getAction().equals("ORDER_NO_SHOW"))
                .hasSize(1);
    }

    @Test
    void concurrentNoShowRequestsAllowOnlyOneSuccessfulTransition() throws Exception {
        UUID orderId = createConfirmedOrder();
        UUID taskId = createTask(orderId);
        prepareEligibleNoShow(orderId, taskId, 301);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Integer> first = executor.submit(() -> performNoShowAfter(start, orderId, UUID.randomUUID()));
            Future<Integer> second = executor.submit(() -> performNoShowAfter(start, orderId, UUID.randomUUID()));
            start.countDown();

            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(200, 409);
            assertThat(auditLogRepository.findByEntityId(orderId))
                    .filteredOn(log -> log.getAction().equals("ORDER_NO_SHOW"))
                    .hasSize(1);
        } finally {
            executor.shutdownNow();
        }
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
        mockMvc.perform(noShowRequest(orderId, UUID.randomUUID()))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder noShowRequest(
            UUID orderId, UUID idempotencyKey) {
        return post("/api/orders/" + orderId + "/no-show")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"reason":"乘客未上车","idempotencyKey":"%s"}
                        """.formatted(idempotencyKey));
    }

    private int performNoShowAfter(CountDownLatch start, UUID orderId, UUID idempotencyKey) throws Exception {
        start.await();
        return mockMvc.perform(noShowRequest(orderId, idempotencyKey))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private void prepareEligibleNoShow(UUID orderId, UUID taskId, long arrivedSecondsAgo) {
        RideOrder order = rideOrderRepository.findById(orderId).orElseThrow();
        order.startExecution();
        rideOrderRepository.save(order);

        VehicleTask task = vehicleTaskRepository.findWithStopsById(taskId).orElseThrow();
        task.startExecution();
        TaskStop pickup = task.getStops().stream()
                .filter(stop -> orderId.equals(stop.getRideOrderId()))
                .filter(stop -> "BOARDING".equals(stop.getStopType()))
                .findFirst()
                .orElseThrow();
        pickup.arrive();
        OffsetDateTime arrivedAt = OffsetDateTime.now().minusSeconds(arrivedSecondsAgo);
        ReflectionTestUtils.setField(pickup, "actualArrivalAt", arrivedAt);
        vehicleTaskRepository.save(task);

        locationEventRepository.save(VehicleLocationEvent.record(
                VEHICLE_ID,
                taskId,
                pickup.getId(),
                pickup.getVirtualStopId(),
                LocationEventType.PICKUP_ARRIVED,
                LocationSource.MANUAL_DISPATCHER,
                "POINT(105.240000 35.210000)",
                new BigDecimal("105.2400000"),
                new BigDecimal("35.2100000"),
                "GCJ02",
                "测试上车点",
                arrivedAt,
                arrivedAt,
                dispatcherId,
                "测试到站",
                null,
                null,
                UUID.randomUUID(),
                UUID.randomUUID().toString().replace("-", ""),
                true,
                false));
    }

    @Test
    void noShowBeforeTaskStartsReturnsConflictWithoutMutation() throws Exception {
        UUID orderId = createConfirmedOrder();
        UUID taskId = createTask(orderId);

        mockMvc.perform(post("/api/orders/" + orderId + "/no-show")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"乘客未上车","idempotencyKey":"11111111-1111-1111-1111-111111111111"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.code").value("NO_SHOW_ORDER_NOT_IN_PROGRESS"));

        assertThat(rideOrderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CONFIRMED);
        assertThat(vehicleTaskRepository.findById(taskId).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.DISPATCHED);
        assertThat(auditLogRepository.findByEntityId(orderId))
                .anyMatch(log -> log.getAction().equals("ORDER_NO_SHOW_REJECTED"));
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
