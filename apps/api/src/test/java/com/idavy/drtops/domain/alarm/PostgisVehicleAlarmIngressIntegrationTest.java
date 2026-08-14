package com.idavy.drtops.domain.alarm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@EnabledIf("integrationEnvironmentAvailable")
@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.jpa.open-in-view=false"})
class PostgisVehicleAlarmIngressIntegrationTest {
    private static PostgreSQLContainer<?> postgres;
    @Autowired VehicleAlarmIngressService service;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void postgisProperties(DynamicPropertyRegistry registry) {
        PostgreSQLContainer<?> container = postgres(); container.start();
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", container::getUsername);
        registry.add("spring.datasource.password", container::getPassword);
    }

    @AfterAll static void stopContainer() { if (postgres != null) postgres.stop(); }

    @BeforeEach
    void removePriorOutboxRejector() {
        removeOutboxRejector();
        closeTaskBindings();
    }

    @AfterEach
    void removeOutboxRejector() {
        jdbc.execute("drop trigger if exists trg_reject_alarm_outbox_for_test on vehicle_alarm_outbox");
        jdbc.execute("drop function if exists reject_alarm_outbox_for_test()");
        closeTaskBindings();
    }

    private void closeTaskBindings() {
        jdbc.update("""
                update jt_terminal_vehicle_bindings
                set status = 'UNBOUND', valid_to = coalesce(valid_to, now()),
                    unbinding_reason = 'task10 test cleanup', updated_at = now()
                where status = 'ACTIVE' and binding_reason = 'task10 test binding'
                """);
    }

    @Test
    void persistsMultiAlarmReplayEndAndOutboxInOneDatabaseTransaction() {
        UUID terminal = terminal(); UUID vehicle = vehicle();
        bind(terminal, vehicle);
        UUID positionKey = acceptedPosition(terminal, vehicle, "QUARANTINED");
        VehicleAlarmIngressService.AlarmFact adas = fact(terminal, vehicle, positionKey, "ADAS", 1, "FORWARD_COLLISION", "00000001", "START");
        VehicleAlarmIngressService.AlarmFact dms = fact(terminal, vehicle, positionKey, "DMS", 2, "PHONE", "00000002", "START");

        service.ingest(List.of(adas, dms));
        service.ingest(List.of(adas, dms));
        service.ingest(List.of(withTerminalIdentifier(
                adas.endAt(Instant.parse("2026-01-15T02:01:00Z")), "f".repeat(64))));

        assertThat(jdbc.queryForObject(
                "select count(*) from vehicle_alarms where terminal_id = ?", Integer.class, terminal)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                select count(*) from vehicle_alarm_outbox o
                join vehicle_alarms a on a.id = o.vehicle_alarm_id
                where a.terminal_id = ?
                """, Integer.class, terminal)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                select ended_at from vehicle_alarms
                where terminal_id = ? and terminal_alarm_identifier = '00000001'
                """, Instant.class, terminal))
                .isEqualTo(Instant.parse("2026-01-15T02:01:00Z"));
        assertThat(jdbc.queryForObject(
                "select count(distinct location_event_id) from vehicle_alarms where terminal_id = ?",
                Integer.class, terminal)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select terminal_alarm_id from vehicle_alarms where terminal_id = ? and module = 'ADAS'",
                Long.class, terminal)).isEqualTo(0x00001001L);
        assertThat(jdbc.queryForList(
                "select location_quality_status from vehicle_alarms where terminal_id = ?", String.class, terminal))
                .containsOnly("QUARANTINED");
    }

    @Test
    void rollsBackTheFactWhenTheDatabaseRejectsItsOutboxWrite() {
        UUID terminal = terminal(); UUID vehicle = vehicle();
        bind(terminal, vehicle);
        UUID positionKey = acceptedPosition(terminal, vehicle, "GOOD");
        removeOutboxRejector();
        jdbc.execute("""
                create function reject_alarm_outbox_for_test() returns trigger language plpgsql as $$
                begin raise exception 'outbox test failure'; end; $$;
                create trigger trg_reject_alarm_outbox_for_test before insert on vehicle_alarm_outbox
                for each row execute function reject_alarm_outbox_for_test();
                """);
        try {
            assertThatThrownBy(() -> service.ingest(List.of(fact(terminal, vehicle, positionKey, "ADAS", 1, "FORWARD_COLLISION", "00000003", "START"))))
                    .isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("select count(*) from vehicle_alarms where terminal_alarm_identifier = '00000003'", Integer.class))
                    .isZero();
        } finally {
            removeOutboxRejector();
        }
    }

    @Test
    void atomicallyReplaysConcurrentStartsWithTheSameDeduplicationKey() throws Exception {
        UUID terminal = terminal(); UUID vehicle = vehicle();
        bind(terminal, vehicle);
        UUID positionKey = acceptedPosition(terminal, vehicle, "GOOD");
        VehicleAlarmIngressService.AlarmFact start = fact(terminal, vehicle, positionKey, "ADAS", 1,
                "FORWARD_COLLISION", "00000004", "START");
        runConcurrently(() -> service.ingest(List.of(start)));

        assertThat(jdbc.queryForObject("select count(*) from vehicle_alarms where terminal_id = ?", Integer.class, terminal))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from vehicle_alarm_outbox o join vehicle_alarms a on a.id = o.vehicle_alarm_id where a.terminal_id = ?", Integer.class, terminal))
                .isEqualTo(1);
    }

    @Test
    void appendsOneOutboxEventForConcurrentEndsOfTheSameStart() throws Exception {
        UUID terminal = terminal(); UUID vehicle = vehicle();
        bind(terminal, vehicle);
        UUID positionKey = acceptedPosition(terminal, vehicle, "GOOD");
        VehicleAlarmIngressService.AlarmFact start = fact(terminal, vehicle, positionKey, "DMS", 2,
                "PHONE", "00000005", "START");
        service.ingest(List.of(start));
        VehicleAlarmIngressService.AlarmFact end = start.endAt(Instant.parse("2026-01-15T02:01:00Z"));

        runConcurrently(() -> service.ingest(List.of(end)));

        assertThat(jdbc.queryForObject("select count(*) from vehicle_alarms where terminal_id = ?", Integer.class, terminal))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from vehicle_alarm_outbox o join vehicle_alarms a on a.id = o.vehicle_alarm_id where a.terminal_id = ?", Integer.class, terminal))
                .isEqualTo(2);
    }

    @Test
    void acceptsABufferedAlarmAgainstTheBindingThatWasValidAtGatewayReceiptTime() {
        UUID terminal = terminal();
        UUID previousVehicle = vehicle(0);
        UUID replacementVehicle = vehicle(1);
        Instant switchedAt = Instant.parse("2026-01-15T02:00:10Z");
        bindAt(terminal, previousVehicle, Instant.parse("2026-01-15T01:59:00Z"));
        unbindAt(terminal, previousVehicle, switchedAt);
        bindAt(terminal, replacementVehicle, switchedAt);
        UUID positionKey = acceptedPosition(terminal, previousVehicle, "GOOD");

        service.ingest(List.of(fact(terminal, previousVehicle, positionKey, "ADAS", 1,
                "FORWARD_COLLISION", "00000006", "START")));

        assertThat(jdbc.queryForObject(
                "select count(*) from vehicle_alarms where terminal_id = ? and vehicle_id = ?",
                Integer.class, terminal, previousVehicle)).isEqualTo(1);
        assertThatThrownBy(() -> service.ingest(List.of(withGatewayReceivedAt(
                fact(terminal, previousVehicle, positionKey, "ADAS", 2,
                        "LANE_DEPARTURE", "00000007", "START"), switchedAt))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("terminal vehicle binding mismatch");
    }

    private static void runConcurrently(Runnable operation) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch proceed = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            List<? extends Future<?>> calls = java.util.stream.IntStream.range(0, 2).mapToObj(ignored -> workers.submit(() -> {
                ready.countDown();
                try {
                    proceed.await();
                    operation.run();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(interrupted);
                }
            })).toList();
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            proceed.countDown();
            for (Future<?> call : calls) {
                call.get();
            }
        }
    }

    private UUID terminal() {
        UUID id = UUID.randomUUID(); String compact = id.toString().replace("-", "");
        jdbc.update("""
                insert into jt_terminals (id, terminal_phone, terminal_code, manufacturer_id, model, protocol_version,
                source_coordinate_system, active_safety_modules, jt1078_enabled, status, auth_token_hash, auth_token_version, created_at, updated_at)
                values (?, ?, ?, 'TEST', 'TEST', 'JT808-2019', 'WGS84', '["ADAS","DMS"]'::jsonb, false, 'ACTIVE', repeat('a',64), 1, now(), now())
                """, id, "PG-" + compact.substring(0, 24), "PG-CODE-" + compact);
        return id;
    }
    private UUID vehicle() { return vehicle(0); }
    private UUID vehicle(int offset) {
        return jdbc.queryForObject("select id from vehicles order by id limit 1 offset ?", UUID.class, offset);
    }
    private void bind(UUID terminal, UUID vehicle) {
        bindAt(terminal, vehicle, Instant.parse("2026-01-15T01:59:00Z"));
    }
    private void bindAt(UUID terminal, UUID vehicle, Instant validFrom) {
        jdbc.update("""
                insert into jt_terminal_vehicle_bindings (
                  id, terminal_id, vehicle_id, valid_from, status, binding_reason, created_at, updated_at
                ) values (?, ?, ?, ?, 'ACTIVE', 'task10 test binding', now(), now())
                """, UUID.randomUUID(), terminal, vehicle, validFrom.atOffset(java.time.ZoneOffset.UTC));
    }
    private void unbindAt(UUID terminal, UUID vehicle, Instant validTo) {
        jdbc.update("""
                update jt_terminal_vehicle_bindings
                set status = 'UNBOUND', valid_to = ?, unbinding_reason = 'task10 test replacement', updated_at = now()
                where terminal_id = ? and vehicle_id = ? and status = 'ACTIVE'
                """, validTo.atOffset(java.time.ZoneOffset.UTC), terminal, vehicle);
    }
    private UUID acceptedPosition(UUID terminal, UUID vehicle, String qualityStatus) {
        UUID eventId = UUID.randomUUID();
        UUID positionKey = UUID.randomUUID();
        jdbc.update("""
                insert into vehicle_location_events (
                  id, vehicle_id, event_type, source, location, longitude, latitude, coordinate_system,
                  driver_reported_at, recorded_at, idempotency_key, request_fingerprint, snapshot_applied,
                  outside_service_area, terminal_id, protocol_version, message_serial_no, raw_longitude,
                  raw_latitude, raw_coordinate_system, gateway_received_at, payload_digest,
                  coordinate_transform_version, quality_status, quality_reasons
                ) values (?, ?, 'GPS_REPORT', 'GPS_DEVICE', ST_GeogFromText('SRID=4326;POINT(118 32)'),
                  118.0000000, 32.0000000, 'GCJ02', now(), now(), ?, repeat('b',64), false, false,
                  ?, 'JT808_2019', 1, 118.0000000, 32.0000000, 'WGS84', now(), repeat('c',64),
                  'WGS84_TO_GCJ02_V1', ?, '[]'::jsonb)
                """, eventId, vehicle, positionKey, terminal, qualityStatus);
        jdbc.update("""
                insert into jt_gateway_ingress_receipts (
                  idempotency_key, final_status, reason_codes, created_at, completed_at
                ) values (?, 'ACCEPTED', '[]'::jsonb, now(), now())
                """, positionKey);
        return positionKey;
    }
    private static VehicleAlarmIngressService.AlarmFact fact(
            UUID terminal, UUID vehicle, UUID positionKey, String module, int code, String type, String id, String state) {
        return new VehicleAlarmIngressService.AlarmFact(terminal, vehicle, "T/JSATL12-2017", module, code, type,
                0x00001000L + code, state, 1, id,
                Instant.parse("2026-01-15T02:00:00Z"), Instant.parse("2026-01-15T02:00:01Z"), new BigDecimal("118.0000000"),
                new BigDecimal("32.0000000"), new BigDecimal("60.00"), positionKey,
                "UNASSESSED", "a".repeat(64));
    }
    private static VehicleAlarmIngressService.AlarmFact withTerminalIdentifier(
            VehicleAlarmIngressService.AlarmFact fact, String terminalAlarmIdentifier) {
        return new VehicleAlarmIngressService.AlarmFact(
                fact.terminalId(), fact.vehicleId(), fact.standard(), fact.module(), fact.typeCode(), fact.alarmType(),
                fact.terminalAlarmId(), fact.state(), fact.level(), terminalAlarmIdentifier, fact.occurredAt(),
                fact.gatewayReceivedAt(), fact.longitude(), fact.latitude(), fact.speedKph(),
                fact.positionIdempotencyKey(), fact.locationQualityStatus(), fact.payloadDigest());
    }
    private static VehicleAlarmIngressService.AlarmFact withGatewayReceivedAt(
            VehicleAlarmIngressService.AlarmFact fact, Instant gatewayReceivedAt) {
        return new VehicleAlarmIngressService.AlarmFact(
                fact.terminalId(), fact.vehicleId(), fact.standard(), fact.module(), fact.typeCode(), fact.alarmType(),
                fact.terminalAlarmId(), fact.state(), fact.level(), fact.terminalAlarmIdentifier(), fact.occurredAt(),
                gatewayReceivedAt, fact.longitude(), fact.latitude(), fact.speedKph(),
                fact.positionIdempotencyKey(), fact.locationQualityStatus(), fact.payloadDigest());
    }
    static boolean integrationEnvironmentAvailable() {
        try { return Boolean.getBoolean("drt.integration.postgis") && DockerClientFactory.instance().isDockerAvailable(); }
        catch (RuntimeException exception) { return false; }
    }
    private static synchronized PostgreSQLContainer<?> postgres() {
        if (postgres == null) postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.5")
                .asCompatibleSubstituteFor("postgres")).withStartupTimeout(Duration.ofMinutes(5));
        return postgres;
    }
}
