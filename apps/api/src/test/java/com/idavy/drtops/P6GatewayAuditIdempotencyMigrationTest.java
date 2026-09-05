package com.idavy.drtops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class P6GatewayAuditIdempotencyMigrationTest {
    private static final String INTEGRATION_PROPERTY = "drt.integration.postgis";

    @Test
    void backfillsLegacyIdsThenEnforcesNotNullAndUniquenessInAnIsolatedH2CompatibilityCheck() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:v17_audit_idempotency;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID legacyId = UUID.randomUUID();
        jdbc.execute("create table jt_gateway_audit_events (id uuid primary key)");
        jdbc.execute("create table jt_gateway_ingress_receipts (idempotency_key uuid primary key)");
        jdbc.update("insert into jt_gateway_audit_events (id) values (?)", legacyId);

        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V17__add_jt_gateway_audit_idempotency.sql")).execute(dataSource);

        assertThat(jdbc.queryForObject(
                "select idempotency_key from jt_gateway_audit_events where id = ?", UUID.class, legacyId))
                .isEqualTo(legacyId);
        assertThat(jdbc.queryForObject("""
                select is_nullable from information_schema.columns
                where table_name = 'JT_GATEWAY_AUDIT_EVENTS' and column_name = 'IDEMPOTENCY_KEY'
                """, String.class)).isEqualTo("NO");
        assertThat(jdbc.queryForList("""
                select column_name from information_schema.columns
                where table_name = 'JT_GATEWAY_INGRESS_RECEIPTS'
                order by ordinal_position
                """, String.class)).contains("TERMINAL_ID", "VEHICLE_ID", "INGRESS_KIND");
        assertThatThrownBy(() -> jdbc.update(
                "insert into jt_gateway_audit_events (id, idempotency_key) values (?, ?)",
                UUID.randomUUID(), legacyId))
                .hasMessageContaining("Unique index or primary key violation");
    }

    @Test
    void migratesFreshV0ToV17OnExplicitEphemeralPostgres() throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = "v17_fresh_" + UUID.randomUUID().toString().replace("-", "");

        flyway(postgres, schema, null).migrate();

        try (Connection connection = DriverManager.getConnection(
                postgres.jdbcUrl(), postgres.username(), postgres.password())) {
            connection.createStatement().execute("set search_path to \"" + schema + "\", public");
            assertAuditIdempotencyContract(connection);
        }
    }

    @Test
    void upgradesExistingV16RowsToV17OnExplicitEphemeralPostgres() throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = "v17_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        flyway(postgres, schema, "16").migrate();
        UUID legacyId = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(
                postgres.jdbcUrl(), postgres.username(), postgres.password())) {
            connection.createStatement().execute("set search_path to \"" + schema + "\", public");
            try (var insert = connection.prepareStatement("""
                    insert into jt_gateway_audit_events (
                      id, event_type, result, reason_code, occurred_at, gateway_instance
                    ) values (?, 'OFFLINE', 'APPLIED', 'LEGACY_ROW', now(), 'legacy-gateway')
                    """)) {
                insert.setObject(1, legacyId);
                insert.executeUpdate();
            }
        }

        flyway(postgres, schema, null).migrate();

        try (Connection connection = DriverManager.getConnection(
                postgres.jdbcUrl(), postgres.username(), postgres.password())) {
            connection.createStatement().execute("set search_path to \"" + schema + "\", public");
            assertThat(queryUuid(connection,
                    "select idempotency_key from jt_gateway_audit_events where id = ?", legacyId))
                    .isEqualTo(legacyId);
            assertAuditIdempotencyContract(connection);
        }
    }

    private static ExternalPostgres externalPostgres() {
        Assumptions.assumeTrue(Boolean.getBoolean(INTEGRATION_PROPERTY),
                "real PostgreSQL migration verification was not enabled");
        String jdbcUrl = System.getProperty("drt.integration.postgis.jdbc-url", "");
        String username = System.getProperty("drt.integration.postgis.username", "");
        String password = System.getProperty("drt.integration.postgis.password", "");
        Assumptions.assumeTrue(Boolean.getBoolean("drt.integration.postgis.external-ephemeral")
                        && "task17".equals(username)
                        && jdbcUrl.matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):\\d+/task17"),
                "requires an explicit empty loopback task17 PostgreSQL database");
        return new ExternalPostgres(jdbcUrl, username, password);
    }

    private static Flyway flyway(ExternalPostgres postgres, String schema, String target) {
        var configuration = Flyway.configure()
                .dataSource(postgres.jdbcUrl(), postgres.username(), postgres.password())
                .locations("classpath:db/migration")
                .schemas(schema).defaultSchema(schema).createSchemas(true);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static void assertAuditIdempotencyContract(Connection connection) throws Exception {
        try (var column = connection.prepareStatement("""
                select is_nullable
                from information_schema.columns
                where table_schema = current_schema()
                  and table_name = 'jt_gateway_audit_events'
                  and column_name = 'idempotency_key'
                """); var rows = column.executeQuery()) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("NO");
        }
        try (var columns = connection.prepareStatement("""
                select column_name
                from information_schema.columns
                where table_schema = current_schema()
                  and table_name = 'jt_gateway_ingress_receipts'
                  and column_name in ('terminal_id', 'vehicle_id', 'ingress_kind')
                order by column_name
                """); var rows = columns.executeQuery()) {
            java.util.List<String> names = new java.util.ArrayList<>();
            while (rows.next()) names.add(rows.getString(1));
            assertThat(names).containsExactly("ingress_kind", "terminal_id", "vehicle_id");
        }
        UUID first = UUID.randomUUID();
        insertAudit(connection, UUID.randomUUID(), first);
        assertThatThrownBy(() -> insertAudit(connection, UUID.randomUUID(), first))
                .hasMessageContaining("uq_jt_gateway_audit_events_idempotency_key");
    }

    private static void insertAudit(Connection connection, UUID id, UUID idempotencyKey) throws Exception {
        try (var insert = connection.prepareStatement("""
                insert into jt_gateway_audit_events (
                  id, idempotency_key, event_type, result, reason_code, occurred_at, gateway_instance
                ) values (?, ?, 'OFFLINE', 'APPLIED', 'CONTRACT_CHECK', now(), 'migration-check')
                """)) {
            insert.setObject(1, id);
            insert.setObject(2, idempotencyKey);
            insert.executeUpdate();
        }
    }

    private static UUID queryUuid(Connection connection, String sql, UUID argument) throws Exception {
        try (var query = connection.prepareStatement(sql)) {
            query.setObject(1, argument);
            try (var rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getObject(1, UUID.class);
            }
        }
    }

    private record ExternalPostgres(String jdbcUrl, String username, String password) { }
}
