package com.idavy.drtops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

class DatabaseMigrationTest {

    private static final String POSTGIS_INTEGRATION_PROPERTY = "drt.integration.postgis";
    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");

    @Test
    void migrationScriptsDeclareCoreTablesAndSeedData() throws IOException {
        String schema = readMigration("V1__create_core_schema.sql");
        String seedData = readMigration("V2__seed_demo_operations.sql");
        String authSchema = readMigration("V3__create_auth_schema.sql");
        String refreshTokenVersion = readMigration("V4__add_refresh_token_version.sql");

        assertThat(schema).contains(
                "CREATE EXTENSION IF NOT EXISTS postgis",
                "CREATE TABLE service_areas",
                "CREATE TABLE virtual_stops",
                "CREATE TABLE dispatch_rule_sets",
                "CREATE TABLE vehicles",
                "CREATE TABLE drivers",
                "CREATE TABLE ride_orders",
                "CREATE TABLE vehicle_tasks",
                "CREATE TABLE task_stops",
                "CREATE TABLE dispatch_decisions",
                "CREATE TABLE audit_logs",
                "boundary geography(POLYGON, 4326)",
                "location geography(POINT, 4326)",
                "current_location geography(POINT, 4326)");

        assertThat(seedData).contains(
                "11111111-1111-1111-1111-111111111111",
                "22222222-2222-2222-2222-222222222222",
                "33333333-3333-3333-3333-333333333331",
                "33333333-3333-3333-3333-333333333332",
                "44444444-4444-4444-4444-444444444441",
                "44444444-4444-4444-4444-444444444442");

        assertThat(authSchema).contains(
                "CREATE TABLE user_accounts",
                "CREATE TABLE roles",
                "CREATE TABLE user_roles",
                "CREATE TABLE refresh_tokens",
                "'SYSTEM_ADMIN'",
                "'DISPATCHER'",
                "'OPERATOR'",
                "'AUDITOR'");

        assertThat(refreshTokenVersion).contains(
                "ALTER TABLE refresh_tokens",
                "token_version BIGINT NOT NULL",
                "FROM user_accounts");
    }

    @Test
    void migrationsCreateCoreTablesAndSeedAreaWhenDockerIsAvailable() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(POSTGIS_INTEGRATION_PROPERTY),
                "Set -D" + POSTGIS_INTEGRATION_PROPERTY + "=true to run the PostGIS migration test");
        Assumptions.assumeTrue(dockerIsAvailable(), "需要 Docker/Testcontainers 提供隔离 PostGIS 数据库");

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgis/postgis:16-3.5")
                .withDatabaseName("drt_ops")
                .withUsername("drt_ops")
                .withPassword("drt_ops")) {
            postgres.start();
            verifyMigrations(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
    }

    private static void verifyMigrations(String jdbcUrl, String username, String password) throws Exception {
        Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        DriverManagerDataSource dataSource = new DriverManagerDataSource(jdbcUrl, username, password);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        List<String> tableNames = jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'",
                String.class);

        assertThat(tableNames).contains(
                "service_areas",
                "virtual_stops",
                "dispatch_rule_sets",
                "vehicles",
                "drivers",
                "ride_orders",
                "vehicle_tasks",
                "task_stops",
                "dispatch_decisions",
                "audit_logs",
                "user_accounts",
                "roles",
                "user_roles",
                "refresh_tokens",
                "vehicle_location_events");

        Integer areaCount = jdbcTemplate.queryForObject("select count(*) from service_areas", Integer.class);
        Integer stopCount = jdbcTemplate.queryForObject("select count(*) from virtual_stops", Integer.class);
        Integer vehicleCount = jdbcTemplate.queryForObject("select count(*) from vehicles", Integer.class);
        Integer driverCount = jdbcTemplate.queryForObject("select count(*) from drivers", Integer.class);

        assertThat(areaCount).isGreaterThanOrEqualTo(1);
        assertThat(stopCount).isEqualTo(6);
        assertThat(vehicleCount).isEqualTo(2);
        assertThat(driverCount).isEqualTo(2);

        try (var connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            try (var statement = connection.createStatement();
                    var resultSet = statement.executeQuery("select postgis_version()")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isNotBlank();
            }

            assertColumns(connection, "vehicle_location_events",
                    "id", "vehicle_id", "vehicle_task_id", "task_stop_id", "virtual_stop_id",
                    "event_type", "source", "location", "longitude", "latitude",
                    "coordinate_system", "standardized_address", "driver_reported_at",
                    "recorded_at", "recorded_by", "note", "correction_reason",
                    "corrects_event_id", "idempotency_key", "request_fingerprint", "snapshot_applied",
                    "outside_service_area");
            assertColumns(connection, "vehicles",
                    "current_location_address", "current_location_source",
                    "current_location_coordinate_system", "current_location_reported_at",
                    "current_location_recorded_at", "current_location_event_id",
                    "current_location_task_id");
        }

        assertMutationRejected(
                jdbcUrl,
                username,
                password,
                "update vehicle_location_events set note = 'changed' where id = ?");
        assertMutationRejected(jdbcUrl, username, password, "delete from vehicle_location_events where id = ?");
    }

    private static void assertColumns(java.sql.Connection connection, String tableName, String... expectedColumns)
            throws Exception {
        try (var statement = connection.prepareStatement("""
                select column_name
                from information_schema.columns
                where table_schema = 'public' and table_name = ?
                """)) {
            statement.setString(1, tableName);
            try (var resultSet = statement.executeQuery()) {
                List<String> columns = new java.util.ArrayList<>();
                while (resultSet.next()) {
                    columns.add(resultSet.getString(1));
                }
                assertThat(columns).contains(expectedColumns);
            }
        }
    }

    private static void assertMutationRejected(String jdbcUrl, String username, String password, String mutationSql)
            throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            connection.setAutoCommit(false);
            try {
                UUID eventId = UUID.randomUUID();
                try (var insert = connection.prepareStatement("""
                        insert into vehicle_location_events (
                          id, vehicle_id, event_type, source, location, longitude, latitude,
                          coordinate_system, standardized_address, driver_reported_at, recorded_by,
                          idempotency_key, request_fingerprint, snapshot_applied, outside_service_area
                        ) values (?, (select id from vehicles limit 1), 'TASK_STARTED', 'MANUAL_DISPATCHER',
                          ST_SetSRID(ST_MakePoint(121.4737, 31.2304), 4326)::geography, 121.4737000, 31.2304000,
                          'GCJ02', 'test address', now(), (select id from user_accounts limit 1),
                          ?, repeat('a', 64), true, false)
                        """)) {
                    insert.setObject(1, eventId);
                    insert.setObject(2, UUID.randomUUID());
                    insert.executeUpdate();
                }

                try (var mutation = connection.prepareStatement(mutationSql)) {
                    mutation.setObject(1, eventId);
                    assertThatThrownBy(mutation::executeUpdate)
                            .hasStackTraceContaining("vehicle location events are immutable");
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private static String readMigration(String fileName) throws IOException {
        return Files.readString(MIGRATION_DIR.resolve(fileName), StandardCharsets.UTF_8);
    }

    private static boolean dockerIsAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
