package com.idavy.drtops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class P6TerminalLocationMigrationTest {

    private static final String POSTGIS_INTEGRATION_PROPERTY = "drt.integration.postgis";

    @Test
    void upgradesLegacyManualLocationsWithoutChangingHistoryOrVehicleSnapshot() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(POSTGIS_INTEGRATION_PROPERTY),
                "Set -D" + POSTGIS_INTEGRATION_PROPERTY + "=true to run the PostGIS migration test");
        Assumptions.assumeTrue(dockerIsAvailable(), "需要 Docker/Testcontainers 提供隔离 PostGIS 数据库");

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.5")
                .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("drt_ops")
                .withUsername("drt_ops")
                .withPassword("drt_ops")) {
            postgres.start();
            String jdbcUrl = postgres.getJdbcUrl();
            migrateToV12(jdbcUrl, postgres.getUsername(), postgres.getPassword());

            LegacyLocationHistory legacyHistory;
            try (Connection connection = DriverManager.getConnection(jdbcUrl, postgres.getUsername(), postgres.getPassword())) {
                legacyHistory = createLegacyManualLocationHistory(connection);
            }

            migrateToLatest(jdbcUrl, postgres.getUsername(), postgres.getPassword());

            try (Connection connection = DriverManager.getConnection(jdbcUrl, postgres.getUsername(), postgres.getPassword())) {
                assertTerminalRegistryAndBindings(connection);
                assertLocationQualitySchema(connection);
                assertLegacyHistoryAndSnapshotArePreserved(connection, legacyHistory);
                assertGpsLocationAllowsMissingManualOnlyFields(connection);
                assertVehicleLocationEventsRemainImmutable(connection);
            }
        }
    }

    private static void migrateToV12(String jdbcUrl, String username, String password) {
        Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .locations("classpath:db/migration")
                .target("12")
                .load()
                .migrate();
    }

    private static void migrateToLatest(String jdbcUrl, String username, String password) {
        Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private static LegacyLocationHistory createLegacyManualLocationHistory(Connection connection) throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID firstEventId = UUID.randomUUID();
        UUID secondEventId = UUID.randomUUID();
        UUID vehicleId = queryUuid(connection, "select id from vehicles order by id limit 1");
        insertActor(connection, actorId);
        insertLegacyManualLocation(connection, firstEventId, vehicleId, actorId,
                OffsetDateTime.parse("2026-08-01T09:00:00+08:00"), new BigDecimal("121.4737000"));
        insertLegacyManualLocation(connection, secondEventId, vehicleId, actorId,
                OffsetDateTime.parse("2026-08-01T10:00:00+08:00"), new BigDecimal("121.4837000"));
        try (var update = connection.prepareStatement("""
                update vehicles
                set current_location = ST_SetSRID(ST_MakePoint(121.4837000, 31.2304000), 4326)::geography,
                    current_location_address = 'legacy manual address 2',
                    current_location_source = 'MANUAL_DISPATCHER',
                    current_location_coordinate_system = 'GCJ02',
                    current_location_reported_at = ?,
                    current_location_recorded_at = ?,
                    current_location_event_id = ?
                where id = ?
                """)) {
            update.setObject(1, OffsetDateTime.parse("2026-08-01T10:00:00+08:00"));
            update.setObject(2, OffsetDateTime.parse("2026-08-01T10:00:01+08:00"));
            update.setObject(3, secondEventId);
            update.setObject(4, vehicleId);
            update.executeUpdate();
        }
        return new LegacyLocationHistory(vehicleId, List.of(firstEventId, secondEventId), secondEventId);
    }

    private static void insertActor(Connection connection, UUID actorId) throws SQLException {
        try (var insert = connection.prepareStatement("""
                insert into user_accounts (
                  id, username, display_name, password_hash, enabled, must_change_password
                ) values (?, ?, 'P6 migration actor', 'not-used', true, false)
                """)) {
            insert.setObject(1, actorId);
            insert.setString(2, "p6-migration-" + actorId);
            insert.executeUpdate();
        }
    }

    private static void insertLegacyManualLocation(
            Connection connection, UUID eventId, UUID vehicleId, UUID actorId, OffsetDateTime reportedAt, BigDecimal longitude)
            throws SQLException {
        try (var insert = connection.prepareStatement("""
                insert into vehicle_location_events (
                  id, vehicle_id, event_type, source, location, longitude, latitude,
                  coordinate_system, standardized_address, driver_reported_at, recorded_by,
                  idempotency_key, request_fingerprint, snapshot_applied, outside_service_area
                ) values (?, ?, 'TASK_STARTED', 'MANUAL_DISPATCHER',
                  ST_SetSRID(ST_MakePoint(?, 31.2304000), 4326)::geography, ?, 31.2304000,
                  'GCJ02', ?, ?, ?, ?, repeat('a', 64), true, false)
                """)) {
            insert.setObject(1, eventId);
            insert.setObject(2, vehicleId);
            insert.setBigDecimal(3, longitude);
            insert.setBigDecimal(4, longitude);
            insert.setString(5, "legacy manual address " + (longitude.compareTo(new BigDecimal("121.48")) > 0 ? "2" : "1"));
            insert.setObject(6, reportedAt);
            insert.setObject(7, actorId);
            insert.setObject(8, UUID.randomUUID());
            insert.executeUpdate();
        }
    }

    private static void assertTerminalRegistryAndBindings(Connection connection) throws Exception {
        UUID firstTerminalId = UUID.randomUUID();
        UUID secondTerminalId = UUID.randomUUID();
        UUID firstVehicleId = queryUuid(connection, "select id from vehicles order by id limit 1");
        UUID secondVehicleId = queryUuid(connection, "select id from vehicles order by id offset 1 limit 1");
        insertTerminal(connection, firstTerminalId, "VIRTUAL-TERM-001", "VIRTUAL-CODE-001");

        assertThatThrownBy(() -> insertTerminal(connection, secondTerminalId, "VIRTUAL-TERM-001", "VIRTUAL-CODE-002"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("duplicate key");
        assertThatThrownBy(() -> insertTerminal(connection, secondTerminalId, "VIRTUAL-TERM-002", "VIRTUAL-CODE-001"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("duplicate key");

        insertBinding(connection, firstTerminalId, firstVehicleId);
        assertThatThrownBy(() -> insertBinding(connection, firstTerminalId, secondVehicleId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("duplicate key");
    }

    private static void insertTerminal(Connection connection, UUID terminalId, String terminalPhone, String terminalCode)
            throws SQLException {
        try (var insert = connection.prepareStatement("""
                insert into jt_terminals (
                  id, terminal_phone, terminal_code, manufacturer_id, model, protocol_version,
                  source_coordinate_system, status, auth_token_hash, auth_token_version
                ) values (?, ?, ?, 'VIRTUAL-MANUFACTURER', 'VIRTUAL-MODEL', 'JT808-2019',
                  'GCJ02', 'PENDING', repeat('b', 64), 1)
                """)) {
            insert.setObject(1, terminalId);
            insert.setString(2, terminalPhone);
            insert.setString(3, terminalCode);
            insert.executeUpdate();
        }
    }

    private static void insertBinding(Connection connection, UUID terminalId, UUID vehicleId) throws SQLException {
        try (var insert = connection.prepareStatement("""
                insert into jt_terminal_vehicle_bindings (
                  id, terminal_id, vehicle_id, valid_from, status, binding_reason
                ) values (?, ?, ?, now(), 'ACTIVE', 'P6 migration test')
                """)) {
            insert.setObject(1, UUID.randomUUID());
            insert.setObject(2, terminalId);
            insert.setObject(3, vehicleId);
            insert.executeUpdate();
        }
    }

    private static void assertLocationQualitySchema(Connection connection) throws SQLException {
        assertColumns(connection, "vehicle_location_events",
                "terminal_id", "protocol_version", "message_serial_no", "raw_longitude", "raw_latitude",
                "raw_coordinate_system", "gateway_received_at", "payload_digest", "speed_kph",
                "direction_degrees", "altitude_meters", "satellite_count", "alarm_bits", "status_bits",
                "coordinate_transform_version", "quality_status", "quality_reasons");
        assertColumns(connection, "vehicles",
                "current_location_terminal_id", "current_location_quality_status", "current_location_quality_reasons",
                "current_location_gateway_received_at", "current_location_speed_kph",
                "current_location_direction_degrees", "current_location_stale");
        assertThat(queryString(connection, """
                select udt_name from information_schema.columns
                where table_schema = 'public' and table_name = 'vehicle_location_events' and column_name = 'quality_reasons'
                """)).isEqualTo("jsonb");
        assertThat(queryStrings(connection, """
                select distinct am.amname
                from pg_indexes indexes
                join pg_class index_class on index_class.relname = indexes.indexname
                join pg_am am on am.oid = index_class.relam
                where indexes.schemaname = 'public' and indexes.tablename = 'vehicle_location_events'
                """)).contains("brin", "gist", "btree");
    }

    private static void assertLegacyHistoryAndSnapshotArePreserved(
            Connection connection, LegacyLocationHistory legacyHistory) throws SQLException {
        assertThat(queryUuids(connection, """
                select id from vehicle_location_events
                where vehicle_id = ? and source = 'MANUAL_DISPATCHER'
                order by driver_reported_at asc
                """, legacyHistory.vehicleId())).containsExactlyElementsOf(legacyHistory.eventIds());
        assertThat(queryString(connection, "select quality_status from vehicle_location_events where id = ?",
                legacyHistory.eventIds().getFirst())).isEqualTo("GOOD");
        assertThat(queryString(connection, "select raw_coordinate_system from vehicle_location_events where id = ?",
                legacyHistory.eventIds().getFirst())).isEqualTo("GCJ02");
        assertThat(queryString(connection, "select coordinate_transform_version from vehicle_location_events where id = ?",
                legacyHistory.eventIds().getFirst())).isEqualTo("LEGACY_NONE");
        assertThat(queryUuid(connection, "select current_location_event_id from vehicles where id = ?", legacyHistory.vehicleId()))
                .isEqualTo(legacyHistory.snapshotEventId());
        assertThat(queryUuid(connection, "select current_location_terminal_id from vehicles where id = ?", legacyHistory.vehicleId()))
                .isNull();
    }

    private static void assertGpsLocationAllowsMissingManualOnlyFields(Connection connection) throws Exception {
        UUID terminalId = UUID.randomUUID();
        UUID vehicleId = queryUuid(connection, "select id from vehicles order by id limit 1");
        insertTerminal(connection, terminalId, "VIRTUAL-TERM-GPS", "VIRTUAL-CODE-GPS");
        try (var insert = connection.prepareStatement("""
                insert into vehicle_location_events (
                  id, vehicle_id, event_type, source, location, longitude, latitude, coordinate_system,
                  driver_reported_at, idempotency_key, request_fingerprint, snapshot_applied, outside_service_area,
                  terminal_id, protocol_version, message_serial_no, raw_longitude, raw_latitude,
                  raw_coordinate_system, gateway_received_at, payload_digest, speed_kph, direction_degrees,
                  altitude_meters, satellite_count, alarm_bits, status_bits, coordinate_transform_version,
                  quality_status, quality_reasons
                ) values (?, ?, 'GPS_REPORT', 'GPS_DEVICE',
                  ST_SetSRID(ST_MakePoint(121.4937000, 31.2304000), 4326)::geography, 121.4937000, 31.2304000, 'GCJ02',
                  now(), ?, repeat('c', 64), true, false,
                  ?, 'JT808-2019', 1, 121.4937000, 31.2304000,
                  'GCJ02', now(), repeat('d', 64), 36.5, 90, 12,
                  8, 0, 0, 'GCJ02-2026-08', 'GOOD', '[]'::jsonb)
                """)) {
            insert.setObject(1, UUID.randomUUID());
            insert.setObject(2, vehicleId);
            insert.setObject(3, UUID.randomUUID());
            insert.setObject(4, terminalId);
            insert.executeUpdate();
        }
    }

    private static void assertVehicleLocationEventsRemainImmutable(Connection connection) throws Exception {
        assertThat(queryString(connection, """
                select tgenabled
                from pg_trigger
                where tgrelid = 'vehicle_location_events'::regclass
                  and tgname = 'prevent_vehicle_location_event_mutation'
                """)).isEqualTo("O");
        UUID eventId = queryUuid(connection, "select id from vehicle_location_events order by recorded_at limit 1");
        try (var update = connection.prepareStatement("update vehicle_location_events set note = 'changed' where id = ?")) {
            update.setObject(1, eventId);
            assertThatThrownBy(update::executeUpdate)
                    .hasStackTraceContaining("vehicle location events are immutable");
        }
    }

    private static void assertColumns(Connection connection, String tableName, String... expectedColumns) throws SQLException {
        assertThat(queryStrings(connection, """
                select column_name from information_schema.columns
                where table_schema = 'public' and table_name = ?
                """, tableName)).contains(expectedColumns);
    }

    private static UUID queryUuid(Connection connection, String sql, Object... parameters) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            setParameters(statement, parameters);
            try (var resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getObject(1, UUID.class);
            }
        }
    }

    private static List<UUID> queryUuids(Connection connection, String sql, Object... parameters) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            setParameters(statement, parameters);
            try (var resultSet = statement.executeQuery()) {
                java.util.ArrayList<UUID> values = new java.util.ArrayList<>();
                while (resultSet.next()) {
                    values.add(resultSet.getObject(1, UUID.class));
                }
                return values;
            }
        }
    }

    private static String queryString(Connection connection, String sql, Object... parameters) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            setParameters(statement, parameters);
            try (var resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getString(1);
            }
        }
    }

    private static List<String> queryStrings(Connection connection, String sql, Object... parameters) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            setParameters(statement, parameters);
            try (var resultSet = statement.executeQuery()) {
                java.util.ArrayList<String> values = new java.util.ArrayList<>();
                while (resultSet.next()) {
                    values.add(resultSet.getString(1));
                }
                return values;
            }
        }
    }

    private static void setParameters(java.sql.PreparedStatement statement, Object... parameters) throws SQLException {
        for (int index = 0; index < parameters.length; index++) {
            statement.setObject(index + 1, parameters[index]);
        }
    }

    private static boolean dockerIsAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private record LegacyLocationHistory(UUID vehicleId, List<UUID> eventIds, UUID snapshotEventId) {
    }
}
