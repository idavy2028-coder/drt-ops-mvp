package com.idavy.drtops;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
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
    }

    @Test
    void migrationsCreateCoreTablesAndSeedAreaWhenDockerIsAvailable() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(POSTGIS_INTEGRATION_PROPERTY),
                "Set -D" + POSTGIS_INTEGRATION_PROPERTY + "=true to run the PostGIS migration test");
        Assumptions.assumeTrue(dockerIsAvailable(), "Docker is not available; skipping PostGIS migration test");

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgis/postgis:16-3.5")
                .withDatabaseName("drt_ops")
                .withUsername("drt_ops")
                .withPassword("drt_ops")) {
            postgres.start();

            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
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
                    "refresh_tokens");

            Integer areaCount = jdbcTemplate.queryForObject("select count(*) from service_areas", Integer.class);
            Integer stopCount = jdbcTemplate.queryForObject("select count(*) from virtual_stops", Integer.class);
            Integer vehicleCount = jdbcTemplate.queryForObject("select count(*) from vehicles", Integer.class);
            Integer driverCount = jdbcTemplate.queryForObject("select count(*) from drivers", Integer.class);

            assertThat(areaCount).isGreaterThanOrEqualTo(1);
            assertThat(stopCount).isEqualTo(6);
            assertThat(vehicleCount).isEqualTo(2);
            assertThat(driverCount).isEqualTo(2);

            try (var connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                    var statement = connection.createStatement();
                    var resultSet = statement.executeQuery("select postgis_version()")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isNotBlank();
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
