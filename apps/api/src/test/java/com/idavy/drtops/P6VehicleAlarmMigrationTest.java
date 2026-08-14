package com.idavy.drtops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class P6VehicleAlarmMigrationTest {
    @Test
    void createsTheCompleteAlarmDomainWithImmutableFactsAndRequiredConstraints() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("drt.integration.postgis"));
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable());
        try (PostgreSQLContainer<?> database = new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.5")
                .asCompatibleSubstituteFor("postgres"))
                .withStartupTimeout(Duration.ofMinutes(5))) {
            database.start();
            Flyway.configure().dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                    .locations("classpath:db/migration").load().migrate();
            try (Connection connection = DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword())) {
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
    }

    @Test
    void upgradesExistingV15AlarmRowsWithIndependentImmutablePublicIds() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("drt.integration.postgis"));
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable());
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            Flyway.configure().dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                    .locations("classpath:db/migration").target("15").load().migrate();
            try (Connection connection = DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword())) {
                Fixture first = fixture(connection);
                Fixture second = fixture(connection);
                insertAlarm(connection, first.alarmId(), first.vehicleId(), first.terminalId(), first.deduplicationKey());
                insertAlarm(connection, second.alarmId(), second.vehicleId(), second.terminalId(), second.deduplicationKey());

                Flyway.configure().dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                        .locations("classpath:db/migration").load().migrate();

                UUID firstPublicId = queryUuid(connection, "select public_id from vehicle_alarms where id = ?", first.alarmId());
                UUID secondPublicId = queryUuid(connection, "select public_id from vehicle_alarms where id = ?", second.alarmId());
                assertThat(firstPublicId).isNotEqualTo(first.alarmId()).isNotEqualTo(secondPublicId);
                assertThat(secondPublicId).isNotEqualTo(second.alarmId());
                assertThatThrownBy(() -> update(connection,
                        "update vehicle_alarms set public_id = ? where id = ?", UUID.randomUUID(), first.alarmId()))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> update(connection,
                        "update vehicle_alarms set public_id = ? where id = ?", firstPublicId, second.alarmId()))
                        .isInstanceOf(SQLException.class);
                assertThat(update(connection,
                        "update vehicle_alarms set processing_status = 'ACKNOWLEDGED', handled_at = now(), version = version + 1 where id = ?",
                        first.alarmId())).isEqualTo(1);
            }
        }
    }

    private static PostgreSQLContainer<?> database() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.5")
                .asCompatibleSubstituteFor("postgres"))
                .withStartupTimeout(Duration.ofMinutes(5));
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
                """, alarmId, UUID.randomUUID(), vehicleId, terminalId, module, deduplicationKey);
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
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
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
        try (var query = connection.prepareStatement("select exists (select 1 from information_schema.tables where table_schema='public' and table_name=?)")) {
            query.setString(1, table); try (var rows = query.executeQuery()) { rows.next(); return rows.getBoolean(1); }
        }
    }
    private static boolean indexExists(Connection connection, String index) throws SQLException {
        try (var query = connection.prepareStatement("select exists (select 1 from pg_indexes where schemaname='public' and indexname=?)")) {
            query.setString(1, index); try (var rows = query.executeQuery()) { rows.next(); return rows.getBoolean(1); }
        }
    }
    private static boolean triggerExists(Connection connection, String trigger) throws SQLException {
        try (var query = connection.prepareStatement("select exists (select 1 from information_schema.triggers where trigger_schema='public' and trigger_name=?)")) {
            query.setString(1, trigger); try (var rows = query.executeQuery()) { rows.next(); return rows.getBoolean(1); }
        }
    }
    private static boolean columnExists(Connection connection, String table, String column) throws SQLException {
        try (var query = connection.prepareStatement("select exists (select 1 from information_schema.columns where table_schema='public' and table_name=? and column_name=?)")) {
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
                  where constraints.constraint_type='FOREIGN KEY' and keys.table_schema='public'
                    and keys.table_name=? and keys.column_name=? and references_.table_name=?)
                """)) {
            query.setString(1, table); query.setString(2, column); query.setString(3, referencedTable);
            try (var rows = query.executeQuery()) { rows.next(); return rows.getBoolean(1); }
        }
    }

    private record Fixture(UUID terminalId, UUID vehicleId, UUID alarmId, String deduplicationKey) { }
}
