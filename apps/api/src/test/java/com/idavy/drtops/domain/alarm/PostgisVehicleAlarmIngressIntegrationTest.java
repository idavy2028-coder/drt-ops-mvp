package com.idavy.drtops.domain.alarm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@EnabledIf("integrationEnvironmentAvailable")
@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false"})
class PostgisVehicleAlarmIngressIntegrationTest {
    private static final String MASTER_PROPERTY = "drt.integration.postgis";
    private static final String EXTERNAL_PROPERTY =
            "drt.integration.alarm-postgis.external-ephemeral";
    private static final String EXTERNAL_JDBC_URL_PROPERTY =
            "drt.integration.alarm-postgis.jdbc-url";
    private static final String EXTERNAL_USERNAME_PROPERTY =
            "drt.integration.alarm-postgis.username";
    private static final String EXTERNAL_PASSWORD_PROPERTY =
            "drt.integration.alarm-postgis.password";
    private static final String EXTERNAL_USERNAME = "alarm_authority";
    private static final String EXTERNAL_JDBC_URL_PATTERN =
            "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):\\d+/alarm_authority";
    private static final Instant AUTHORIZED_AT = Instant.parse("2026-01-15T02:00:00Z");
    private static final UUID LEGACY_TERMINAL_ID =
            UUID.fromString("aaaaaaaa-1111-1111-1111-111111111111");
    private static final UUID LEGACY_SYSTEM_ID =
            UUID.fromString("aaaaaaaa-2222-2222-2222-222222222222");
    private static final UUID LEGACY_VEHICLE_ID =
            UUID.fromString("aaaaaaaa-3333-3333-3333-333333333333");
    private static PostgreSQLContainer<?> postgres;
    private static boolean ownsPostgresContainer;

    @Autowired VehicleAlarmIngressService service;
    @Autowired com.idavy.drtops.domain.terminal.JtTerminalSessionLeaseService leaseService;
    @Autowired JdbcTemplate jdbc;
    @Autowired VehicleRepository vehicles;
    @Autowired VehicleAlarmRepository alarms;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired jakarta.persistence.EntityManager entityManager;

    @DynamicPropertySource
    static void postgisProperties(DynamicPropertyRegistry registry) {
        ExternalPostgres external = externalPostgres();
        if (external != null) {
            prepareV20Contract(external);
            registry.add("spring.datasource.url", external::jdbcUrl);
            registry.add("spring.datasource.username", external::username);
            registry.add("spring.datasource.password", external::password);
            return;
        }
        PostgreSQLContainer<?> container = postgres();
        container.start();
        ownsPostgresContainer = true;
        ExternalPostgres owned = new ExternalPostgres(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
        prepareV20Contract(owned);
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", container::getUsername);
        registry.add("spring.datasource.password", container::getPassword);
    }

    @AfterAll
    static void stopContainer() {
        if (ownsPostgresContainer && postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void removePriorOutboxRejector() {
        removeOutboxRejector();
    }

    @AfterEach
    void removeOutboxRejector() {
        jdbc.execute("drop trigger if exists trg_reject_c1_audit_for_test on audit_logs");
        jdbc.execute("drop function if exists reject_c1_audit_for_test()");
        jdbc.execute("drop trigger if exists trg_reject_alarm_outbox_for_test on vehicle_alarm_outbox");
        jdbc.execute("drop function if exists reject_alarm_outbox_for_test()");
    }

    @Test
    void validatesAllMappedEntitiesAndKeepsTheFlywayDigestColumnTypes() throws Exception {
        assertThat(entityManager.getEntityManagerFactory().getProperties()
                .get("hibernate.hbm2ddl.auto")).isEqualTo("validate");
        // Full application bootstrap validates the entire persistence unit, not an entity subset.
        int entities = entityManager.getMetamodel().getEntities().size();
        assertThat(entities).isPositive();
        record ColumnSpec(String table, String column, int jdbcType, boolean nullable) { }
        var columns = List.of(
                new ColumnSpec("jt_gateway_audit_events", "payload_digest", java.sql.Types.CHAR, true),
                new ColumnSpec("jt_terminals", "auth_token_hash", java.sql.Types.CHAR, false),
                new ColumnSpec("vehicle_alarms", "payload_digest", java.sql.Types.CHAR, false),
                new ColumnSpec("vehicle_alarms", "deduplication_key", java.sql.Types.CHAR, false),
                new ColumnSpec("vehicle_alarm_attachments", "payload_digest", java.sql.Types.CHAR, true),
                new ColumnSpec("vehicle_location_events", "payload_digest", java.sql.Types.CHAR, true),
                new ColumnSpec("video_declaration_observations", "payload_digest", java.sql.Types.VARCHAR, false));
        try (var connection = jdbc.getDataSource().getConnection()) {
            for (var spec : columns) {
                try (var column = connection.getMetaData().getColumns(
                        null, "public", spec.table(), spec.column())) {
                    assertThat(column.next()).as(spec.table() + "." + spec.column()).isTrue();
                    assertThat(column.getInt("DATA_TYPE")).isEqualTo(spec.jdbcType());
                    assertThat(column.getInt("COLUMN_SIZE")).isEqualTo(64);
                    assertThat(column.getInt("NULLABLE")).isEqualTo(spec.nullable()
                            ? java.sql.DatabaseMetaData.columnNullable
                            : java.sql.DatabaseMetaData.columnNoNulls);
                    assertThat(column.next()).isFalse();
                }
            }
        }
        assertThat(jdbc.queryForObject("select version from flyway_schema_history "
                + "order by installed_rank desc limit 1", String.class)).isEqualTo("22");
        System.out.println("C1_FULL_VALIDATE entities=" + entities + " CHAR_columns=6 VARCHAR_columns=1 schema=22");
    }

    @Test
    void charDigestsRoundTripAndBindQueriesWithoutPaddingWhileNullsStayNull() {
        var fixture = onboardAlarmFixture(false, "GOOD", "ADAS");
        service.ingest(List.of(fixture.fact("ADAS", 1, "FORWARD_COLLISION", "CHAR-MAPPING", "START")));
        UUID alarmId = jdbc.queryForObject("select id from vehicle_alarms where terminal_id=?",
                UUID.class, fixture.terminalId());
        String deduplicationKey = jdbc.queryForObject("select deduplication_key from vehicle_alarms where id=?",
                String.class, alarmId);
        assertThat(deduplicationKey).matches("[0-9a-f]{64}");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var terminal = entityManager.find(com.idavy.drtops.domain.terminal.JtTerminal.class, fixture.terminalId());
            terminal.completeRegistration(2, "9".repeat(64));
            UUID actor = jdbc.queryForObject("select id from user_accounts order by id limit 1", UUID.class);
            var location = mappingLocation(fixture.vehicleId(), actor, "c".repeat(64));
            var nullLocation = mappingLocation(fixture.vehicleId(), actor, null);
            entityManager.persist(location);
            entityManager.persist(nullLocation);
            var audit = mappingAudit(fixture.terminalId(), fixture.vehicleId(), "d".repeat(64));
            var nullAudit = mappingAudit(fixture.terminalId(), fixture.vehicleId(), null);
            entityManager.persist(audit);
            entityManager.persist(nullAudit);
            var attachment = VehicleAlarmAttachment.register(alarmId, "VIDEO", "1", "MP4",
                    "synthetic.mp4", 128L, "e".repeat(64),
                    VehicleAlarmAttachment.Status.WAITING_MEDIA_SERVICE, Instant.now());
            var nullAttachment = VehicleAlarmAttachment.register(alarmId, "VIDEO", "1", "MP4",
                    null, null, null, VehicleAlarmAttachment.Status.WAITING_MEDIA_SERVICE, Instant.now());
            entityManager.persist(attachment);
            entityManager.persist(nullAttachment);
            entityManager.flush();
            entityManager.clear();
            assertMappedDigest("JtTerminal", "authTokenHash", fixture.terminalId(), "9".repeat(64));
            assertMappedDigest("JtGatewayAuditEvent", "payloadDigest", audit.getId(), "d".repeat(64));
            assertMappedDigest("JtGatewayAuditEvent", "payloadDigest", nullAudit.getId(), null);
            assertMappedDigest("VehicleLocationEvent", "payloadDigest", location.getId(), "c".repeat(64));
            assertMappedDigest("VehicleLocationEvent", "payloadDigest", nullLocation.getId(), null);
            assertMappedDigest("VehicleAlarm", "payloadDigest", alarmId, "a".repeat(64));
            assertMappedDigest("VehicleAlarm", "deduplicationKey", alarmId, deduplicationKey);
            assertMappedDigest("VehicleAlarmAttachment", "payloadDigest", attachment.getId(), "e".repeat(64));
            assertMappedDigest("VehicleAlarmAttachment", "payloadDigest", nullAttachment.getId(), null);
        });
    }

    @Test
    void postgresStillRejectsShortLongUppercaseAndSpacePaddedAttachmentDigests() {
        var fixture = onboardAlarmFixture(false, "GOOD", "ADAS");
        service.ingest(List.of(fixture.fact("ADAS", 1, "FORWARD_COLLISION", "CHAR-INVALID", "START")));
        UUID alarmId = jdbc.queryForObject("select id from vehicle_alarms where terminal_id=?",
                UUID.class, fixture.terminalId());
        record InvalidDigest(String value, String sqlState) { }
        for (var invalid : List.of(new InvalidDigest("a".repeat(63), "23514"),
                new InvalidDigest("a".repeat(65), "22001"),
                new InvalidDigest("A".repeat(64), "23514"),
                new InvalidDigest("a".repeat(63) + " ", "23514"))) {
            Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() ->
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        var attachment = VehicleAlarmAttachment.register(alarmId, "VIDEO", "1", "MP4",
                                "invalid.mp4", 128L, invalid.value(),
                                VehicleAlarmAttachment.Status.WAITING_MEDIA_SERVICE, Instant.now());
                        entityManager.persist(attachment);
                        entityManager.flush();
                    }));
            assertThat(failure).hasRootCauseInstanceOf(java.sql.SQLException.class);
            while (failure.getCause() != null) failure = failure.getCause();
            assertThat(((java.sql.SQLException) failure).getSQLState()).isEqualTo(invalid.sqlState());
        }
        assertThat(jdbc.queryForObject("select count(*) from vehicle_alarm_attachments where vehicle_alarm_id=?",
                Integer.class, alarmId)).isZero();
    }

    private void assertMappedDigest(String entity, String property, UUID id, String expected) {
        assertThat(entityManager.createQuery("select e." + property + " from " + entity + " e where e.id=:id",
                String.class).setParameter("id", id).getSingleResult()).isEqualTo(expected);
        if (expected != null) {
            assertThat(entityManager.createQuery("select e.id from " + entity
                    + " e where e.id=:id and e." + property + "=:digest", UUID.class)
                    .setParameter("id", id).setParameter("digest", expected).getResultList()).containsExactly(id);
        }
    }

    private static com.idavy.drtops.domain.location.VehicleLocationEvent mappingLocation(
            UUID vehicleId, UUID actor, String digest) {
        var at = java.time.OffsetDateTime.parse("2026-01-15T02:00:00Z");
        var event = com.idavy.drtops.domain.location.VehicleLocationEvent.record(vehicleId, null, null, null,
                com.idavy.drtops.domain.location.LocationEventType.TASK_STARTED,
                com.idavy.drtops.domain.location.LocationSource.MANUAL_DISPATCHER,
                "POINT(118 32)", new BigDecimal("118.0000000"), new BigDecimal("32.0000000"),
                "GCJ02", "Synthetic mapping fixture", at, at, actor, null, null, null,
                UUID.randomUUID(), "b".repeat(64), false, false);
        // Populate this optional field without invoking unrelated GPS authorization in a mapping test.
        org.springframework.test.util.ReflectionTestUtils.setField(event, "payloadDigest", digest);
        return event;
    }

    private static com.idavy.drtops.domain.terminal.JtGatewayAuditEvent mappingAudit(
            UUID terminalId, UUID vehicleId, String digest) {
        return com.idavy.drtops.domain.terminal.JtGatewayAuditEvent.record(terminalId, vehicleId,
                com.idavy.drtops.domain.terminal.JtGatewayAuditEvent.EventType.PROTOCOL_REJECTED,
                com.idavy.drtops.domain.terminal.JtGatewayAuditEvent.Result.REJECTED,
                "SYNTHETIC_MAPPING_TEST", "JT808_2019", 512, digest, null,
                java.time.OffsetDateTime.now(), "c1-mapping-test");
    }

    @Test
    void postgresC1LeaseAuditPersistsAndNoOpsStayIdempotent() throws Exception {
        OnboardAlarmFixture fixture = onboardAlarmFixture(false, "GOOD", "ADAS");
        int tokenVersion = jdbc.queryForObject("select auth_token_version from jt_terminals where id=?", Integer.class, fixture.terminalId());
        var first = leaseService.acquire(fixture.terminalId(), "c1-pg-synthetic", UUID.randomUUID(), tokenVersion);
        long beforeVersion = jdbc.queryForObject("select version from jt_terminal_session_leases where terminal_id=?", Long.class, fixture.terminalId());
        Thread.sleep(25);
        var renewed = leaseService.renew(first.owner()).orElseThrow();
        assertThat(renewed.expiresAt()).isAfter(first.expiresAt());
        assertThat(renewed.owner().leaseGeneration()).isEqualTo(first.owner().leaseGeneration());
        assertThat(jdbc.queryForObject("select version from jt_terminal_session_leases where terminal_id=?", Long.class, fixture.terminalId())).isEqualTo(beforeVersion+1);
        assertThat(leaseService.release(first.owner(), "SYNTHETIC_C1_RELEASE").status()).isEqualTo("RELEASED");
        assertThat(leaseService.release(first.owner(), "SYNTHETIC_C1_RELEASE").status()).isEqualTo("ALREADY_RELEASED");
        assertThat(leaseService.renew(first.owner())).isEmpty();
        assertThat(jdbc.queryForObject("select released_at from jt_terminal_session_leases where terminal_id=?", java.time.OffsetDateTime.class, fixture.terminalId())).isNotNull();
        assertThat(jdbc.queryForList("select action from audit_logs where entity_id=? and entity_type='JT_TERMINAL_SESSION_LEASE'", String.class, fixture.terminalId()))
                .containsExactlyInAnyOrder("SESSION_LEASE_RENEWED", "SESSION_LEASE_RELEASED");
        assertThat(jdbc.queryForList("select metadata_json ->> 'connectionId' from audit_logs where entity_id=?", String.class, fixture.terminalId()))
                .containsOnly(first.owner().connectionId().toString());
    }

    @Test
    void postgresC1DatabaseAuditFailureRollsBackLeaseAlarmAndReceipt() {
        OnboardAlarmFixture fixture = onboardAlarmFixture(false, "GOOD", "ADAS");
        int tokenVersion = jdbc.queryForObject("select auth_token_version from jt_terminals where id=?", Integer.class, fixture.terminalId());
        var lease = leaseService.acquire(fixture.terminalId(), "c1-pg-synthetic", UUID.randomUUID(), tokenVersion);
        var before = jdbc.queryForMap("select version,expires_at,released_at from jt_terminal_session_leases where terminal_id=?", fixture.terminalId());
        jdbc.execute("create function reject_c1_audit_for_test() returns trigger language plpgsql as $$ begin raise exception 'C1_SYNTHETIC_AUDIT_REJECT'; end $$");
        jdbc.execute("create trigger trg_reject_c1_audit_for_test before insert on audit_logs for each row execute function reject_c1_audit_for_test()");
        assertThatThrownBy(() -> leaseService.renew(lease.owner())).hasStackTraceContaining("C1_SYNTHETIC_AUDIT_REJECT");
        assertThatThrownBy(() -> leaseService.release(lease.owner(), "SYNTHETIC_C1_RELEASE")).hasStackTraceContaining("C1_SYNTHETIC_AUDIT_REJECT");
        assertThat(jdbc.queryForMap("select version,expires_at,released_at from jt_terminal_session_leases where terminal_id=?", fixture.terminalId())).isEqualTo(before);
        UUID key = UUID.randomUUID();
        var fact = fixture.fact("ADAS", 1, "FORWARD_COLLISION", "00000001", "START");
        assertThatThrownBy(() -> service.ingest(key, fact)).hasStackTraceContaining("C1_SYNTHETIC_AUDIT_REJECT");
        assertThat(jdbc.queryForObject("select count(*) from vehicle_alarms where terminal_id=?", Integer.class, fixture.terminalId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from jt_gateway_ingress_receipts where idempotency_key=?", Integer.class, key)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where entity_id=?", Integer.class, fixture.terminalId())).isZero();
        removeOutboxRejector();
        assertThat(service.ingest(key, fact).status()).isEqualTo("ACCEPTED");
        assertThat(service.ingest(key, fact).status()).isEqualTo("REPLAYED");
        assertThat(jdbc.queryForObject("select count(*) from audit_logs l join vehicle_alarms a on a.id=l.entity_id where a.terminal_id=? and l.action='VEHICLE_ALARM_CREATED'", Integer.class, fixture.terminalId())).isEqualTo(1);
    }

    @Test
    void persistsMultiAlarmReplayEndAndOutboxInOneDatabaseTransaction() {
        OnboardAlarmFixture fixture = onboardAlarmFixture(false, "QUARANTINED", "ADAS", "DMS");
        VehicleAlarmIngressService.AlarmFact adas = fixture.fact(
                "ADAS", 1, "FORWARD_COLLISION", "00000001", "START");
        VehicleAlarmIngressService.AlarmFact dms = fixture.fact(
                "DMS", 2, "PHONE", "00000002", "START");

        service.ingest(List.of(adas, dms));
        service.ingest(List.of(adas, dms));
        service.ingest(List.of(withTerminalIdentifier(
                adas.endAt(Instant.parse("2026-01-15T02:01:00Z")), "f".repeat(64))));
        assertThat(jdbc.queryForList("select l.action from audit_logs l join vehicle_alarms a on a.id=l.entity_id where a.terminal_id=?", String.class, fixture.terminalId()))
                .containsExactlyInAnyOrder("VEHICLE_ALARM_CREATED", "VEHICLE_ALARM_CREATED", "VEHICLE_ALARM_ENDED");
        assertThat(jdbc.queryForList("select l.metadata_json ->> 'terminalId' from audit_logs l join vehicle_alarms a on a.id=l.entity_id where a.terminal_id=?", String.class, fixture.terminalId()))
                .containsOnly(fixture.terminalId().toString());
        assertThat(jdbc.queryForList("select distinct l.metadata_json ->> 'module' from audit_logs l join vehicle_alarms a on a.id=l.entity_id where a.terminal_id=?", String.class, fixture.terminalId()))
                .containsExactlyInAnyOrder("ADAS", "DMS");


        assertThat(jdbc.queryForObject(
                "select count(*) from vehicle_alarms where terminal_id = ?",
                Integer.class, fixture.terminalId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                select count(*) from vehicle_alarm_outbox o
                join vehicle_alarms a on a.id = o.vehicle_alarm_id
                where a.terminal_id = ?
                """, Integer.class, fixture.terminalId())).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                select ended_at from vehicle_alarms
                where terminal_id = ? and terminal_alarm_identifier = '00000001'
                """, Instant.class, fixture.terminalId()))
                .isEqualTo(Instant.parse("2026-01-15T02:01:00Z"));
        assertThat(jdbc.queryForObject(
                "select count(distinct location_event_id) from vehicle_alarms where terminal_id = ?",
                Integer.class, fixture.terminalId())).isEqualTo(1);
        assertThat(jdbc.queryForList(
                "select onboard_system_id from vehicle_alarms where terminal_id = ?",
                UUID.class, fixture.terminalId())).containsOnly(fixture.onboardSystemId());
        assertThat(jdbc.queryForList(
                "select location_quality_status from vehicle_alarms where terminal_id = ?",
                String.class, fixture.terminalId())).containsOnly("QUARANTINED");
    }

    @Test
    void rollsBackTheFactWhenTheDatabaseRejectsItsOutboxWrite() {
        OnboardAlarmFixture fixture = onboardAlarmFixture(false, "GOOD", "ADAS");
        jdbc.execute("""
                create function reject_alarm_outbox_for_test() returns trigger language plpgsql as $$
                begin raise exception 'outbox test failure'; end; $$;
                create trigger trg_reject_alarm_outbox_for_test before insert on vehicle_alarm_outbox
                for each row execute function reject_alarm_outbox_for_test();
                """);
        try {
            assertThatThrownBy(() -> service.ingest(List.of(fixture.fact(
                    "ADAS", 1, "FORWARD_COLLISION", "00000003", "START"))))
                    .isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject(
                    "select count(*) from vehicle_alarms where terminal_alarm_identifier = '00000003'",
                    Integer.class)).isZero();
        } finally {
            removeOutboxRejector();
        }
    }

    @Test
    void atomicallyReplaysConcurrentStartsWithTheSameDeduplicationKey() throws Exception {
        OnboardAlarmFixture fixture = onboardAlarmFixture(false, "GOOD", "ADAS");
        VehicleAlarmIngressService.AlarmFact start = fixture.fact(
                "ADAS", 1, "FORWARD_COLLISION", "00000004", "START");

        runConcurrently(() -> service.ingest(List.of(start)));

        assertThat(jdbc.queryForObject(
                "select count(*) from vehicle_alarms where terminal_id = ?",
                Integer.class, fixture.terminalId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from vehicle_alarm_outbox o
                join vehicle_alarms a on a.id = o.vehicle_alarm_id
                where a.terminal_id = ?
                """, Integer.class, fixture.terminalId())).isEqualTo(1);
    }

    @Test
    void appendsOneOutboxEventForConcurrentEndsOfTheSameStart() throws Exception {
        OnboardAlarmFixture fixture = onboardAlarmFixture(false, "GOOD", "DMS");
        VehicleAlarmIngressService.AlarmFact start = fixture.fact(
                "DMS", 2, "PHONE", "00000005", "START");
        service.ingest(List.of(start));
        VehicleAlarmIngressService.AlarmFact end = start.endAt(
                Instant.parse("2026-01-15T02:01:00Z"));

        runConcurrently(() -> service.ingest(List.of(end)));

        assertThat(jdbc.queryForObject(
                "select count(*) from vehicle_alarms where terminal_id = ?",
                Integer.class, fixture.terminalId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from vehicle_alarm_outbox o
                join vehicle_alarms a on a.id = o.vehicle_alarm_id
                where a.terminal_id = ?
                """, Integer.class, fixture.terminalId())).isEqualTo(2);
    }

    @Test
    void secondActiveSafetyMemberWithoutLegacyBindingPersistsAlarm() {
        OnboardAlarmFixture fixture = onboardAlarmFixture(false, "GOOD", "ADAS");

        VehicleAlarmIngressService.Result result = service.ingest(
                fixture.alarmKey(),
                fixture.fact("ADAS", 1, "FORWARD_COLLISION", "00000006", "START"));

        assertThat(result.status()).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForMap("""
                select onboard_system_id, terminal_id, vehicle_id
                from vehicle_alarms where terminal_alarm_identifier = ?
                """, "00000006"))
                .containsEntry("onboard_system_id", fixture.onboardSystemId())
                .containsEntry("terminal_id", fixture.terminalId())
                .containsEntry("vehicle_id", fixture.vehicleId());
        assertThat(jdbc.queryForObject(
                "select count(*) from jt_terminal_vehicle_bindings where terminal_id = ?",
                Integer.class, fixture.terminalId())).isZero();
    }

    @Test
    void revokedRoleAndHistoricalLegacyBindingCannotAuthorizeAlarm() {
        OnboardAlarmFixture fixture = onboardAlarmFixture(true, "GOOD", "ADAS");
        revokeActiveSafetyBeforePositionRecordedAt(fixture);

        VehicleAlarmIngressService.Result result = service.ingest(
                fixture.alarmKey(),
                fixture.fact("ADAS", 1, "FORWARD_COLLISION", "00000007", "START"));

        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.reasonCodes()).containsExactly("ACTIVE_SAFETY_AUTHORITY_MISMATCH");
        assertThat(jdbc.queryForObject(
                "select count(*) from vehicle_alarms where terminal_alarm_identifier = ?",
                Integer.class, "00000007")).isZero();
    }

    @Test
    void alarmCannotCrossOnboardSystemThroughAReusedVehicle() {
        OnboardAlarmFixture source = onboardAlarmFixture(false, "GOOD", "ADAS");
        OnboardAlarmFixture other = onboardAlarmFixture(false, "GOOD", "ADAS");
        UUID forgedPosition = acceptedPosition(
                source.terminalId(), other.onboardSystemId(), source.vehicleId(),
                "GOOD", AUTHORIZED_AT);
        VehicleAlarmIngressService.AlarmFact forged = source.factAt(
                other.onboardSystemId(), forgedPosition,
                "ADAS", 1, "FORWARD_COLLISION", "00000008", "START");

        VehicleAlarmIngressService.Result result = service.ingest(UUID.randomUUID(), forged);

        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.reasonCodes()).containsExactly("ACTIVE_SAFETY_AUTHORITY_MISMATCH");
    }

    @Test
    void dmsAlarmRequiresVerifiedDmsNotOnlyVerifiedAdas() {
        OnboardAlarmFixture fixture = onboardAlarmFixture(false, "GOOD", "ADAS");

        VehicleAlarmIngressService.Result result = service.ingest(
                fixture.alarmKey(), fixture.fact(
                        "DMS", 2, "PHONE", "00000009", "START"));

        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.reasonCodes()).containsExactly("ACTIVE_SAFETY_AUTHORITY_MISMATCH");
    }

    @Test
    void configurationAndAlarmAuthorizationSerializeToOneWholeOutcome() throws Exception {
        OnboardAlarmFixture fixture = onboardAlarmFixture(false, "GOOD", "ADAS");
        CountDownLatch systemLocked = new CountDownLatch(1);
        CountDownLatch releaseConfiguration = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<?> configuration = workers.submit(() -> new TransactionTemplate(
                    transactionManager).executeWithoutResult(status -> {
                jdbc.queryForObject(
                        "select id from onboard_systems where id = ? for update",
                        UUID.class, fixture.onboardSystemId());
                systemLocked.countDown();
                await(releaseConfiguration);
                revokeActiveSafetyBeforePositionRecordedAt(fixture);
                jdbc.update("""
                        update onboard_systems
                        set version = version + 1, updated_at = now()
                        where id = ?
                        """, fixture.onboardSystemId());
            }));
            assertThat(systemLocked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<VehicleAlarmIngressService.Result> alarm = workers.submit(() -> service.ingest(
                    fixture.alarmKey(),
                    fixture.fact("ADAS", 1, "FORWARD_COLLISION", "00000010", "START")));

            assertThat(completedWithin(alarm, 300)).isFalse();
            releaseConfiguration.countDown();
            configuration.get();
            VehicleAlarmIngressService.Result result = alarm.get();

            assertThat(result.status()).isEqualTo("REJECTED");
            assertThat(result.reasonCodes()).containsExactly("ACTIVE_SAFETY_AUTHORITY_MISMATCH");
            assertThat(jdbc.queryForObject(
                    "select count(*) from vehicle_alarms where terminal_alarm_identifier = ?",
                    Integer.class, "00000010")).isZero();
            assertThat(jdbc.queryForObject("""
                    select status from onboard_device_role_assignments
                    where onboard_system_id = ? and role = 'ACTIVE_SAFETY'
                    """, String.class, fixture.onboardSystemId())).isEqualTo("REVOKED");
        } finally {
            releaseConfiguration.countDown();
        }
    }

    @Test
    void endReplayUsesCurrentOnboardSystemWhenAnotherSystemHasALaterHistory() {
        OnboardAlarmFixture fixture = onboardAlarmFixture(false, "GOOD", "ADAS");
        VehicleAlarmIngressService.AlarmFact currentStart = fixture.fact(
                "ADAS", 1, "FORWARD_COLLISION", "00000011", "START");
        service.ingest(List.of(currentStart));
        Instant firstEnd = AUTHORIZED_AT.plusSeconds(60);
        service.ingest(List.of(currentStart.endAt(firstEnd)));
        UUID historicalSystemId = historicalOnboardSystem(
                fixture.vehicleId(), AUTHORIZED_AT.minusSeconds(7200));
        persistHistoricalAlarm(
                fixture,
                historicalSystemId,
                AUTHORIZED_AT.plusSeconds(120),
                AUTHORIZED_AT.plusSeconds(150));

        VehicleAlarmIngressService.Result result = service.ingest(
                UUID.randomUUID(),
                currentStart.endAt(AUTHORIZED_AT.plusSeconds(180)));

        assertThat(result.status()).isEqualTo("REPLAYED");
        assertThat(result.reasonCodes()).isEmpty();
        assertThat(jdbc.queryForObject("""
                select ended_at from vehicle_alarms
                where onboard_system_id = ? and terminal_alarm_identifier = ?
                """, Instant.class, fixture.onboardSystemId(), "00000011"))
                .isEqualTo(firstEnd);
    }

    @Test
    void startFailsClosedWhenAnotherOnboardSystemKeepsTheGlobalLifecycleOpen() {
        OnboardAlarmFixture fixture = onboardAlarmFixture(false, "GOOD", "ADAS");
        UUID historicalSystemId = historicalOnboardSystem(
                fixture.vehicleId(), AUTHORIZED_AT.minusSeconds(7200));
        persistHistoricalAlarm(
                fixture,
                historicalSystemId,
                AUTHORIZED_AT.minusSeconds(120),
                null);
        AtomicReference<VehicleAlarmIngressService.Result> observed = new AtomicReference<>();

        assertThatCode(() -> observed.set(service.ingest(
                UUID.randomUUID(),
                fixture.fact(
                        "ADAS", 1, "FORWARD_COLLISION", "00000012", "START"))))
                .doesNotThrowAnyException();

        assertThat(observed.get().status()).isEqualTo("REJECTED");
        assertThat(observed.get().reasonCodes()).containsExactly("ALARM_STATE_INVALID");
        assertThat(jdbc.queryForObject("""
                select count(*) from vehicle_alarms
                where terminal_id = ? and vehicle_id = ? and ended_at is null
                """, Integer.class, fixture.terminalId(), fixture.vehicleId())).isEqualTo(1);
    }

    private OnboardAlarmFixture onboardAlarmFixture(
            boolean legacyBinding, String qualityStatus, String... verifiedCapabilities) {
        if (legacyBinding) {
            UUID positionKey = acceptedPosition(
                    LEGACY_TERMINAL_ID, LEGACY_SYSTEM_ID, LEGACY_VEHICLE_ID,
                    qualityStatus, AUTHORIZED_AT);
            return new OnboardAlarmFixture(
                    UUID.randomUUID(), LEGACY_TERMINAL_ID, LEGACY_SYSTEM_ID,
                    LEGACY_VEHICLE_ID, positionKey);
        }
        UUID terminalId = terminal();
        UUID vehicleId = vehicle();
        UUID onboardSystemId = UUID.randomUUID();
        Instant configuredAt = AUTHORIZED_AT.minusSeconds(3600);
        jdbc.update("""
                insert into onboard_systems (
                  id, vehicle_id, status, operating_mode, created_at, updated_at, version
                ) values (?, ?, 'ACTIVE', 'SAFETY_MONITOR_ONLY', ?, ?, 1)
                """, onboardSystemId, vehicleId, offset(configuredAt), offset(configuredAt));
        jdbc.update("""
                insert into onboard_device_memberships (
                  id, onboard_system_id, terminal_id, network_mode, status, valid_from,
                  added_reason, created_at, updated_at, version
                ) values (?, ?, ?, 'DIRECT_CELLULAR', 'ACTIVE', ?,
                  'R2 alarm membership', ?, ?, 0)
                """, UUID.randomUUID(), onboardSystemId, terminalId, offset(configuredAt),
                offset(configuredAt), offset(configuredAt));
        jdbc.update("""
                insert into onboard_device_role_assignments (
                  id, onboard_system_id, terminal_id, role, status, valid_from,
                  assigned_reason, created_at, updated_at, version
                ) values (?, ?, ?, 'ACTIVE_SAFETY', 'ACTIVE', ?,
                  'R2 alarm role', ?, ?, 0)
                """, UUID.randomUUID(), onboardSystemId, terminalId, offset(configuredAt),
                offset(configuredAt), offset(configuredAt));
        jdbc.update("""
                insert into onboard_device_protocol_profiles (
                  id, terminal_id, transport_profile, business_profile, safety_profile,
                  media_profile, active_position_interval_seconds,
                  idle_position_interval_seconds, status, valid_from, reason,
                  created_at, updated_at, version
                ) values (?, ?, 'JT808_2019', 'NONE', 'JSATL12_2017', 'NONE',
                  30, 60, 'ACTIVE', ?, 'R2 alarm profile', ?, ?, 0)
                """, UUID.randomUUID(), terminalId, offset(configuredAt),
                offset(configuredAt), offset(configuredAt));
        for (String capability : verifiedCapabilities) {
            jdbc.update("""
                    insert into onboard_device_capabilities (
                      id, terminal_id, capability, status, evidence_ref, verified_at,
                      verified_by, reason, created_at, updated_at, version
                    ) values (?, ?, ?, 'VERIFIED', 'R2-TEST-EVIDENCE', ?,
                      (select id from user_accounts order by id limit 1),
                      'R2 alarm capability', ?, ?, 0)
                    """, UUID.randomUUID(), terminalId, capability, offset(configuredAt),
                    offset(configuredAt), offset(configuredAt));
        }
        UUID positionKey = acceptedPosition(
                terminalId, onboardSystemId, vehicleId, qualityStatus, AUTHORIZED_AT);
        return new OnboardAlarmFixture(
                UUID.randomUUID(), terminalId, onboardSystemId, vehicleId, positionKey);
    }

    private UUID terminal() {
        UUID id = UUID.randomUUID();
        String compact = id.toString().replace("-", "");
        String terminalPhone = "PG-" + compact.substring(0, 24);
        String terminalCode = "PG-CODE-" + compact;
        jdbc.update("""
                insert into jt_terminals (
                  id, terminal_phone, terminal_phone_identity, terminal_code,
                  manufacturer_id, model,
                  protocol_version, source_coordinate_system, active_safety_modules,
                  jt1078_enabled, status, auth_token_hash, auth_token_version,
                  created_at, updated_at
                ) values (?, ?, ?, ?, 'TEST', 'TEST', 'JT808-2019', 'WGS84',
                  '["ADAS","DMS"]'::jsonb, false, 'ACTIVE', repeat('a',64), 1,
                  now(), now())
                """, id, terminalPhone, terminalPhone, terminalCode);
        return id;
    }

    private UUID historicalOnboardSystem(UUID vehicleId, Instant createdAt) {
        UUID onboardSystemId = UUID.randomUUID();
        jdbc.update("""
                insert into onboard_systems (
                  id, vehicle_id, status, operating_mode, created_at, updated_at, version
                ) values (?, ?, 'RETIRED', 'SAFETY_MONITOR_ONLY', ?, ?, 1)
                """, onboardSystemId, vehicleId, offset(createdAt), offset(createdAt));
        return onboardSystemId;
    }

    private VehicleAlarm persistHistoricalAlarm(
            OnboardAlarmFixture fixture,
            UUID onboardSystemId,
            Instant occurredAt,
            Instant endedAt) {
        VehicleAlarmIngressService.AlarmFact fact = withOnboardSystemAndOccurredAt(
                fixture.fact(
                        "ADAS", 1, "FORWARD_COLLISION", "HISTORICAL", "START"),
                onboardSystemId,
                occurredAt);
        UUID locationEventId = jdbc.queryForObject(
                "select id from vehicle_location_events where idempotency_key = ?",
                UUID.class,
                fixture.positionKey());
        VehicleAlarm alarm = VehicleAlarm.start(
                fact,
                UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID().toString().replace("-", ""),
                new AlarmStore.LocationReference(
                        locationEventId,
                        onboardSystemId,
                        AUTHORIZED_AT,
                        "GOOD",
                        "[]"));
        if (endedAt != null) {
            alarm.endAt(endedAt);
        }
        return alarms.saveAndFlush(alarm);
    }

    private UUID vehicle() {
        UUID id = UUID.randomUUID();
        vehicles.saveAndFlush(Vehicle.create(
                id,
                "ALM-" + id.toString().substring(0, 8),
                "Microbus",
                8,
                "IDLE",
                "POINT(118 32)",
                "R2 alarm test fleet",
                false));
        return id;
    }

    private UUID acceptedPosition(
            UUID terminalId,
            UUID onboardSystemId,
            UUID vehicleId,
            String qualityStatus,
            Instant recordedAt) {
        UUID eventId = UUID.randomUUID();
        UUID positionKey = UUID.randomUUID();
        jdbc.update("""
                insert into vehicle_location_events (
                  id, vehicle_id, event_type, source, location, longitude, latitude,
                  coordinate_system, driver_reported_at, recorded_at, idempotency_key,
                  request_fingerprint, snapshot_applied, outside_service_area,
                  terminal_id, onboard_system_id, source_role, protocol_version,
                  message_serial_no, raw_longitude, raw_latitude,
                  raw_coordinate_system, gateway_received_at, payload_digest,
                  coordinate_transform_version, quality_status, quality_reasons
                ) values (?, ?, 'GPS_REPORT', 'GPS_DEVICE',
                  ST_GeogFromText('SRID=4326;POINT(118 32)'),
                  118.0000000, 32.0000000, 'GCJ02', ?, ?, ?, repeat('b',64),
                  false, false, ?, ?, 'LOCATION_PRIMARY', 'JT808_2019', 1,
                  118.0000000, 32.0000000, 'WGS84', ?, repeat('c',64),
                  'WGS84_TO_GCJ02_V1', ?, '[]'::jsonb)
                """, eventId, vehicleId, offset(recordedAt), offset(recordedAt),
                positionKey, terminalId, onboardSystemId, offset(recordedAt), qualityStatus);
        jdbc.update("""
                insert into jt_gateway_ingress_receipts (
                  idempotency_key, final_status, reason_codes, created_at, completed_at,
                  terminal_id, vehicle_id, ingress_kind
                ) values (?, 'ACCEPTED', '[]'::jsonb, now(), now(), ?, ?, 'LOCATION')
                """, positionKey, terminalId, vehicleId);
        return positionKey;
    }

    private void revokeActiveSafetyBeforePositionRecordedAt(OnboardAlarmFixture fixture) {
        Instant revokedAt = AUTHORIZED_AT.minusSeconds(1);
        jdbc.update("""
                update onboard_device_role_assignments
                set status = 'REVOKED', valid_to = ?,
                    revoked_reason = 'R2 test revoke', updated_at = ?
                where onboard_system_id = ? and terminal_id = ?
                  and role = 'ACTIVE_SAFETY' and status = 'ACTIVE'
                """, offset(revokedAt), offset(revokedAt),
                fixture.onboardSystemId(), fixture.terminalId());
    }

    private static VehicleAlarmIngressService.AlarmFact withTerminalIdentifier(
            VehicleAlarmIngressService.AlarmFact fact, String terminalAlarmIdentifier) {
        return new VehicleAlarmIngressService.AlarmFact(
                fact.terminalId(), fact.onboardSystemId(), fact.vehicleId(),
                fact.standard(), fact.module(), fact.typeCode(), fact.alarmType(),
                fact.terminalAlarmId(), fact.state(), fact.level(), terminalAlarmIdentifier,
                fact.occurredAt(), fact.gatewayReceivedAt(), fact.longitude(), fact.latitude(),
                fact.speedKph(), fact.positionIdempotencyKey(),
                fact.locationQualityStatus(), fact.payloadDigest());
    }

    private static VehicleAlarmIngressService.AlarmFact withOnboardSystemAndOccurredAt(
            VehicleAlarmIngressService.AlarmFact fact,
            UUID onboardSystemId,
            Instant occurredAt) {
        return new VehicleAlarmIngressService.AlarmFact(
                fact.terminalId(), onboardSystemId, fact.vehicleId(), fact.standard(),
                fact.module(), fact.typeCode(), fact.alarmType(), fact.terminalAlarmId(),
                fact.state(), fact.level(), fact.terminalAlarmIdentifier(), occurredAt,
                occurredAt.plusSeconds(1), fact.longitude(), fact.latitude(), fact.speedKph(),
                fact.positionIdempotencyKey(), fact.locationQualityStatus(), fact.payloadDigest());
    }

    private static void runConcurrently(Runnable operation) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch proceed = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            List<? extends Future<?>> calls = java.util.stream.IntStream.range(0, 2)
                    .mapToObj(ignored -> workers.submit(() -> {
                        ready.countDown();
                        await(proceed);
                        operation.run();
                    }))
                    .toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            proceed.countDown();
            for (Future<?> call : calls) {
                call.get();
            }
        }
    }

    private static boolean completedWithin(Future<?> future, long milliseconds) throws Exception {
        try {
            future.get(milliseconds, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException expected) {
            return false;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("R2 test latch timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("R2 test interrupted", interrupted);
        }
    }

    private static java.time.OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    static boolean integrationEnvironmentAvailable() {
        if (!Boolean.getBoolean(MASTER_PROPERTY)) {
            return false;
        }
        if (Boolean.getBoolean(EXTERNAL_PROPERTY)) {
            return externalPostgres() != null;
        }
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static ExternalPostgres externalPostgres() {
        if (!Boolean.getBoolean(EXTERNAL_PROPERTY)) {
            return null;
        }
        String jdbcUrl = System.getProperty(EXTERNAL_JDBC_URL_PROPERTY, "");
        String username = System.getProperty(EXTERNAL_USERNAME_PROPERTY, "");
        String password = System.getProperty(EXTERNAL_PASSWORD_PROPERTY, "");
        if (!jdbcUrl.matches(EXTERNAL_JDBC_URL_PATTERN)
                || !EXTERNAL_USERNAME.equals(username)
                || password.isBlank()) {
            return null;
        }
        return new ExternalPostgres(jdbcUrl, username, password);
    }

    private static void prepareV20Contract(ExternalPostgres database) {
        Flyway.configure()
                .dataSource(database.jdbcUrl(), database.username(), database.password())
                .locations("classpath:db/migration")
                .target("19")
                .load()
                .migrate();
        try (var connection = DriverManager.getConnection(
                database.jdbcUrl(), database.username(), database.password());
                var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    insert into user_accounts (
                      id, username, display_name, password_hash, enabled,
                      token_version, must_change_password, created_at, updated_at
                    ) values (
                      '11111111-1111-1111-1111-111111111111',
                      'r2-alarm-authority-fixture', 'R2 alarm authority fixture',
                      'DISABLED_TEST_IDENTITY', false, 0, true, now(), now()
                    ) on conflict (id) do nothing
                    """);
            statement.executeUpdate("update vehicles set dispatchable = false where dispatchable");
            statement.executeUpdate("""
                    update jt_terminals terminal
                    set status = 'SUSPENDED', updated_at = now()
                    where terminal.status = 'ACTIVE'
                      and not exists (
                        select 1
                        from onboard_device_memberships membership
                        join onboard_systems system
                          on system.id = membership.onboard_system_id
                        where membership.terminal_id = terminal.id
                          and membership.status = 'ACTIVE'
                          and membership.valid_to is null
                          and system.status = 'ACTIVE'
                      )
                    """);
            insertPreV20LegacyFixture(statement);
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException(
                    "failed to prepare isolated alarm authority database", failure);
        }
    }

    private static void insertPreV20LegacyFixture(java.sql.Statement statement)
            throws java.sql.SQLException {
        statement.executeUpdate("""
                insert into vehicles (
                  id, plate_number, vehicle_type, capacity, current_status,
                  current_location, fleet_name, dispatchable, created_at
                ) values (
                  'aaaaaaaa-3333-3333-3333-333333333333',
                  'R2-LEGACY-HISTORY', 'Microbus', 8, 'IDLE',
                  ST_GeogFromText('SRID=4326;POINT(118 32)'),
                  'R2 alarm fixture', false, now()
                )
                """);
        statement.executeUpdate("""
                insert into jt_terminals (
                  id, terminal_phone, terminal_phone_identity, terminal_code,
                  manufacturer_id, model, protocol_version,
                  source_coordinate_system, active_safety_modules,
                  jt1078_enabled, status, auth_token_hash,
                  auth_token_version, created_at, updated_at
                ) values (
                  'aaaaaaaa-1111-1111-1111-111111111111',
                  'R2-LEGACY-PHONE', 'R2-LEGACY-PHONE', 'R2-LEGACY-TERMINAL',
                  'TEST', 'TEST', 'JT808-2019', 'WGS84',
                  '["ADAS"]'::jsonb, false, 'ACTIVE', repeat('a', 64),
                  1, now(), now()
                )
                """);
        statement.executeUpdate("""
                insert into onboard_systems (
                  id, vehicle_id, status, operating_mode,
                  created_at, updated_at, version
                ) values (
                  'aaaaaaaa-2222-2222-2222-222222222222',
                  'aaaaaaaa-3333-3333-3333-333333333333',
                  'ACTIVE', 'SAFETY_MONITOR_ONLY',
                  TIMESTAMPTZ '2026-01-15 01:00:00+00',
                  TIMESTAMPTZ '2026-01-15 01:00:00+00', 1
                )
                """);
        statement.executeUpdate("""
                insert into onboard_device_memberships (
                  id, onboard_system_id, terminal_id, network_mode, status,
                  valid_from, added_reason, created_at, updated_at, version
                ) values (
                  'aaaaaaaa-4444-2222-2222-222222222222',
                  'aaaaaaaa-2222-2222-2222-222222222222',
                  'aaaaaaaa-1111-1111-1111-111111111111',
                  'DIRECT_CELLULAR', 'ACTIVE',
                  TIMESTAMPTZ '2026-01-15 01:00:00+00',
                  'R2 pre-V20 membership',
                  TIMESTAMPTZ '2026-01-15 01:00:00+00',
                  TIMESTAMPTZ '2026-01-15 01:00:00+00', 0
                )
                """);
        statement.executeUpdate("""
                insert into onboard_device_role_assignments (
                  id, onboard_system_id, terminal_id, role, status,
                  valid_from, assigned_reason, created_at, updated_at, version
                ) values (
                  'aaaaaaaa-5555-2222-2222-222222222222',
                  'aaaaaaaa-2222-2222-2222-222222222222',
                  'aaaaaaaa-1111-1111-1111-111111111111',
                  'ACTIVE_SAFETY', 'ACTIVE',
                  TIMESTAMPTZ '2026-01-15 01:00:00+00',
                  'R2 pre-V20 role',
                  TIMESTAMPTZ '2026-01-15 01:00:00+00',
                  TIMESTAMPTZ '2026-01-15 01:00:00+00', 0
                )
                """);
        statement.executeUpdate("""
                insert into onboard_device_protocol_profiles (
                  id, terminal_id, transport_profile, business_profile,
                  safety_profile, media_profile,
                  active_position_interval_seconds,
                  idle_position_interval_seconds, status, valid_from,
                  reason, created_at, updated_at, version
                ) values (
                  'aaaaaaaa-6666-2222-2222-222222222222',
                  'aaaaaaaa-1111-1111-1111-111111111111',
                  'JT808_2019', 'NONE', 'JSATL12_2017', 'NONE',
                  30, 60, 'ACTIVE',
                  TIMESTAMPTZ '2026-01-15 01:00:00+00',
                  'R2 pre-V20 profile',
                  TIMESTAMPTZ '2026-01-15 01:00:00+00',
                  TIMESTAMPTZ '2026-01-15 01:00:00+00', 0
                )
                """);
        statement.executeUpdate("""
                insert into onboard_device_capabilities (
                  id, terminal_id, capability, status, evidence_ref,
                  verified_at, verified_by, reason,
                  created_at, updated_at, version
                ) values (
                  'aaaaaaaa-7777-2222-2222-222222222222',
                  'aaaaaaaa-1111-1111-1111-111111111111',
                  'ADAS', 'VERIFIED', 'R2-PRE-V20-EVIDENCE',
                  TIMESTAMPTZ '2026-01-15 01:00:00+00',
                  '11111111-1111-1111-1111-111111111111',
                  'R2 pre-V20 capability',
                  TIMESTAMPTZ '2026-01-15 01:00:00+00',
                  TIMESTAMPTZ '2026-01-15 01:00:00+00', 0
                )
                """);
        statement.executeUpdate("""
                insert into jt_terminal_vehicle_bindings (
                  id, terminal_id, vehicle_id, valid_from, status,
                  binding_reason, created_at, updated_at
                ) values (
                  'aaaaaaaa-8888-2222-2222-222222222222',
                  'aaaaaaaa-1111-1111-1111-111111111111',
                  'aaaaaaaa-3333-3333-3333-333333333333',
                  TIMESTAMPTZ '2026-01-15 01:00:00+00', 'ACTIVE',
                  'R2 pre-V20 legacy history',
                  TIMESTAMPTZ '2026-01-15 01:00:00+00',
                  TIMESTAMPTZ '2026-01-15 01:00:00+00'
                )
                """);
    }

    private static synchronized PostgreSQLContainer<?> postgres() {
        if (postgres == null) {
            postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.5")
                    .asCompatibleSubstituteFor("postgres"))
                    .withStartupTimeout(Duration.ofMinutes(5));
        }
        return postgres;
    }

    private record ExternalPostgres(
            String jdbcUrl, String username, String password) {
    }

    private record OnboardAlarmFixture(
            UUID alarmKey,
            UUID terminalId,
            UUID onboardSystemId,
            UUID vehicleId,
            UUID positionKey) {

        VehicleAlarmIngressService.AlarmFact fact(
                String module,
                int code,
                String type,
                String identifier,
                String state) {
            return factAt(
                    onboardSystemId, positionKey, module, code, type, identifier, state);
        }

        VehicleAlarmIngressService.AlarmFact factAt(
                UUID factOnboardSystemId,
                UUID factPositionKey,
                String module,
                int code,
                String type,
                String identifier,
                String state) {
            return new VehicleAlarmIngressService.AlarmFact(
                    terminalId,
                    factOnboardSystemId,
                    vehicleId,
                    "T/JSATL12-2017",
                    module,
                    code,
                    type,
                    0x00001000L + code,
                    state,
                    1,
                    identifier,
                    AUTHORIZED_AT,
                    AUTHORIZED_AT.plusSeconds(1),
                    new BigDecimal("118.0000000"),
                    new BigDecimal("32.0000000"),
                    new BigDecimal("60.00"),
                    factPositionKey,
                    "UNASSESSED",
                    "a".repeat(64));
        }
    }
}
