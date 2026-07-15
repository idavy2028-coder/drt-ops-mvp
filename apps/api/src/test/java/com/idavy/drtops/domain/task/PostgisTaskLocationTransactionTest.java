package com.idavy.drtops.domain.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import com.idavy.drtops.domain.location.VehicleLocationEvent;
import com.idavy.drtops.domain.location.VehicleLocationRecorder;
import com.idavy.drtops.domain.location.VehicleLocationSnapshotService;
import com.idavy.drtops.domain.order.RideOrderRepository;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@EnabledIfSystemProperty(named = "drt.integration.postgis", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=${drt.integration.postgis-url:jdbc:postgresql://127.0.0.1:15432/drt_ops}",
        "spring.datasource.username=drt_ops",
        "spring.datasource.password=drt_ops"
})
class PostgisTaskLocationTransactionTest {

    private static final UUID ACTOR_ID = UUID.fromString("77777777-7777-7777-7777-777777777704");

    @Autowired TaskExecutionService taskExecutionService;
    @Autowired VehicleTaskRepository vehicleTaskRepository;
    @Autowired RideOrderRepository rideOrderRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired VehicleLocationRecorder locationRecorder;
    @Autowired VehicleLocationSnapshotService snapshotService;
    @Autowired VehicleRepository vehicleRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbcTemplate;

    private UUID vehicleId;
    private UUID driverId;
    private UUID taskId;

    @BeforeEach
    void setUpFixture() {
        vehicleId = UUID.randomUUID();
        driverId = UUID.randomUUID();
        taskId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into user_accounts (
                  id, username, display_name, password_hash, enabled, must_change_password
                ) values (?, 'task-location-postgis', 'Task 4 PostgreSQL 测试', 'not-used', true, false)
                on conflict (id) do nothing
                """, ACTOR_ID);
        jdbcTemplate.update("""
                insert into vehicles (
                  id, plate_number, vehicle_type, capacity, current_status, current_location, fleet_name, dispatchable
                ) values (?, ?, 'MINIBUS', 8, 'AVAILABLE',
                  ST_SetSRID(ST_MakePoint(116.3200000, 39.9300000), 4326)::geography,
                  'Task 4 PostgreSQL 测试车队', true)
                """, vehicleId, "PG-" + vehicleId.toString().substring(0, 8));
        jdbcTemplate.update("""
                insert into drivers (
                  id, name, phone, qualification_status, shift_start, shift_end, current_status, fleet_name
                ) values (?, 'Task 4 测试司机', ?, 'QUALIFIED', ?, ?, 'AVAILABLE', 'Task 4 PostgreSQL 测试车队')
                """,
                driverId,
                "139" + driverId.toString().replace("-", "").substring(0, 8),
                OffsetDateTime.now().minusHours(1),
                OffsetDateTime.now().plusHours(8));
        jdbcTemplate.update("""
                insert into vehicle_tasks (
                  id, vehicle_id, driver_id, status, planned_start_at, source_type
                ) values (?, ?, ?, 'PENDING_DEPARTURE', ?, 'MANUAL')
                """, taskId, vehicleId, driverId, OffsetDateTime.now().minusMinutes(5));
    }

    @Test
    void auditFailureRollsBackTaskEventAndSnapshotInOneTransaction() {
        TaskExecutionService failingService = new TaskExecutionService(
                vehicleTaskRepository,
                rideOrderRepository,
                failingAuditRepository(),
                locationRecorder,
                snapshotService);

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(
                        status -> failingService.start(ACTOR_ID, taskId, request(UUID.randomUUID(), "审计失败回滚"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("模拟任务审计写入失败");

        assertThat(taskStatus()).isEqualTo("PENDING_DEPARTURE");
        assertThat(eventCount()).isZero();
        assertThat(auditCount()).isZero();
        assertThat(currentLocationEventId()).isNull();
    }

    @Test
    void locationPersistenceFailureRollsBackTaskSnapshotAndAudit() {
        TaskLocationReportRequest invalid = new TaskLocationReportRequest(
                new BigDecimal("116.3200000"),
                new BigDecimal("39.9300000"),
                "超".repeat(301),
                OffsetDateTime.now().minusMinutes(1),
                null,
                "位置事件数据库约束失败",
                UUID.randomUUID());

        assertThatThrownBy(() -> taskExecutionService.start(ACTOR_ID, taskId, invalid))
                .isInstanceOf(RuntimeException.class);

        assertThat(taskStatus()).isEqualTo("PENDING_DEPARTURE");
        assertThat(eventCount()).isZero();
        assertThat(auditCount()).isZero();
        assertThat(currentLocationEventId()).isNull();
    }

    @Test
    void snapshotFailureRollsBackTaskEventAndAudit() {
        VehicleLocationSnapshotService failingSnapshot = new VehicleLocationSnapshotService(vehicleRepository) {
            @Override
            public void apply(VehicleLocationEvent event) {
                throw new IllegalStateException("模拟车辆快照写入失败");
            }
        };
        TaskExecutionService failingService = new TaskExecutionService(
                vehicleTaskRepository,
                rideOrderRepository,
                auditLogRepository,
                locationRecorder,
                failingSnapshot);

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(
                        status -> failingService.start(ACTOR_ID, taskId, request(UUID.randomUUID(), "快照失败回滚"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("模拟车辆快照写入失败");

        assertThat(taskStatus()).isEqualTo("PENDING_DEPARTURE");
        assertThat(eventCount()).isZero();
        assertThat(auditCount()).isZero();
        assertThat(currentLocationEventId()).isNull();
    }

    @Test
    void replayAfterStateAdvanceReturnsFirstEventAndChangedFingerprintConflicts() {
        UUID key = UUID.randomUUID();
        TaskLocationReportRequest request = request(key, "PostgreSQL 幂等请求");

        TaskActionResponse first = taskExecutionService.start(ACTOR_ID, taskId, request);
        TaskActionResponse replay = taskExecutionService.start(ACTOR_ID, taskId, request);

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.locationEvent().id()).isEqualTo(first.locationEvent().id());
        assertThatThrownBy(() -> taskExecutionService.start(
                        ACTOR_ID, taskId, request(key, "PostgreSQL 不同指纹")))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getReason()).isEqualTo("幂等编号已用于不同的位置请求");
                });
        assertThat(taskStatus()).isEqualTo("IN_PROGRESS");
        assertThat(eventCount()).isOne();
        assertThat(auditCount()).isOne();
        assertThat(currentLocationEventId()).isEqualTo(first.locationEvent().id());
    }

    @Test
    void concurrentIdenticalRequestsWaitForTaskLockAndAdvanceOnlyOnce() throws Exception {
        TaskLocationReportRequest request = request(UUID.randomUUID(), "PostgreSQL 并发相同请求");
        CountDownLatch taskLocked = new CountDownLatch(1);
        CountDownLatch releaseTaskLock = new CountDownLatch(1);
        CountDownLatch callersReady = new CountDownLatch(2);
        CountDownLatch issueRequests = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            Future<?> lockHolder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                vehicleTaskRepository.findByIdForExecution(taskId).orElseThrow();
                taskLocked.countDown();
                await(releaseTaskLock);
            }));
            assertThat(taskLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<TaskActionResponse> first = executor.submit(
                    () -> concurrentStart(callersReady, issueRequests, request));
            Future<TaskActionResponse> second = executor.submit(
                    () -> concurrentStart(callersReady, issueRequests, request));
            assertThat(callersReady.await(5, TimeUnit.SECONDS)).isTrue();
            issueRequests.countDown();
            try {
                awaitBlockedTaskRequests(2);
            } finally {
                releaseTaskLock.countDown();
            }

            List<TaskActionResponse> responses = List.of(
                    first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            lockHolder.get(10, TimeUnit.SECONDS);
            assertThat(responses).extracting(TaskActionResponse::replayed)
                    .containsExactlyInAnyOrder(false, true);
            assertThat(responses).extracting(response -> response.locationEvent().id())
                    .containsOnly(responses.getFirst().locationEvent().id());
        }

        assertThat(taskStatus()).isEqualTo("IN_PROGRESS");
        assertThat(eventCount()).isOne();
        assertThat(auditCount()).isOne();
    }

    private TaskActionResponse concurrentStart(
            CountDownLatch callersReady,
            CountDownLatch issueRequests,
            TaskLocationReportRequest request) {
        callersReady.countDown();
        await(issueRequests);
        return taskExecutionService.start(ACTOR_ID, taskId, request);
    }

    private void awaitBlockedTaskRequests(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer blocked = jdbcTemplate.queryForObject("""
                    select count(*)
                    from pg_stat_activity activity
                    where activity.datname = current_database()
                      and cardinality(pg_blocking_pids(activity.pid)) > 0
                      and activity.query ilike '%vehicle_tasks%'
                    """, Integer.class);
            if (blocked != null && blocked >= expected) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("两个任务动作请求未在 PostgreSQL 任务行锁上形成确定性等待");
    }

    @SuppressWarnings("unchecked")
    private AuditLogRepository failingAuditRepository() {
        return (AuditLogRepository) Proxy.newProxyInstance(
                AuditLogRepository.class.getClassLoader(),
                new Class<?>[] {AuditLogRepository.class},
                (proxy, method, arguments) -> {
                    if ("save".equals(method.getName())) {
                        throw new IllegalStateException("模拟任务审计写入失败");
                    }
                    return method.invoke(auditLogRepository, arguments);
                });
    }

    private TaskLocationReportRequest request(UUID idempotencyKey, String note) {
        return new TaskLocationReportRequest(
                new BigDecimal("116.3200000"),
                new BigDecimal("39.9300000"),
                "北京市朝阳区 Task 4 PostgreSQL 测试点",
                OffsetDateTime.now().minusMinutes(1),
                null,
                note,
                idempotencyKey);
    }

    private String taskStatus() {
        return jdbcTemplate.queryForObject(
                "select status from vehicle_tasks where id = ?", String.class, taskId);
    }

    private int eventCount() {
        return jdbcTemplate.queryForObject(
                "select count(*) from vehicle_location_events where vehicle_task_id = ?", Integer.class, taskId);
    }

    private int auditCount() {
        return jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where entity_id = ?", Integer.class, taskId);
    }

    private UUID currentLocationEventId() {
        return jdbcTemplate.queryForObject(
                "select current_location_event_id from vehicles where id = ?", UUID.class, vehicleId);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("等待 PostgreSQL 并发协调信号超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 PostgreSQL 并发协调信号被中断", exception);
        }
    }
}
