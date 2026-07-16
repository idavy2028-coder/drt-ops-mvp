package com.idavy.drtops.domain.location;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
class PostgisVehicleLocationConcurrencyTest {

    private static final UUID FIRST_VEHICLE_ID = UUID.fromString("33333333-3333-3333-3333-333333333331");
    private static final UUID SECOND_VEHICLE_ID = UUID.fromString("33333333-3333-3333-3333-333333333332");
    private static final UUID RECORDED_BY = UUID.fromString("77777777-7777-7777-7777-777777777702");

    @Autowired
    private VehicleLocationRecorder recorder;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void createRecorderAccount() {
        jdbcTemplate.update("""
                insert into user_accounts (
                  id, username, display_name, password_hash, enabled, must_change_password
                ) values (?, 'location-concurrency-test', 'PostgreSQL concurrency test', 'not-used', true, false)
                on conflict (id) do nothing
                """, RECORDED_BY);
    }

    @Test
    void concurrentEquivalentRequestsCreateOneEventAndBothReturnFirstResult() throws Exception {
        UUID idempotencyKey = UUID.randomUUID();
        LocationReportCommand command = command(FIRST_VEHICLE_ID, idempotencyKey, "并发等价请求");

        List<Attempt> attempts = runConcurrently(command, command);

        assertThat(attempts).allSatisfy(attempt -> assertThat(attempt.failure()).isNull());
        assertThat(attempts)
                .extracting(attempt -> attempt.result().event().getId())
                .containsOnly(attempts.getFirst().result().event().getId());
        assertThat(attempts)
                .extracting(attempt -> attempt.result().replayed())
                .containsExactlyInAnyOrder(false, true);
        assertThat(eventCount(idempotencyKey)).isOne();
    }

    @Test
    void concurrentDifferentRequestsReturnOneResultAndOneConflict() throws Exception {
        UUID idempotencyKey = UUID.randomUUID();
        LocationReportCommand first = command(FIRST_VEHICLE_ID, idempotencyKey, "并发冲突请求");
        LocationReportCommand changedVehicle = command(SECOND_VEHICLE_ID, idempotencyKey, "并发冲突请求");

        List<Attempt> attempts = runConcurrently(first, changedVehicle);

        assertThat(attempts).filteredOn(attempt -> attempt.failure() == null).hasSize(1);
        assertThat(attempts).filteredOn(attempt -> attempt.failure() instanceof ResponseStatusException)
                .singleElement()
                .satisfies(attempt -> assertThat(((ResponseStatusException) attempt.failure()).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
        assertThat(eventCount(idempotencyKey)).isOne();
    }

    private List<Attempt> runConcurrently(LocationReportCommand first, LocationReportCommand second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Attempt> firstAttempt = executor.submit(() -> appendInTransaction(first, start));
            Future<Attempt> secondAttempt = executor.submit(() -> appendInTransaction(second, start));
            start.countDown();
            return List.of(firstAttempt.get(), secondAttempt.get());
        }
    }

    private Attempt appendInTransaction(LocationReportCommand command, CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            LocationReportResult result = new TransactionTemplate(transactionManager)
                    .execute(status -> recorder.append(command));
            return new Attempt(result, null);
        } catch (RuntimeException exception) {
            return new Attempt(null, exception);
        }
    }

    private int eventCount(UUID idempotencyKey) {
        return jdbcTemplate.queryForObject(
                "select count(*) from vehicle_location_events where idempotency_key = ?",
                Integer.class,
                idempotencyKey);
    }

    private LocationReportCommand command(UUID vehicleId, UUID idempotencyKey, String note) {
        return new LocationReportCommand(
                LocationReportScope.INDEPENDENT_REPORT,
                vehicleId,
                null,
                null,
                null,
                LocationEventType.TASK_STARTED,
                new BigDecimal("116.3200000"),
                new BigDecimal("39.9300000"),
                "北京市朝阳区望京园区",
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1),
                RECORDED_BY,
                note,
                null,
                null,
                idempotencyKey);
    }

    private record Attempt(LocationReportResult result, RuntimeException failure) {
    }
}
