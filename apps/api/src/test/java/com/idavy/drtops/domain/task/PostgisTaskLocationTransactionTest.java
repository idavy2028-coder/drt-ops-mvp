package com.idavy.drtops.domain.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idavy.drtops.domain.dispatch.DispatchDecision;
import com.idavy.drtops.domain.dispatch.DispatchDecisionRepository;
import com.idavy.drtops.domain.dispatch.DispatchDecisionType;
import com.idavy.drtops.domain.dispatch.DispatchOrchestrator;
import com.idavy.drtops.domain.dispatch.DispatchResult;
import com.idavy.drtops.domain.dispatch.ManualReviewService;
import com.idavy.drtops.domain.location.IdempotencyKeyLock;
import com.idavy.drtops.domain.location.LocationEventType;
import com.idavy.drtops.domain.location.LocationReportRequest;
import com.idavy.drtops.domain.location.LocationReportResponse;
import com.idavy.drtops.domain.location.VehicleLocationCommandService;
import com.idavy.drtops.domain.order.OrderStatus;
import com.idavy.drtops.domain.order.RideOrder;
import com.idavy.drtops.domain.order.RideOrderRepository;
import com.idavy.drtops.integration.algorithm.AlgorithmClient;
import com.idavy.drtops.integration.algorithm.DispatchEvaluateRequest;
import com.idavy.drtops.integration.algorithm.DispatchEvaluateResponse;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.sql.DataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@EnabledIfSystemProperty(named = "drt.integration.postgis", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=${drt.integration.postgis-url:jdbc:postgresql://127.0.0.1:15432/drt_ops}",
        "spring.datasource.username=drt_ops",
        "spring.datasource.password=drt_ops"
})
@Import(PostgisTaskLocationTransactionTest.DispatchTestConfiguration.class)
class PostgisTaskLocationTransactionTest {

    private static final UUID ACTOR_ID = UUID.fromString("77777777-7777-7777-7777-777777777704");

    @Autowired TaskExecutionService taskExecutionService;
    @Autowired VehicleLocationCommandService locationCommandService;
    @Autowired DispatchOrchestrator dispatchOrchestrator;
    @Autowired ManualReviewService manualReviewService;
    @Autowired VehicleTaskRepository vehicleTaskRepository;
    @Autowired RideOrderRepository rideOrderRepository;
    @Autowired DispatchDecisionRepository dispatchDecisionRepository;
    @Autowired StubAlgorithmClient algorithmClient;
    @Autowired IdempotencyKeyLock idempotencyKeyLock;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired DataSource dataSource;
    @PersistenceContext EntityManager entityManager;

    private UUID vehicleId;
    private UUID driverId;
    private UUID taskId;
    private UUID taskStopId;
    private UUID virtualStopId;

    @BeforeEach
    void setUpFixture() {
        UUID ruleSetId = UUID.randomUUID();
        UUID serviceAreaId = UUID.randomUUID();
        virtualStopId = UUID.randomUUID();
        vehicleId = UUID.randomUUID();
        driverId = UUID.randomUUID();
        taskId = UUID.randomUUID();
        taskStopId = UUID.randomUUID();

        jdbcTemplate.update("""
                insert into user_accounts (
                  id, username, display_name, password_hash, enabled, must_change_password
                ) values (?, 'task-location-postgis', 'Task 4 PostgreSQL 测试', 'not-used', true, false)
                on conflict (id) do nothing
                """, ACTOR_ID);
        jdbcTemplate.update("""
                insert into dispatch_rule_sets (
                  id, name, max_wait_minutes, max_detour_minutes, booking_window_minutes,
                  auto_dispatch_score_threshold, manual_review_score_threshold,
                  wait_weight, detour_weight, stability_weight, utilization_weight, insertion_policy, enabled
                ) values (?, ?, 15, 10, 120, 80, 60, 1, 1, 1, 1, 'BEST_SCORE', true)
                """, ruleSetId, "Task 4 " + ruleSetId);
        jdbcTemplate.update("""
                insert into service_areas (
                  id, name, boundary, service_start, service_end, rule_set_id, enabled
                ) values (?, ?, ST_GeogFromText('SRID=4326;POLYGON((116.30 39.90,116.35 39.90,116.35 39.96,116.30 39.96,116.30 39.90))'),
                  '00:00', '23:59', ?, true)
                """, serviceAreaId, "Task 4 " + serviceAreaId, ruleSetId);
        jdbcTemplate.update("""
                insert into virtual_stops (
                  id, service_area_id, name, location, service_radius_meters,
                  boarding_enabled, alighting_enabled, safety_note, enabled
                ) values (?, ?, ?, ST_GeogFromText('SRID=4326;POINT(116.32 39.93)'), 500,
                  true, true, 'Task 4 PostgreSQL 测试站点', true)
                """, virtualStopId, serviceAreaId, "Task 4 " + virtualStopId);
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
        jdbcTemplate.update("""
                insert into task_stops (
                  id, vehicle_task_id, virtual_stop_id, sequence_number, stop_type, planned_arrival_at, status
                ) values (?, ?, ?, 1, 'BOARDING', ?, 'PLANNED')
                """, taskStopId, taskId, virtualStopId, OffsetDateTime.now().plusMinutes(5));
    }

    @Test
    void auditFlushFailureThroughProductionProxyRollsBackTaskNodeEventSnapshotAndAudit() {
        prepareTaskForArrival();
        Trigger trigger = installAuditFailureTrigger();
        try {
            assertThatThrownBy(() -> taskExecutionService.arrive(
                            ACTOR_ID, taskId, taskStopId, request(UUID.randomUUID(), "审计 flush 失败回滚")))
                    .isInstanceOf(RuntimeException.class)
                    .satisfies(exception -> assertThat(rootCause(exception).getMessage())
                            .contains("Task 4 audit flush failure"));
        } finally {
            trigger.drop();
        }

        assertRollbackStateFromIndependentConnection("IN_PROGRESS");
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

        assertRollbackStateFromIndependentConnection("PENDING_DEPARTURE");
    }

    @Test
    void snapshotFlushFailureThroughProductionProxyRollsBackTaskNodeEventAndAudit() {
        prepareTaskForArrival();
        Trigger trigger = installSnapshotFailureTrigger();
        try {
            assertThatThrownBy(() -> taskExecutionService.arrive(
                            ACTOR_ID, taskId, taskStopId, request(UUID.randomUUID(), "快照 flush 失败回滚")))
                    .isInstanceOf(RuntimeException.class)
                    .satisfies(exception -> assertThat(rootCause(exception).getMessage())
                            .contains("Task 4 snapshot flush failure"));
        } finally {
            trigger.drop();
        }

        assertRollbackStateFromIndependentConnection("IN_PROGRESS");
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
        assertThat(eventCountForTask()).isOne();
        assertThat(auditCount()).isOne();
        assertThat(currentLocationEventId()).isEqualTo(first.locationEvent().id());
    }

    @ParameterizedTest
    @ValueSource(strings = {"VEHICLE_EXCEPTION", "SEVERE_DELAY"})
    void completionSerializesBeforeExceptionClosuresAndCannotBeOverwritten(String closure) throws Exception {
        AtomicInteger closurePid = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            CompletionRace race = startBlockedCompletion(executor);
            Future<VehicleTask> closureResult = submitTransactional(
                    executor,
                    closurePid,
                    () -> "VEHICLE_EXCEPTION".equals(closure)
                            ? taskExecutionService.markException(ACTOR_ID, taskId, "车辆故障并发")
                            : taskExecutionService.markSevereDelay(ACTOR_ID, taskId, "严重延误并发"));
            try {
                awaitBlockedBy(closurePid, race.completionPid().get());
                assertThat(isBlockedBy(closurePid.get(), race.completionPid().get())).isTrue();
            } finally {
                race.releaseBlocker().countDown();
            }

            race.completion().get(10, TimeUnit.SECONDS);
            race.blocker().get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> closureResult.get(10, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(ExecutionException.class, exception ->
                            assertThat(exception.getCause()).isInstanceOf(IllegalStateException.class));
        }

        assertThat(taskStatus()).isEqualTo("COMPLETED");
        assertThat(taskStopStatus()).isEqualTo("BOARDED");
        assertThat(eventCountForTask()).isOne();
        assertThat(taskAuditCount(closure.equals("VEHICLE_EXCEPTION")
                ? "TASK_EXCEPTION" : "TASK_SEVERE_DELAY")).isZero();
    }

    @Test
    void autoInsertionQueuedBehindCompletionCannotAddStopsToCompletedTask() throws Exception {
        RideOrder order = createPendingOrder("138" + randomDigits());
        algorithmClient.stubAutoDispatchIntoTask(taskId, vehicleId);
        AtomicInteger dispatchPid = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            CompletionRace race = startBlockedCompletion(executor);
            Future<DispatchResult> dispatch = submitTransactional(
                    executor,
                    dispatchPid,
                    () -> dispatchOrchestrator.dispatchOrder(order.getId()));
            try {
                awaitBlockedBy(dispatchPid, race.completionPid().get());
                assertThat(isBlockedBy(dispatchPid.get(), race.completionPid().get())).isTrue();
            } finally {
                race.releaseBlocker().countDown();
            }

            race.completion().get(10, TimeUnit.SECONDS);
            race.blocker().get(10, TimeUnit.SECONDS);
            assertFutureConflict(dispatch);
        }

        assertCompletedWithoutInsertedStops(order.getId(), OrderStatus.PENDING_DISPATCH);
    }

    @Test
    void manualInsertionQueuedBehindCompletionCannotAddStopsToCompletedTask() throws Exception {
        RideOrder order = createPendingOrder("137" + randomDigits());
        order.markPendingManualReview("Task 4 PostgreSQL 人工审核并发");
        order = rideOrderRepository.save(order);
        DispatchDecision decision = dispatchDecisionRepository.save(DispatchDecision.manualReview(
                order.getId(),
                1,
                vehicleId,
                taskId,
                "task-4-test",
                "SYSTEM",
                "task-4-test"));
        AtomicInteger approvalPid = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            CompletionRace race = startBlockedCompletion(executor);
            Future<DispatchResult> approval = submitTransactional(
                    executor,
                    approvalPid,
                    () -> manualReviewService.approve(ACTOR_ID, decision.getId()));
            try {
                awaitBlockedBy(approvalPid, race.completionPid().get());
                assertThat(isBlockedBy(approvalPid.get(), race.completionPid().get())).isTrue();
            } finally {
                race.releaseBlocker().countDown();
            }

            race.completion().get(10, TimeUnit.SECONDS);
            race.blocker().get(10, TimeUnit.SECONDS);
            assertFutureConflict(approval);
        }

        assertCompletedWithoutInsertedStops(order.getId(), OrderStatus.PENDING_MANUAL_REVIEW);
    }

    @Test
    void independentReportWinningBetweenPrecheckAndAppendMakesTaskActionFailAtomically() throws Exception {
        UUID key = UUID.randomUUID();
        OffsetDateTime reportedAt = OffsetDateTime.now().minusMinutes(1);
        TaskLocationReportRequest taskRequest = request(key, reportedAt, "PostgreSQL 跨接口并发");
        LocationReportRequest independentRequest = new LocationReportRequest(
                null,
                null,
                null,
                LocationEventType.TASK_STARTED,
                taskRequest.longitude(),
                taskRequest.latitude(),
                taskRequest.standardizedAddress(),
                reportedAt,
                taskRequest.note(),
                null,
                null,
                key);
        CountDownLatch independentHasLock = new CountDownLatch(1);
        CountDownLatch allowIndependentReport = new CountDownLatch(1);
        AtomicInteger blockerPid = new AtomicInteger();
        AtomicInteger callerPid = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<LocationReportResponse> independent = executor.submit(() -> withLocationReportAuthority(
                    () -> new TransactionTemplate(transactionManager).execute(status -> {
                        blockerPid.set(backendPid());
                        idempotencyKeyLock.acquire(key);
                        independentHasLock.countDown();
                        await(allowIndependentReport);
                        return locationCommandService.report(vehicleId, ACTOR_ID, independentRequest);
                    })));
            assertThat(independentHasLock.await(10, TimeUnit.SECONDS)).isTrue();

            Future<TaskActionResponse> taskAction = executor.submit(
                    () -> new TransactionTemplate(transactionManager).execute(status -> {
                        callerPid.set(backendPid());
                        return taskExecutionService.start(ACTOR_ID, taskId, taskRequest);
                    }));
            awaitBlockedBy(callerPid, blockerPid.get());
            assertThat(isBlockedBy(callerPid.get(), blockerPid.get())).isTrue();
            allowIndependentReport.countDown();

            LocationReportResponse independentResponse = independent.get(10, TimeUnit.SECONDS);
            assertThat(independentResponse.replayed()).isFalse();
            assertThatThrownBy(() -> taskAction.get(10, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(ExecutionException.class, exception ->
                            assertThat(exception.getCause())
                                    .isInstanceOfSatisfying(ResponseStatusException.class, cause -> {
                                        assertThat(cause.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                                        assertThat(cause.getReason()).isEqualTo("幂等编号已用于不同的位置请求");
                                    }));
        } finally {
            allowIndependentReport.countDown();
        }

        assertThat(taskStatus()).isEqualTo("PENDING_DEPARTURE");
        assertThat(taskStopStatus()).isEqualTo("PLANNED");
        assertThat(eventCountForKey(key)).isOne();
        assertThat(auditCount()).isZero();
        assertThat(currentLocationEventId()).isNotNull();
    }

    @Test
    void concurrentIdenticalRequestsWaitForExactTaskLockPidAndAdvanceOnlyOnce() throws Exception {
        TaskLocationReportRequest request = request(UUID.randomUUID(), "PostgreSQL 并发相同请求");
        CountDownLatch taskLocked = new CountDownLatch(1);
        CountDownLatch releaseTaskLock = new CountDownLatch(1);
        AtomicInteger blockerPid = new AtomicInteger();
        AtomicInteger firstCallerPid = new AtomicInteger();
        AtomicInteger secondCallerPid = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            Future<?> lockHolder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                vehicleTaskRepository.findByIdForExecution(taskId).orElseThrow();
                blockerPid.set(backendPid());
                taskLocked.countDown();
                await(releaseTaskLock, 30);
            }));
            assertThat(taskLocked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<TaskActionResponse> first = executor.submit(
                    () -> concurrentStart(firstCallerPid, request));
            awaitBlockedBy(firstCallerPid, blockerPid.get());
            assertThat(isBlockedBy(firstCallerPid.get(), blockerPid.get())).isTrue();

            Future<TaskActionResponse> second = executor.submit(
                    () -> concurrentStart(secondCallerPid, request));
            try {
                awaitBlockedBy(secondCallerPid, firstCallerPid.get());
                assertThat(isBlockedBy(secondCallerPid.get(), firstCallerPid.get())).isTrue();
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
        assertThat(eventCountForTask()).isOne();
        assertThat(auditCount()).isOne();
    }

    private CompletionRace startBlockedCompletion(ExecutorService executor) throws Exception {
        prepareTaskForCompletion();
        CountDownLatch taskLocked = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        AtomicInteger blockerPid = new AtomicInteger();
        AtomicInteger completionPid = new AtomicInteger();

        Future<?> blocker = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            vehicleTaskRepository.findByIdForExecution(taskId).orElseThrow();
            blockerPid.set(backendPid());
            taskLocked.countDown();
            await(releaseBlocker, 30);
        }));
        assertThat(taskLocked.await(10, TimeUnit.SECONDS)).isTrue();

        Future<TaskActionResponse> completion = submitTransactional(
                executor,
                completionPid,
                () -> taskExecutionService.complete(
                        ACTOR_ID, taskId, request(UUID.randomUUID(), "完成与其他任务写入并发")));
        awaitBlockedBy(completionPid, blockerPid.get());
        assertThat(isBlockedBy(completionPid.get(), blockerPid.get())).isTrue();
        return new CompletionRace(blocker, completion, completionPid, releaseBlocker);
    }

    private <T> Future<T> submitTransactional(
            ExecutorService executor,
            AtomicInteger callerPid,
            Supplier<T> action) {
        return executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
            callerPid.set(backendPid());
            return action.get();
        }));
    }

    private static void assertFutureConflict(Future<?> future) {
        assertThatThrownBy(() -> future.get(10, TimeUnit.SECONDS))
                .isInstanceOfSatisfying(ExecutionException.class, exception ->
                        assertThat(exception.getCause())
                                .isInstanceOfSatisfying(ResponseStatusException.class, cause ->
                                        assertThat(cause.getStatusCode()).isEqualTo(HttpStatus.CONFLICT)));
    }

    private void assertCompletedWithoutInsertedStops(UUID orderId, OrderStatus expectedOrderStatus) {
        assertThat(taskStatus()).isEqualTo("COMPLETED");
        assertThat(taskStopStatus()).isEqualTo("BOARDED");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from task_stops where vehicle_task_id = ?",
                Integer.class,
                taskId)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from task_stops where ride_order_id = ? and status = 'PLANNED'",
                Integer.class,
                orderId)).isZero();
        assertThat(rideOrderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(expectedOrderStatus);
    }

    private RideOrder createPendingOrder(String phone) {
        return rideOrderRepository.save(RideOrder.pendingDispatch(new RideOrder.CreateOrderCommand(
                "Task 4 PostgreSQL 并发乘客",
                phone,
                1,
                "IMMEDIATE",
                new BigDecimal("116.3200000"),
                new BigDecimal("39.9300000"),
                new BigDecimal("116.3210000"),
                new BigDecimal("39.9310000"),
                virtualStopId,
                virtualStopId,
                OffsetDateTime.now().plusMinutes(15))));
    }

    private static String randomDigits() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private void prepareTaskForArrival() {
        jdbcTemplate.update("update vehicle_tasks set status = 'IN_PROGRESS', current_stop_id = null where id = ?", taskId);
        jdbcTemplate.update("update task_stops set status = 'PLANNED', actual_arrival_at = null where id = ?", taskStopId);
    }

    private void prepareTaskForCompletion() {
        jdbcTemplate.update("update vehicle_tasks set status = 'IN_PROGRESS', current_stop_id = null where id = ?", taskId);
        jdbcTemplate.update("update task_stops set status = 'BOARDED' where id = ?", taskStopId);
    }

    private TaskActionResponse concurrentStart(
            AtomicInteger callerPid,
            TaskLocationReportRequest request) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            callerPid.set(backendPid());
            return taskExecutionService.start(ACTOR_ID, taskId, request);
        });
    }

    private void awaitBlockedBy(AtomicInteger callerPid, int expectedBlockerPid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            int pid = callerPid.get();
            if (pid != 0 && isBlockedBy(pid, expectedBlockerPid)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("调用连接未被指定的 PostgreSQL 持锁连接阻塞: blockerPid="
                + expectedBlockerPid + ", callerPid=" + callerPid.get()
                + ", caller=" + backendState(callerPid.get())
                + ", blocker=" + backendState(expectedBlockerPid));
    }

    private String backendState(int pid) {
        if (pid == 0) {
            return "pid-not-recorded";
        }
        return jdbcTemplate.queryForObject("""
                select concat(
                  'blocking=', pg_blocking_pids(pid)::text,
                  ', state=', state,
                  ', wait=', coalesce(wait_event_type, ''), '/', coalesce(wait_event, ''),
                  ', query=', left(query, 200))
                from pg_stat_activity
                where pid = ?
                """, String.class, pid);
    }

    private boolean isBlockedBy(int callerPid, int expectedBlockerPid) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "select ? = any(pg_blocking_pids(?))",
                Boolean.class,
                expectedBlockerPid,
                callerPid));
    }

    private int backendPid() {
        return ((Number) entityManager.createNativeQuery("select pg_backend_pid()")
                .getSingleResult()).intValue();
    }

    private Trigger installAuditFailureTrigger() {
        String suffix = taskId.toString().replace("-", "");
        String functionName = "task4_fail_audit_" + suffix;
        String triggerName = "task4_fail_audit_" + suffix;
        jdbcTemplate.execute("""
                create function %s() returns trigger language plpgsql as $$
                begin
                  if new.entity_id = '%s'::uuid then
                    raise exception 'Task 4 audit flush failure';
                  end if;
                  return new;
                end;
                $$
                """.formatted(functionName, taskId));
        jdbcTemplate.execute("create trigger " + triggerName
                + " before insert on audit_logs for each row execute function " + functionName + "()");
        return new Trigger(triggerName, "audit_logs", functionName);
    }

    private Trigger installSnapshotFailureTrigger() {
        String suffix = taskId.toString().replace("-", "");
        String functionName = "task4_fail_snapshot_" + suffix;
        String triggerName = "task4_fail_snapshot_" + suffix;
        jdbcTemplate.execute("""
                create function %s() returns trigger language plpgsql as $$
                begin
                  if new.id = '%s'::uuid and new.current_location_event_id is not null then
                    raise exception 'Task 4 snapshot flush failure';
                  end if;
                  return new;
                end;
                $$
                """.formatted(functionName, vehicleId));
        jdbcTemplate.execute("create trigger " + triggerName
                + " before update on vehicles for each row execute function " + functionName + "()");
        return new Trigger(triggerName, "vehicles", functionName);
    }

    private void assertRollbackStateFromIndependentConnection(String expectedTaskStatus) {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(queryString(connection, "select status from vehicle_tasks where id = ?", taskId))
                    .isEqualTo(expectedTaskStatus);
            assertThat(queryString(connection, "select status from task_stops where id = ?", taskStopId))
                    .isEqualTo("PLANNED");
            assertThat(queryInt(connection,
                    "select count(*) from task_stops where id = ? and actual_arrival_at is not null", taskStopId))
                    .isZero();
            assertThat(queryInt(connection,
                    "select count(*) from vehicle_location_events where vehicle_task_id = ?", taskId)).isZero();
            assertThat(queryInt(connection, "select count(*) from audit_logs where entity_id = ?", taskId)).isZero();
            assertThat(queryUuid(connection,
                    "select current_location_event_id from vehicles where id = ?", vehicleId)).isNull();
            assertThat(queryUuid(connection,
                    "select current_stop_id from vehicle_tasks where id = ?", taskId)).isNull();
        } catch (SQLException exception) {
            throw new IllegalStateException("无法从独立连接核对回滚状态", exception);
        }
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String queryString(Connection connection, String sql, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }

    private static int queryInt(Connection connection, String sql, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private static UUID queryUuid(Connection connection, String sql, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getObject(1, UUID.class);
            }
        }
    }

    private TaskLocationReportRequest request(UUID idempotencyKey, String note) {
        return request(idempotencyKey, OffsetDateTime.now().minusMinutes(1), note);
    }

    private TaskLocationReportRequest request(UUID idempotencyKey, OffsetDateTime reportedAt, String note) {
        return new TaskLocationReportRequest(
                new BigDecimal("116.3200000"),
                new BigDecimal("39.9300000"),
                "北京市朝阳区 Task 4 PostgreSQL 测试点",
                reportedAt,
                null,
                note,
                idempotencyKey);
    }

    private String taskStatus() {
        return jdbcTemplate.queryForObject(
                "select status from vehicle_tasks where id = ?", String.class, taskId);
    }

    private String taskStopStatus() {
        return jdbcTemplate.queryForObject(
                "select status from task_stops where id = ?", String.class, taskStopId);
    }

    private int eventCountForTask() {
        return jdbcTemplate.queryForObject(
                "select count(*) from vehicle_location_events where vehicle_task_id = ?", Integer.class, taskId);
    }

    private int eventCountForKey(UUID key) {
        return jdbcTemplate.queryForObject(
                "select count(*) from vehicle_location_events where idempotency_key = ?", Integer.class, key);
    }

    private int auditCount() {
        return jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where entity_id = ?", Integer.class, taskId);
    }

    private int taskAuditCount(String action) {
        return jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where entity_id = ? and action = ?",
                Integer.class,
                taskId,
                action);
    }

    private UUID currentLocationEventId() {
        return jdbcTemplate.queryForObject(
                "select current_location_event_id from vehicles where id = ?", UUID.class, vehicleId);
    }

    private <T> T withLocationReportAuthority(Supplier<T> action) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                ACTOR_ID,
                "not-used",
                List.of(new SimpleGrantedAuthority("LOCATION_REPORT"))));
        SecurityContextHolder.setContext(context);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static void await(CountDownLatch latch) {
        await(latch, 10);
    }

    private static void await(CountDownLatch latch, int timeoutSeconds) {
        try {
            if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new AssertionError("等待 PostgreSQL 并发协调信号超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 PostgreSQL 并发协调信号被中断", exception);
        }
    }

    private record CompletionRace(
            Future<?> blocker,
            Future<TaskActionResponse> completion,
            AtomicInteger completionPid,
            CountDownLatch releaseBlocker) {
    }

    @TestConfiguration
    static class DispatchTestConfiguration {

        @Bean
        @Primary
        StubAlgorithmClient algorithmClient() {
            return new StubAlgorithmClient();
        }
    }

    static final class StubAlgorithmClient implements AlgorithmClient {

        private volatile DispatchEvaluateResponse response;

        @Override
        public DispatchEvaluateResponse evaluate(DispatchEvaluateRequest request) {
            if (response == null) {
                throw new IllegalStateException("Task 4 PostgreSQL 算法响应未配置");
            }
            return response;
        }

        void stubAutoDispatchIntoTask(UUID selectedTaskId, UUID selectedVehicleId) {
            response = new DispatchEvaluateResponse(
                    DispatchDecisionType.AUTO_DISPATCH,
                    new DispatchEvaluateResponse.BestPlan(
                            selectedTaskId,
                            selectedVehicleId,
                            new BigDecimal("90.00"),
                            6,
                            3,
                            "SAME_DIRECTION",
                            new BigDecimal("0.25")),
                    1,
                    0,
                    List.of(),
                    Map.of("reason", "Task 4 PostgreSQL 自动插单并发"));
        }
    }

    private final class Trigger {
        private final String triggerName;
        private final String tableName;
        private final String functionName;

        private Trigger(String triggerName, String tableName, String functionName) {
            this.triggerName = triggerName;
            this.tableName = tableName;
            this.functionName = functionName;
        }

        private void drop() {
            jdbcTemplate.execute("drop trigger if exists " + triggerName + " on " + tableName);
            jdbcTemplate.execute("drop function if exists " + functionName + "()");
        }
    }
}
