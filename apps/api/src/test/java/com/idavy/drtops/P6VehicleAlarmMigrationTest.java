package com.idavy.drtops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class P6VehicleAlarmMigrationTest {
    @Test
    void createsTheCompleteAlarmDomainWithImmutableFactsAndRequiredConstraints() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("drt.integration.postgis"));
        String externalUrl = System.getProperty("drt.integration.postgis.jdbc-url");
        if (externalUrl != null && !externalUrl.isBlank()) {
            String username = System.getProperty("drt.integration.postgis.username");
            String password = System.getProperty("drt.integration.postgis.password");
            requireEphemeralExternalDatabase(externalUrl, username, password);
            verifyCompleteAlarmDomain(externalUrl, username, password, uniqueSchema("v16_complete"));
            return;
        }
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable());
        try (PostgreSQLContainer<?> database = new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.5")
                .asCompatibleSubstituteFor("postgres"))
                .withStartupTimeout(Duration.ofMinutes(5))) {
            database.start();
            verifyCompleteAlarmDomain(database.getJdbcUrl(), database.getUsername(), database.getPassword(), null);
        }
    }

    private static void verifyCompleteAlarmDomain(
            String jdbcUrl, String username, String password, String schema) throws Exception {
            flyway(jdbcUrl, username, password, schema, null).migrate();
            try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
                setSearchPath(connection, schema);
                for (String table : new String[] {"vehicle_alarms", "vehicle_alarm_actions", "vehicle_alarm_attachments", "vehicle_alarm_attachment_transfers", "vehicle_alarm_outbox"}) {
                    assertThat(tableExists(connection, table)).as(table).isTrue();
                }
                assertThat(indexExists(connection, "uq_vehicle_alarms_deduplication_key")).isTrue();
                assertThat(indexExists(connection, "idx_vehicle_alarms_occurred_at_brin")).isTrue();
                assertThat(indexExists(connection, "idx_vehicle_alarms_vehicle_occurred_at")).isTrue();
                assertThat(indexExists(connection, "idx_vehicle_alarms_status_occurred_at")).isTrue();
                assertThat(indexExists(connection, "uq_vehicle_alarm_attachment_transfers_active")).isTrue();
                assertThat(indexExists(connection, "idx_vehicle_alarm_attachments_status_created_at")).isTrue();
                assertThat(indexExists(connection, "idx_vehicle_alarm_outbox_created_at_brin")).isTrue();
                assertThat(indexExists(connection, "idx_vehicle_alarm_outbox_status_created_at")).isTrue();
                assertThat(indexDefinition(connection, "idx_vehicle_alarm_actions_alarm_created_at"))
                        .contains("USING btree (vehicle_alarm_id, created_at DESC)");
                assertThat(triggerExists(connection, "trg_vehicle_alarms_immutable_facts")).isTrue();
                assertThat(triggerExists(connection, "trg_vehicle_alarm_actions_append_only")).isTrue();
                assertThat(columnExists(connection, "vehicle_alarm_outbox", "vehicle_alarm_id")).isTrue();
                assertThat(columnExists(connection, "vehicle_alarm_outbox", "event_type")).isTrue();
                assertThat(columnExists(connection, "vehicle_alarm_outbox", "status")).isTrue();
                assertThat(columnExists(connection, "vehicle_alarms", "location_quality_reasons")).isTrue();
                assertThat(columnExists(connection, "vehicle_alarms", "terminal_alarm_id")).isTrue();
                assertThat(columnExists(connection, "vehicle_alarms", "public_id")).isTrue();
                assertThat(indexExists(connection, "uq_vehicle_alarms_public_id")).isTrue();
                assertThat(indexExists(connection, "uq_vehicle_alarms_open_source_alarm")).isTrue();
                assertThat(columnExists(connection, "vehicle_alarm_attachments", "channel")).isTrue();
                assertThat(foreignKeyExists(connection, "vehicle_alarm_outbox", "vehicle_alarm_id", "vehicle_alarms")).isTrue();
                assertCanonicalModuleBoundary(connection);
                assertFactsAreDeduplicatedAndImmutable(connection);
                assertActionsAreAppendOnly(connection);
                assertOnlyOneAttachmentTransferCanBeActive(connection);
                assertOutboxHasRequiredForeignKeyAndStatusBoundary(connection);
            }
    }

    @Test
    void upgradesExistingV15AlarmRowsWithIndependentImmutablePublicIds() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("drt.integration.postgis"));
        String externalUrl = System.getProperty("drt.integration.postgis.jdbc-url");
        if (externalUrl != null && !externalUrl.isBlank()) {
            String externalUsername = System.getProperty("drt.integration.postgis.username");
            String externalPassword = System.getProperty("drt.integration.postgis.password");
            requireEphemeralExternalDatabase(externalUrl, externalUsername, externalPassword);
            verifyV15Upgrade(externalUrl, externalUsername, externalPassword, uniqueSchema("v16_upgrade"));
            return;
        }
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable());
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            verifyV15Upgrade(database.getJdbcUrl(), database.getUsername(), database.getPassword(), null);
        }
    }

    private static void requireEphemeralExternalDatabase(String jdbcUrl, String username, String password)
            throws SQLException {
        if (!Boolean.getBoolean("drt.integration.postgis.external-ephemeral")
                || !"task12".equals(username)
                || !jdbcUrl.matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):\\d+/task12")) {
            throw new IllegalArgumentException("external migration tests require an explicit loopback task12 database");
        }
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             var query = connection.prepareStatement("""
                     select table_name
                     from information_schema.tables
                     where table_schema = 'public'
                       and table_type = 'BASE TABLE'
                       and table_name <> 'spatial_ref_sys'
                     order by table_name
                     """);
             var rows = query.executeQuery()) {
            List<String> unexpectedTables = new ArrayList<>();
            while (rows.next()) unexpectedTables.add(rows.getString(1));
            if (!unexpectedTables.isEmpty()) {
                throw new IllegalArgumentException(
                        "external task12 database public schema is not empty: " + unexpectedTables);
            }
        }
    }

    @Test
    void rejectsUnsupportedOrAmbiguousLegacyOutboxFactsBeforeV16Backfill() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("drt.integration.postgis"));
        String externalUrl = System.getProperty("drt.integration.postgis.jdbc-url");
        if (externalUrl != null && !externalUrl.isBlank()) {
            String username = System.getProperty("drt.integration.postgis.username");
            String password = System.getProperty("drt.integration.postgis.password");
            requireEphemeralExternalDatabase(externalUrl, username, password);
            verifyInvalidV16Backfills(externalUrl, username, password);
            return;
        }
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable());
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            verifyInvalidV16Backfills(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        }
    }

    private static void verifyInvalidV16Backfills(String jdbcUrl, String username, String password) {
        String run = Long.toUnsignedString(System.nanoTime(), 36);
        assertAll(
                    () -> assertV16BackfillRejected(jdbcUrl, username, password, "v16_unknown_event_" + run,
                            "V16 cannot backfill an unsupported legacy vehicle alarm outbox event type", connection -> {
                        Fixture fixture = legacyAlarm(connection);
                        insertLegacyOutbox(connection, fixture.alarmId(), "NOT_SUPPORTED", Instant.now());
                    }),
                    () -> assertV16BackfillRejected(jdbcUrl, username, password, "v16_missing_action_" + run,
                            "V16 cannot unambiguously backfill a legacy alarm status change", connection -> {
                        Fixture fixture = legacyAlarm(connection);
                        insertLegacyOutbox(connection, fixture.alarmId(), "ALARM_STATUS_CHANGED", Instant.now());
                    }),
                    () -> assertV16BackfillRejected(jdbcUrl, username, password, "v16_tied_latest_action_" + run,
                            "V16 cannot unambiguously backfill a legacy alarm status change", connection -> {
                        Fixture fixture = legacyAlarm(connection);
                        Instant actionAt = Instant.parse("2026-08-14T10:00:00Z");
                        insertLegacyAction(connection, fixture.alarmId(), "ACKNOWLEDGE", "NEW", "ACKNOWLEDGED", actionAt);
                        insertLegacyAction(connection, fixture.alarmId(), "TAKE_OVER", "ACKNOWLEDGED", "PROCESSING", actionAt);
                        insertLegacyOutbox(connection, fixture.alarmId(), "ALARM_STATUS_CHANGED", actionAt.plusSeconds(1));
                    }),
                    () -> assertV16BackfillRejected(jdbcUrl, username, password, "v16_illegal_status_" + run,
                            "V16 cannot unambiguously backfill a legacy alarm status change", connection -> {
                        Fixture fixture = legacyAlarm(connection);
                        Instant actionAt = Instant.parse("2026-08-14T10:00:00Z");
                        insertLegacyAction(connection, fixture.alarmId(), "BROKEN", "NEW", "NOT_A_STATUS", actionAt);
                        insertLegacyOutbox(connection, fixture.alarmId(), "ALARM_STATUS_CHANGED", actionAt.plusSeconds(1));
                    }),
                    () -> assertV16BackfillRejected(jdbcUrl, username, password, "v16_ended_illegal_status_" + run,
                            "V16 cannot unambiguously backfill a legacy alarm ended event", connection -> {
                        Fixture fixture = legacyAlarm(connection);
                        Instant actionAt = Instant.parse("2026-08-14T10:00:00Z");
                        insertLegacyAction(connection, fixture.alarmId(), "BROKEN", "NEW", "NOT_A_STATUS", actionAt);
                        insertLegacyOutbox(connection, fixture.alarmId(), "ALARM_ENDED", actionAt.plusSeconds(1));
                    }));
    }

    private static void assertV16BackfillRejected(
            String jdbcUrl,
            String username,
            String password,
            String schema,
            String expectedMessage,
            LegacySetup setup) throws Exception {
        Flyway.configure().dataSource(jdbcUrl, username, password)
                .schemas(schema).defaultSchema(schema).createSchemas(true)
                .locations("classpath:db/migration").target("15").load().migrate();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            update(connection, "set search_path to " + schema + ", public");
            setup.prepare(connection);
        }
        assertThatThrownBy(() -> Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load().migrate())
                .isInstanceOf(FlywayException.class)
                .satisfies(exception -> assertThat(rootCause(exception).getMessage()).contains(expectedMessage));
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static Fixture legacyAlarm(Connection connection) throws Exception {
        Fixture fixture = fixture(connection);
        insertAlarm(connection, fixture.alarmId(), fixture.vehicleId(), fixture.terminalId(), fixture.deduplicationKey());
        return fixture;
    }

    private static void insertLegacyOutbox(
            Connection connection, UUID alarmId, String eventType, Instant createdAt) throws SQLException {
        update(connection, """
                insert into vehicle_alarm_outbox (id, vehicle_alarm_id, event_type, payload, status, created_at)
                values (?, ?, ?, '{}'::jsonb, 'PENDING', ?)
                """, UUID.randomUUID(), alarmId, eventType, createdAt);
    }

    @FunctionalInterface
    private interface LegacySetup {
        void prepare(Connection connection) throws Exception;
    }

    private static void verifyV15Upgrade(
            String jdbcUrl, String username, String password, String schema) throws Exception {
        flyway(jdbcUrl, username, password, schema, "15").migrate();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            setSearchPath(connection, schema);
            Fixture first = fixture(connection);
            Fixture second = fixture(connection);
            Fixture legacyOutboxAlarm = fixture(connection);
            Fixture endedOutboxAlarm = fixture(connection);
            insertAlarm(connection, first.alarmId(), first.vehicleId(), first.terminalId(), first.deduplicationKey());
            insertAlarm(connection, second.alarmId(), second.vehicleId(), second.terminalId(), second.deduplicationKey());
            insertAlarm(connection, legacyOutboxAlarm.alarmId(), legacyOutboxAlarm.vehicleId(),
                    legacyOutboxAlarm.terminalId(), legacyOutboxAlarm.deduplicationKey());
            insertAlarm(connection, endedOutboxAlarm.alarmId(), endedOutboxAlarm.vehicleId(),
                    endedOutboxAlarm.terminalId(), endedOutboxAlarm.deduplicationKey());
            Instant createdAt = Instant.parse("2026-08-14T10:00:00Z");
            Instant acknowledgedAt = createdAt.plusSeconds(1);
            Instant processingAt = createdAt.plusSeconds(2);
            Instant resolvedAt = createdAt.plusSeconds(3);
            UUID legacyOutboxId = UUID.randomUUID();
            UUID acknowledgedOutboxId = UUID.randomUUID();
            UUID processingOutboxId = UUID.randomUUID();
            UUID resolvedOutboxId = UUID.randomUUID();
            UUID endedOutboxId = UUID.randomUUID();
            update(connection, """
                    insert into vehicle_alarm_outbox (id, vehicle_alarm_id, event_type, payload, status, created_at)
                    values (?, ?, 'ALARM_CREATED', '{}'::jsonb, 'PENDING', ?)
                    """, legacyOutboxId, legacyOutboxAlarm.alarmId(), createdAt);
            insertLegacyAction(connection, legacyOutboxAlarm.alarmId(), "ACKNOWLEDGE",
                    "NEW", "ACKNOWLEDGED", acknowledgedAt);
            update(connection, """
                    insert into vehicle_alarm_outbox (id, vehicle_alarm_id, event_type, payload, status, created_at)
                    values (?, ?, 'ALARM_STATUS_CHANGED', '{}'::jsonb, 'PENDING', ?)
                    """, acknowledgedOutboxId, legacyOutboxAlarm.alarmId(), acknowledgedAt.plusMillis(1));
            insertLegacyAction(connection, legacyOutboxAlarm.alarmId(), "TAKE_OVER",
                    "ACKNOWLEDGED", "PROCESSING", processingAt);
            update(connection, """
                    insert into vehicle_alarm_outbox (id, vehicle_alarm_id, event_type, payload, status, created_at)
                    values (?, ?, 'ALARM_STATUS_CHANGED', '{}'::jsonb, 'PENDING', ?)
                    """, processingOutboxId, legacyOutboxAlarm.alarmId(), processingAt.plusMillis(1));
            insertLegacyAction(connection, legacyOutboxAlarm.alarmId(), "RESOLVE",
                    "PROCESSING", "RESOLVED", resolvedAt);
            update(connection, """
                    insert into vehicle_alarm_outbox (id, vehicle_alarm_id, event_type, payload, status, created_at)
                    values (?, ?, 'ALARM_STATUS_CHANGED', '{}'::jsonb, 'PENDING', ?)
                    """, resolvedOutboxId, legacyOutboxAlarm.alarmId(), resolvedAt.plusMillis(1));
            update(connection, """
                    update vehicle_alarms
                    set processing_status = 'RESOLVED', handled_at = ?, version = 3
                    where id = ?
                    """, resolvedAt, legacyOutboxAlarm.alarmId());
            update(connection, """
                    insert into vehicle_alarm_outbox (id, vehicle_alarm_id, event_type, payload, status, created_at)
                    values (?, ?, 'ALARM_ENDED', '{}'::jsonb, 'PENDING', ?)
                    """, endedOutboxId, endedOutboxAlarm.alarmId(), createdAt);

            flyway(jdbcUrl, username, password, schema, null).migrate();

            UUID firstPublicId = queryUuid(connection, "select public_id from vehicle_alarms where id = ?", first.alarmId());
            UUID secondPublicId = queryUuid(connection, "select public_id from vehicle_alarms where id = ?", second.alarmId());
            assertThat(firstPublicId).isNotEqualTo(first.alarmId()).isNotEqualTo(secondPublicId);
            assertThat(secondPublicId).isNotEqualTo(second.alarmId());
            UUID legacyOutboxPublicId = queryUuid(connection,
                    "select public_id from vehicle_alarms where id = ?", legacyOutboxAlarm.alarmId());
            String legacyPayload = queryString(connection,
                    "select payload::text from vehicle_alarm_outbox where id = ?", legacyOutboxId);
            assertThat(queryString(connection,
                    "select payload->>'publicId' from vehicle_alarm_outbox where id = ?", legacyOutboxId))
                    .isEqualTo(legacyOutboxPublicId.toString());
            assertThat(queryString(connection,
                    "select payload->>'eventType' from vehicle_alarm_outbox where id = ?", legacyOutboxId))
                    .isEqualTo("ALARM_CREATED");
            assertThat(queryString(connection,
                    "select payload->>'status' from vehicle_alarm_outbox where id = ?", legacyOutboxId))
                    .isEqualTo("NEW");
            assertThat(queryString(connection,
                    "select payload->>'status' from vehicle_alarm_outbox where id = ?", acknowledgedOutboxId))
                    .isEqualTo("ACKNOWLEDGED");
            assertThat(queryString(connection,
                    "select payload->>'status' from vehicle_alarm_outbox where id = ?", processingOutboxId))
                    .isEqualTo("PROCESSING");
            assertThat(queryString(connection,
                    "select payload->>'status' from vehicle_alarm_outbox where id = ?", resolvedOutboxId))
                    .isEqualTo("RESOLVED");
            assertThat(queryString(connection,
                    "select payload->>'status' from vehicle_alarm_outbox where id = ?", endedOutboxId))
                    .isEqualTo("NEW");
            assertThat(queryString(connection,
                    "select payload->>'level' from vehicle_alarm_outbox where id = ?", legacyOutboxId))
                    .isEqualTo("1");
            assertThat(queryString(connection,
                    "select payload->>'module' from vehicle_alarm_outbox where id = ?", legacyOutboxId))
                    .isEqualTo("ADAS");
            assertThat(queryString(connection,
                    "select payload->>'occurredAt' from vehicle_alarm_outbox where id = ?", legacyOutboxId))
                    .isNotBlank();
            UUID postgresLowerId = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
            UUID postgresHigherId = UUID.fromString("80000000-0000-0000-0000-000000000000");
            Instant sameMicrosecond = Instant.parse("2026-08-14T10:01:00.000001Z");
            update(connection, """
                    insert into vehicle_alarm_outbox
                        (id, vehicle_alarm_id, event_type, payload, status, published_at, created_at)
                    values (?, ?, 'ALARM_CREATED', '{"publicId":"safe"}'::jsonb, 'PUBLISHED', ?, ?)
                    """, postgresHigherId, legacyOutboxAlarm.alarmId(), sameMicrosecond, sameMicrosecond);
            update(connection, """
                    insert into vehicle_alarm_outbox
                        (id, vehicle_alarm_id, event_type, payload, status, published_at, created_at)
                    values (?, ?, 'ALARM_CREATED', '{"publicId":"safe"}'::jsonb, 'PUBLISHED', ?, ?)
                    """, postgresLowerId, legacyOutboxAlarm.alarmId(), sameMicrosecond, sameMicrosecond);
            List<UUID> postgresCursorOrder = new ArrayList<>();
            try (var query = connection.prepareStatement("""
                    select id from vehicle_alarm_outbox
                    where id in (?, ?) order by created_at asc, id asc
                    """)) {
                query.setObject(1, postgresLowerId);
                query.setObject(2, postgresHigherId);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) postgresCursorOrder.add(rows.getObject(1, UUID.class));
                }
            }
            assertThat(postgresCursorOrder).containsExactly(postgresLowerId, postgresHigherId);
            assertThat(new ObjectMapper().findAndRegisterModules().readValue(legacyPayload,
                    Class.forName("com.idavy.drtops.domain.alarm.VehicleAlarmOutboxEvent$Snapshot"))).isNotNull();
            assertThatThrownBy(() -> update(connection,
                    "update vehicle_alarms set public_id = ? where id = ?", UUID.randomUUID(), first.alarmId()))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> update(connection,
                    "update vehicle_alarms set public_id = ? where id = ?", firstPublicId, second.alarmId()))
                    .isInstanceOf(SQLException.class);
            Fixture equalPublicId = fixture(connection);
            assertThatThrownBy(() -> insertAlarm(connection, equalPublicId.alarmId(), equalPublicId.vehicleId(),
                    equalPublicId.terminalId(), equalPublicId.deduplicationKey(), "ADAS", equalPublicId.alarmId()))
                    .isInstanceOf(SQLException.class);
            assertThat(update(connection,
                    "update vehicle_alarms set processing_status = 'ACKNOWLEDGED', handled_at = now(), version = version + 1 where id = ?",
                    first.alarmId())).isEqualTo(1);
        }
    }

    private static void insertLegacyAction(
            Connection connection,
            UUID alarmId,
            String actionType,
            String fromStatus,
            String toStatus,
            Instant occurredAt) throws SQLException {
        update(connection, """
                insert into vehicle_alarm_actions
                    (id, vehicle_alarm_id, action_type, from_status, to_status, reason, actor_id, occurred_at, created_at)
                values (?, ?, ?, ?, ?, 'migration fixture', null, ?, ?)
                """, UUID.randomUUID(), alarmId, actionType, fromStatus, toStatus, occurredAt, occurredAt);
    }

    @Test
    void concurrentlyClaimsAndPublishesEachPendingOutboxRowExactlyOnce() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("drt.integration.postgis"));
        String externalUrl = System.getProperty("drt.integration.postgis.jdbc-url");
        if (externalUrl != null && !externalUrl.isBlank()) {
            String username = System.getProperty("drt.integration.postgis.username");
            String password = System.getProperty("drt.integration.postgis.password");
            requireEphemeralExternalDatabase(externalUrl, username, password);
            verifyConcurrentClaimAndPublish(
                    externalUrl, username, password, uniqueSchema("v16_concurrent_claim"));
            return;
        }
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable());
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            verifyConcurrentClaimAndPublish(
                    database.getJdbcUrl(), database.getUsername(), database.getPassword(), null);
        }
    }

    private static void verifyConcurrentClaimAndPublish(
            String jdbcUrl, String username, String password, String schema) throws Exception {
            flyway(jdbcUrl, username, password, schema, null).migrate();
            try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
                setSearchPath(connection, schema);
                Fixture fixture = fixture(connection);
                insertAlarm(connection, fixture.alarmId(), fixture.vehicleId(), fixture.terminalId(), fixture.deduplicationKey());
                List<UUID> eventIds = new ArrayList<>();
                for (int index = 0; index < 4; index++) {
                    UUID eventId = UUID.randomUUID();
                    update(connection, """
                            insert into vehicle_alarm_outbox (id, vehicle_alarm_id, event_type, payload, status, created_at)
                            values (?, ?, 'ALARM_CREATED', '{"publicId":"safe"}'::jsonb, 'PENDING', now())
                            """, eventId, fixture.alarmId());
                    eventIds.add(eventId);
                }

                CyclicBarrier startTogether = new CyclicBarrier(2);
                CyclicBarrier claimedBeforeCommit = new CyclicBarrier(2);
                ExecutorService publishers = Executors.newFixedThreadPool(2);
                try {
                    Future<List<UUID>> firstPublisher = publishers.submit(() -> claimAndPublish(
                            jdbcUrl, username, password, schema, startTogether, claimedBeforeCommit));
                    Future<List<UUID>> secondPublisher = publishers.submit(() -> claimAndPublish(
                            jdbcUrl, username, password, schema, startTogether, claimedBeforeCommit));
                    Set<UUID> firstClaim = Set.copyOf(firstPublisher.get(30, TimeUnit.SECONDS));
                    Set<UUID> secondClaim = Set.copyOf(secondPublisher.get(30, TimeUnit.SECONDS));

                    Set<UUID> allClaimed = new HashSet<>(firstClaim);
                    allClaimed.addAll(secondClaim);
                    Set<UUID> overlap = new HashSet<>(firstClaim);
                    overlap.retainAll(secondClaim);
                    assertThat(overlap).isEmpty();
                    assertThat(allClaimed).containsExactlyInAnyOrderElementsOf(eventIds);
                    for (UUID eventId : eventIds) {
                        assertThat(queryString(connection, "select status from vehicle_alarm_outbox where id = ?", eventId))
                                .isEqualTo("PUBLISHED");
                    }
                } finally {
                    publishers.shutdownNow();
                }
            }
    }

    private static PostgreSQLContainer<?> database() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.5")
                .asCompatibleSubstituteFor("postgres"))
                .withStartupTimeout(Duration.ofMinutes(5));
    }

    private static Flyway flyway(
            String jdbcUrl, String username, String password, String schema, String target) {
        var configuration = Flyway.configure().dataSource(jdbcUrl, username, password)
                .locations("classpath:db/migration");
        if (schema != null) {
            configuration.schemas(schema).defaultSchema(schema).createSchemas(true);
        }
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private static void setSearchPath(Connection connection, String schema) throws SQLException {
        if (schema != null) update(connection, "set search_path to \"" + schema + "\", public");
    }

    private static String uniqueSchema(String prefix) {
        return prefix + "_" + Long.toUnsignedString(System.nanoTime(), 36);
    }

    private static List<UUID> claimAndPublish(
            String jdbcUrl,
            String username,
            String password,
            String schema,
            CyclicBarrier startTogether,
            CyclicBarrier claimedBeforeCommit)
            throws Exception {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            setSearchPath(connection, schema);
            connection.setAutoCommit(false);
            startTogether.await(30, TimeUnit.SECONDS);
            List<UUID> claimed = new ArrayList<>();
            try (var statement = connection.prepareStatement("""
                    select id from vehicle_alarm_outbox where status = 'PENDING'
                    order by created_at asc, id asc limit 2 for update skip locked
                    """); var rows = statement.executeQuery()) {
                while (rows.next()) claimed.add(rows.getObject(1, UUID.class));
            }
            for (UUID eventId : claimed) {
                update(connection, "update vehicle_alarm_outbox set status = 'PUBLISHED', published_at = now() where id = ?", eventId);
            }
            claimedBeforeCommit.await(30, TimeUnit.SECONDS);
            connection.commit();
            return claimed;
        }
    }

    private static void assertCanonicalModuleBoundary(Connection connection) throws Exception {
        Fixture dms = fixture(connection);
        insertAlarm(connection, dms.alarmId(), dms.vehicleId(), dms.terminalId(), dms.deduplicationKey(), "DMS");
        Fixture legacyDsm = fixture(connection);
        assertThatThrownBy(() -> insertAlarm(connection, legacyDsm.alarmId(), legacyDsm.vehicleId(),
                legacyDsm.terminalId(), legacyDsm.deduplicationKey(), "DSM"))
                .isInstanceOf(SQLException.class);
    }

    private static void assertFactsAreDeduplicatedAndImmutable(Connection connection) throws Exception {
        Fixture fixture = fixture(connection);
        insertAlarm(connection, fixture.alarmId(), fixture.vehicleId(), fixture.terminalId(), fixture.deduplicationKey());
        UUID publicId = queryUuid(connection, "select public_id from vehicle_alarms where id = ?", fixture.alarmId());
        assertThat(publicId).isNotEqualTo(fixture.alarmId());
        Fixture equalPublicId = fixture(connection);
        assertThatThrownBy(() -> insertAlarm(connection, equalPublicId.alarmId(), equalPublicId.vehicleId(),
                equalPublicId.terminalId(), equalPublicId.deduplicationKey(), "ADAS", equalPublicId.alarmId()))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insertAlarm(connection, UUID.randomUUID(), fixture.vehicleId(), fixture.terminalId(), fixture.deduplicationKey()))
                .isInstanceOf(SQLException.class).hasMessageContaining("duplicate key");
        assertThatThrownBy(() -> update(connection,
                "update vehicle_alarms set alarm_type_name_snapshot = 'tampered' where id = ?", fixture.alarmId()))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> update(connection,
                "update vehicle_alarms set terminal_alarm_id = 2 where id = ?", fixture.alarmId()))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> update(connection,
                "update vehicle_alarms set public_id = ? where id = ?", UUID.randomUUID(), fixture.alarmId()))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> update(connection,
                "update vehicle_alarms set location_quality_reasons = '[\"tampered\"]'::jsonb where id = ?", fixture.alarmId()))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> update(connection, "delete from vehicle_alarms where id = ?", fixture.alarmId()))
                .isInstanceOf(SQLException.class);
        assertThat(update(connection,
                "update vehicle_alarms set processing_status = 'ACKNOWLEDGED', handled_at = now(), version = version + 1 where id = ?",
                fixture.alarmId())).isEqualTo(1);
        assertThat(queryString(connection, "select processing_status from vehicle_alarms where id = ?", fixture.alarmId()))
                .isEqualTo("ACKNOWLEDGED");
    }

    private static void assertActionsAreAppendOnly(Connection connection) throws Exception {
        Fixture fixture = fixture(connection);
        insertAlarm(connection, fixture.alarmId(), fixture.vehicleId(), fixture.terminalId(), fixture.deduplicationKey());
        UUID actionId = UUID.randomUUID();
        update(connection, """
                insert into vehicle_alarm_actions (id, vehicle_alarm_id, action_type, from_status, to_status, reason, occurred_at)
                values (?, ?, 'ACKNOWLEDGE', 'NEW', 'ACKNOWLEDGED', 'migration test', now())
                """, actionId, fixture.alarmId());
        assertThatThrownBy(() -> update(connection,
                "update vehicle_alarm_actions set reason = 'tampered' where id = ?", actionId)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> update(connection, "delete from vehicle_alarm_actions where id = ?", actionId))
                .isInstanceOf(SQLException.class);
    }

    private static void assertOnlyOneAttachmentTransferCanBeActive(Connection connection) throws Exception {
        Fixture fixture = fixture(connection);
        insertAlarm(connection, fixture.alarmId(), fixture.vehicleId(), fixture.terminalId(), fixture.deduplicationKey());
        UUID attachmentId = UUID.randomUUID();
        update(connection, """
                insert into vehicle_alarm_attachments (
                  id, vehicle_alarm_id, attachment_type, channel, media_format, status, created_at
                ) values (?, ?, 'IMAGE', 'CAMERA_1', 'JPEG', 'REQUESTED', now())
                """, attachmentId, fixture.alarmId());
        insertTransfer(connection, UUID.randomUUID(), attachmentId, "REQUESTED");
        assertThatThrownBy(() -> insertTransfer(connection, UUID.randomUUID(), attachmentId, "UPLOADING"))
                .isInstanceOf(SQLException.class).hasMessageContaining("duplicate key");
    }

    private static void assertOutboxHasRequiredForeignKeyAndStatusBoundary(Connection connection) throws Exception {
        Fixture fixture = fixture(connection);
        insertAlarm(connection, fixture.alarmId(), fixture.vehicleId(), fixture.terminalId(), fixture.deduplicationKey());
        update(connection, """
                insert into vehicle_alarm_outbox (id, vehicle_alarm_id, event_type, payload, status, created_at)
                values (?, ?, 'ALARM_CREATED', '{}'::jsonb, 'PENDING', now())
                """, UUID.randomUUID(), fixture.alarmId());
        assertThatThrownBy(() -> update(connection, """
                insert into vehicle_alarm_outbox (id, vehicle_alarm_id, event_type, payload, status, created_at)
                values (?, ?, 'ALARM_CREATED', '{}'::jsonb, 'PENDING', now())
                """, UUID.randomUUID(), UUID.randomUUID())).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> update(connection, """
                insert into vehicle_alarm_outbox (id, vehicle_alarm_id, event_type, payload, status, created_at)
                values (?, ?, 'ALARM_CREATED', '{}'::jsonb, 'NOT_A_STATUS', now())
                """, UUID.randomUUID(), fixture.alarmId())).isInstanceOf(SQLException.class);
    }

    private static Fixture fixture(Connection connection) throws SQLException {
        UUID terminalId = UUID.randomUUID();
        UUID vehicleId = queryUuid(connection, "select id from vehicles order by id limit 1");
        String compactTerminalId = terminalId.toString().replace("-", "");
        update(connection, """
                insert into jt_terminals (id, terminal_phone, terminal_code, manufacturer_id, model, protocol_version,
                  source_coordinate_system, status, auth_token_hash, auth_token_version)
                values (?, ?, ?, 'VIRTUAL-MANUFACTURER', 'VIRTUAL-MODEL', 'JT808-2019',
                  'GCJ02', 'ACTIVE', repeat('a', 64), 1)
                """, terminalId, "MIG-" + compactTerminalId.substring(0, 24), "MIGRATION-CODE-" + terminalId);
        return new Fixture(terminalId, vehicleId, UUID.randomUUID(), compactTerminalId
                + UUID.randomUUID().toString().replace("-", ""));
    }

    private static void insertAlarm(Connection connection, UUID alarmId, UUID vehicleId, UUID terminalId, String deduplicationKey)
            throws SQLException {
        insertAlarm(connection, alarmId, vehicleId, terminalId, deduplicationKey, "ADAS");
    }

    private static void insertAlarm(
            Connection connection, UUID alarmId, UUID vehicleId, UUID terminalId, String deduplicationKey, String module)
            throws SQLException {
        insertAlarm(connection, alarmId, vehicleId, terminalId, deduplicationKey, module, UUID.randomUUID());
    }

    private static void insertAlarm(
            Connection connection, UUID alarmId, UUID vehicleId, UUID terminalId, String deduplicationKey,
            String module, UUID publicId) throws SQLException {
        if (!columnExists(connection, "vehicle_alarms", "public_id")) {
            update(connection, """
                insert into vehicle_alarms (
                  id, vehicle_id, terminal_id, standard, module, terminal_alarm_id,
                  alarm_type_code, alarm_type_name_snapshot,
                  alarm_level, terminal_alarm_identifier, terminal_alarm_state, occurred_at, gateway_received_at,
                  longitude, latitude, speed_kph, location_quality_status, location_quality_reasons,
                  processing_status, payload_digest,
                  deduplication_key, version, created_at)
                values (?, ?, ?, 'T/JSATL12-2017', ?, 4097, 1, 'FORWARD_COLLISION',
                  1, '00000001', 'START', now(), now(), 118.0000000, 32.0000000, 60.00,
                  'GOOD', '[]'::jsonb, 'NEW', repeat('c', 64), ?, 0, now())
                """, alarmId, vehicleId, terminalId, module, deduplicationKey);
            return;
        }
        update(connection, """
                insert into vehicle_alarms (
                  id, public_id, vehicle_id, terminal_id, standard, module, terminal_alarm_id,
                  alarm_type_code, alarm_type_name_snapshot,
                  alarm_level, terminal_alarm_identifier, terminal_alarm_state, occurred_at, gateway_received_at,
                  longitude, latitude, speed_kph, location_quality_status, location_quality_reasons,
                  processing_status, payload_digest,
                  deduplication_key, version, created_at)
                values (?, ?, ?, ?, 'T/JSATL12-2017', ?, 4097, 1, 'FORWARD_COLLISION',
                  1, '00000001', 'START', now(), now(), 118.0000000, 32.0000000, 60.00,
                  'GOOD', '[]'::jsonb, 'NEW', repeat('c', 64), ?, 0, now())
                """, alarmId, publicId, vehicleId, terminalId, module, deduplicationKey);
    }

    private static void insertTransfer(Connection connection, UUID id, UUID attachmentId, String status) throws SQLException {
        update(connection, """
                insert into vehicle_alarm_attachment_transfers (
                  id, vehicle_alarm_attachment_id, control_message_type, status, created_at)
                values (?, ?, '0x9208', ?, now())
                """, id, attachmentId, status);
    }

    private static int update(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                Object value = values[index];
                statement.setObject(index + 1, value instanceof Instant instant
                        ? OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)
                        : value);
            }
            return statement.executeUpdate();
        }
    }

    private static UUID queryUuid(Connection connection, String sql) throws SQLException {
        try (var query = connection.prepareStatement(sql); var rows = query.executeQuery()) { rows.next(); return rows.getObject(1, UUID.class); }
    }

    private static UUID queryUuid(Connection connection, String sql, Object value) throws SQLException {
        try (var query = connection.prepareStatement(sql)) {
            query.setObject(1, value);
            try (var rows = query.executeQuery()) { rows.next(); return rows.getObject(1, UUID.class); }
        }
    }

    private static String queryString(Connection connection, String sql, Object value) throws SQLException {
        try (var query = connection.prepareStatement(sql)) { query.setObject(1, value); try (var rows = query.executeQuery()) { rows.next(); return rows.getString(1); } }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (var query = connection.prepareStatement("select exists (select 1 from information_schema.tables where table_schema=current_schema() and table_name=?)")) {
            query.setString(1, table); try (var rows = query.executeQuery()) { rows.next(); return rows.getBoolean(1); }
        }
    }
    private static boolean indexExists(Connection connection, String index) throws SQLException {
        try (var query = connection.prepareStatement("select exists (select 1 from pg_indexes where schemaname=current_schema() and indexname=?)")) {
            query.setString(1, index); try (var rows = query.executeQuery()) { rows.next(); return rows.getBoolean(1); }
        }
    }
    private static String indexDefinition(Connection connection, String index) throws SQLException {
        try (var query = connection.prepareStatement(
                "select indexdef from pg_indexes where schemaname=current_schema() and indexname=?")) {
            query.setString(1, index);
            try (var rows = query.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }
    private static boolean triggerExists(Connection connection, String trigger) throws SQLException {
        try (var query = connection.prepareStatement("select exists (select 1 from information_schema.triggers where trigger_schema=current_schema() and trigger_name=?)")) {
            query.setString(1, trigger); try (var rows = query.executeQuery()) { rows.next(); return rows.getBoolean(1); }
        }
    }
    private static boolean columnExists(Connection connection, String table, String column) throws SQLException {
        try (var query = connection.prepareStatement("select exists (select 1 from information_schema.columns where table_schema=current_schema() and table_name=? and column_name=?)")) {
            query.setString(1, table); query.setString(2, column); try (var rows = query.executeQuery()) { rows.next(); return rows.getBoolean(1); }
        }
    }
    private static boolean foreignKeyExists(Connection connection, String table, String column, String referencedTable) throws SQLException {
        try (var query = connection.prepareStatement("""
                select exists (select 1 from information_schema.key_column_usage keys
                  join information_schema.table_constraints constraints on constraints.constraint_name = keys.constraint_name
                    and constraints.table_schema = keys.table_schema
                  join information_schema.constraint_column_usage references_ on references_.constraint_name = constraints.constraint_name
                    and references_.table_schema = constraints.table_schema
                  where constraints.constraint_type='FOREIGN KEY' and keys.table_schema=current_schema()
                    and keys.table_name=? and keys.column_name=? and references_.table_name=?)
                """)) {
            query.setString(1, table); query.setString(2, column); query.setString(3, referencedTable);
            try (var rows = query.executeQuery()) { rows.next(); return rows.getBoolean(1); }
        }
    }

    private record Fixture(UUID terminalId, UUID vehicleId, UUID alarmId, String deduplicationKey) { }
}
