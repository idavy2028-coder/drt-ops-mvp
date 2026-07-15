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
import com.idavy.drtops.domain.location.VehicleLocationEventRepository;
import com.idavy.drtops.domain.location.IdempotencyKeyLock;
import com.idavy.drtops.domain.location.ServiceAreaLocationChecker;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:task_location_transaction;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
@Import(TaskLocationTransactionTest.LocationTestConfiguration.class)
class TaskLocationTransactionTest {

    private static final UUID VEHICLE_ID = UUID.fromString("33333333-3333-3333-3333-333333333341");
    private static final UUID DRIVER_ID = UUID.fromString("44444444-4444-4444-4444-444444444451");
    private static final UUID STOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555561");

    @Autowired MockMvc mockMvc;
    @Autowired VehicleTaskRepository vehicleTaskRepository;
    @Autowired VehicleRepository vehicleRepository;
    @Autowired VehicleLocationEventRepository eventRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired UserAccountRepository userAccountRepository;
    @Autowired JwtTokenService jwtTokenService;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbcTemplate;

    private String dispatcherToken;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        eventRepository.deleteAll();
        vehicleTaskRepository.deleteAll();
        vehicleRepository.deleteAll();
        userAccountRepository.deleteAll();

        vehicleRepository.save(Vehicle.create(
                VEHICLE_ID,
                "浙A00002",
                "MINIBUS",
                8,
                "AVAILABLE",
                "POINT(120.1550 30.2741)",
                "事务测试车队",
                true));
        UserAccount dispatcher = UserAccount.create("task-location", "task-location", "not-used");
        dispatcher.assignRoles(Set.of(RoleCode.DISPATCHER));
        dispatcher = userAccountRepository.save(dispatcher);
        dispatcherToken = jwtTokenService.issue(dispatcher).value();
    }

    @Test
    void futureReportRollsBackTaskEventSnapshotAndAudit() throws Exception {
        UUID taskId = createTask();

        performStart(taskId, request(UUID.randomUUID(), OffsetDateTime.now().plusHours(1), "未来位置"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.message").value("反馈时间不能晚于当前时间"));

        assertUnchanged(taskId);
    }

    @Test
    void illegalTaskStateDoesNotWriteLocationOrSnapshot() throws Exception {
        UUID taskId = createTask();
        UUID taskStopId = firstStopId(taskId);

        mockMvc.perform(post("/api/vehicle-tasks/" + taskId + "/stops/" + taskStopId + "/arrive")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(UUID.randomUUID(), OffsetDateTime.now().minusMinutes(1), "非法状态位置")))
                .andExpect(status().isConflict());

        assertUnchanged(taskId);
        assertThat(firstStop(taskId).getStatus()).isEqualTo("PLANNED");
    }

    @Test
    void replayAfterStateAdvanceReturnsOriginalEventAndChangedFingerprintConflicts() throws Exception {
        UUID taskId = createTask();
        UUID key = UUID.randomUUID();
        String original = request(key, OffsetDateTime.now().minusMinutes(1), "首次位置");
        MvcResult first = performStart(taskId, original)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(false))
                .andReturn();

        String eventId = com.jayway.jsonpath.JsonPath.read(
                first.getResponse().getContentAsString(), "$.data.locationEvent.id");
        performStart(taskId, original)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.locationEvent.id").value(eventId))
                .andExpect(jsonPath("$.data.replayed").value(true));
        performStart(taskId, request(key, OffsetDateTime.now().minusMinutes(1), "不同位置指纹"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.message").value("幂等编号已用于不同的位置请求"));

        assertThat(eventRepository.count()).isOne();
        assertThat(auditLogRepository.findByEntityId(taskId)).hasSize(1);
    }

    @Test
    void concurrentIdenticalRequestsAdvanceTaskOnlyOnce() throws Exception {
        UUID taskId = createTask();
        String request = request(UUID.randomUUID(), OffsetDateTime.now().minusMinutes(1), "并发相同请求");
        CountDownLatch taskLocked = new CountDownLatch(1);
        CountDownLatch releaseTaskLock = new CountDownLatch(1);
        CountDownLatch issueRequests = new CountDownLatch(1);
        AtomicReference<Thread> firstThread = new AtomicReference<>();
        AtomicReference<Thread> secondThread = new AtomicReference<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            Future<?> lockHolder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbcTemplate.queryForObject(
                        "select id from vehicle_tasks where id = ? for update", UUID.class, taskId);
                taskLocked.countDown();
                await(releaseTaskLock);
            }));
            assertThat(taskLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<MvcResult> first = executor.submit(() -> concurrentStart(
                    firstThread, issueRequests, taskId, request));
            Future<MvcResult> second = executor.submit(() -> concurrentStart(
                    secondThread, issueRequests, taskId, request));
            issueRequests.countDown();
            awaitDatabaseWait(firstThread, secondThread);
            releaseTaskLock.countDown();

            List<MvcResult> results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            lockHolder.get(10, TimeUnit.SECONDS);
            assertThat(results).allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
            assertThat(results)
                    .extracting(result -> (Boolean) com.jayway.jsonpath.JsonPath.read(
                            result.getResponse().getContentAsString(), "$.data.replayed"))
                    .containsExactlyInAnyOrder(false, true);
        }

        assertThat(vehicleTaskRepository.findById(taskId).orElseThrow().getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(eventRepository.count()).isOne();
        assertThat(auditLogRepository.findByEntityId(taskId)).hasSize(1);
    }

    private org.springframework.test.web.servlet.ResultActions performStart(UUID taskId, String request)
            throws Exception {
        return mockMvc.perform(post("/api/vehicle-tasks/" + taskId + "/start")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + dispatcherToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request));
    }

    private MvcResult concurrentStart(
            AtomicReference<Thread> threadReference,
            CountDownLatch issueRequests,
            UUID taskId,
            String request) throws Exception {
        threadReference.set(Thread.currentThread());
        issueRequests.await();
        return performStart(taskId, request).andReturn();
    }

    private static void awaitDatabaseWait(
            AtomicReference<Thread> firstThread,
            AtomicReference<Thread> secondThread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (isBlocked(firstThread.get()) && isBlocked(secondThread.get())) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("并发请求未在任务悲观锁上形成确定性等待");
    }

    private static boolean isBlocked(Thread thread) {
        if (thread == null) {
            return false;
        }
        return thread.getState() == Thread.State.WAITING
                || thread.getState() == Thread.State.TIMED_WAITING
                || thread.getState() == Thread.State.BLOCKED;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("等待并发协调信号超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待并发协调信号被中断", exception);
        }
    }

    private void assertUnchanged(UUID taskId) {
        assertThat(vehicleTaskRepository.findById(taskId).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.PENDING_DEPARTURE);
        assertThat(eventRepository.count()).isZero();
        assertThat(auditLogRepository.findByEntityId(taskId)).isEmpty();
        assertThat(vehicleRepository.findById(VEHICLE_ID).orElseThrow().getCurrentLocationEventId()).isNull();
    }

    private UUID createTask() {
        VehicleTask task = VehicleTask.pendingDeparture(
                VEHICLE_ID,
                DRIVER_ID,
                OffsetDateTime.now().minusMinutes(5),
                "ALGORITHM");
        task.addStop(TaskStop.planned(
                STOP_ID,
                null,
                1,
                "BOARDING",
                OffsetDateTime.now().plusMinutes(5)));
        return vehicleTaskRepository.save(task).getId();
    }

    private UUID firstStopId(UUID taskId) {
        return firstStop(taskId).getId();
    }

    private TaskStop firstStop(UUID taskId) {
        return vehicleTaskRepository.findWithStopsById(taskId).orElseThrow().getStops().getFirst();
    }

    private String request(UUID key, OffsetDateTime reportedAt, String note) {
        return """
                {
                  "locationReport": {
                    "longitude": 120.1550000,
                    "latitude": 30.2741000,
                    "standardizedAddress": "浙江省杭州市事务测试道路",
                    "driverReportedAt": "%s",
                    "note": "%s",
                    "idempotencyKey": "%s"
                  }
                }
                """.formatted(reportedAt, note, key);
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
