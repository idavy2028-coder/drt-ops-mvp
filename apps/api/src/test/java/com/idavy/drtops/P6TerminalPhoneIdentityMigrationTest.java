package com.idavy.drtops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class P6TerminalPhoneIdentityMigrationTest {

    private static final String INTEGRATION_PROPERTY = "drt.integration.phone-identity";

    @Test
    void migratesFreshSchemaAndEnforcesSemanticPhoneUniqueness() throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = schema("v18_fresh");

        flyway(postgres, schema, null).migrate();

        try (Connection connection = connection(postgres, schema)) {
            assertPhoneIdentityColumn(connection);
            insertTerminalAfterV18(
                    connection, "013800000002", "00000000013800000002",
                    "T-V18-FRESH-SHORT", "JT808_2019");
            assertThatThrownBy(() -> insertTerminalAfterV18(
                    connection, "00000000013800000002", "00000000013800000002",
                    "T-V18-FRESH-FIXED", "JT808_2019"))
                    .hasMessageContaining("uq_jt_terminals_terminal_phone_identity");
        }
    }

    @Test
    void upgradesExistingV17RowsAndBackfillsCanonicalIdentity() throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = schema("v18_upgrade");
        flyway(postgres, schema, "17").migrate();
        try (Connection connection = connection(postgres, schema)) {
            insertTerminalBeforeV18(
                    connection, "013800000003", "T-V18-UPGRADE-SHORT", "JT808_2019");
        }

        flyway(postgres, schema, null).migrate();

        try (Connection connection = connection(postgres, schema)) {
            assertPhoneIdentityColumn(connection);
            try (var query = connection.prepareStatement("""
                    select terminal_phone_identity
                    from jt_terminals
                    where terminal_code = 'T-V18-UPGRADE-SHORT'
                    """); var rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("00000000013800000003");
            }
        }
    }

    @Test
    void rejectsUpgradeWhenLegacyRowsAlreadyContainSemanticDuplicates() throws Exception {
        ExternalPostgres postgres = externalPostgres();
        String schema = schema("v18_duplicate_gate");
        Flyway baseline = flyway(postgres, schema, "17");
        baseline.migrate();
        try (Connection connection = connection(postgres, schema)) {
            insertTerminalBeforeV18(
                    connection, "013800000004", "T-V18-DUP-SHORT", "JT808_2019");
            insertTerminalBeforeV18(
                    connection, "00000000013800000004", "T-V18-DUP-FIXED", "JT808_2019");
        }

        assertThatThrownBy(() -> flyway(postgres, schema, null).migrate())
                .hasStackTraceContaining("uq_jt_terminals_terminal_phone_identity")
                .hasStackTraceContaining("terminal_phone_identity");
        try (Connection connection = connection(postgres, schema)) {
            assertThat(queryCount(connection, """
                    select count(*)
                    from information_schema.columns
                    where table_schema = current_schema()
                      and table_name = 'jt_terminals'
                      and column_name = 'terminal_phone_identity'
                    """)).isZero();
            assertThat(queryCount(connection, """
                    select count(*)
                    from flyway_schema_history
                    where version = '18'
                    """)).isZero();
        }
    }

    private static ExternalPostgres externalPostgres() {
        Assumptions.assumeTrue(Boolean.getBoolean(INTEGRATION_PROPERTY),
                "terminal phone identity migration verification was not enabled");
        String jdbcUrl = System.getProperty("drt.integration.phone-identity.jdbc-url", "");
        String username = System.getProperty("drt.integration.phone-identity.username", "");
        String password = System.getProperty("drt.integration.phone-identity.password", "");
        Assumptions.assumeTrue(Boolean.getBoolean("drt.integration.phone-identity.external-ephemeral")
                        && "phoneidentity".equals(username)
                        && jdbcUrl.matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):\\d+/phone_identity"),
                "requires an explicit empty loopback phone_identity PostgreSQL database");
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

    private static Connection connection(ExternalPostgres postgres, String schema) throws Exception {
        Connection connection = DriverManager.getConnection(
                postgres.jdbcUrl(), postgres.username(), postgres.password());
        connection.createStatement().execute("set search_path to \"" + schema + "\", public");
        return connection;
    }

    private static void insertTerminalBeforeV18(
            Connection connection, String phone, String code, String protocolVersion) throws Exception {
        try (var insert = connection.prepareStatement("""
                insert into jt_terminals (
                  id, terminal_phone, terminal_code, manufacturer_id, model, protocol_version,
                  source_coordinate_system, status, auth_token_hash, auth_token_version
                ) values (?, ?, ?, 'MFG', 'MODEL', ?, 'GCJ02', 'PENDING', repeat('a', 64), 1)
                """)) {
            insert.setObject(1, UUID.randomUUID());
            insert.setString(2, phone);
            insert.setString(3, code);
            insert.setString(4, protocolVersion);
            insert.executeUpdate();
        }
    }

    private static void insertTerminalAfterV18(
            Connection connection,
            String phone,
            String phoneIdentity,
            String code,
            String protocolVersion) throws Exception {
        try (var insert = connection.prepareStatement("""
                insert into jt_terminals (
                  id, terminal_phone, terminal_phone_identity, terminal_code,
                  manufacturer_id, model, protocol_version, source_coordinate_system,
                  status, auth_token_hash, auth_token_version
                ) values (?, ?, ?, ?, 'MFG', 'MODEL', ?, 'GCJ02', 'PENDING', repeat('a', 64), 1)
                """)) {
            insert.setObject(1, UUID.randomUUID());
            insert.setString(2, phone);
            insert.setString(3, phoneIdentity);
            insert.setString(4, code);
            insert.setString(5, protocolVersion);
            insert.executeUpdate();
        }
    }

    private static void assertPhoneIdentityColumn(Connection connection) throws Exception {
        try (var query = connection.prepareStatement("""
                select is_nullable
                from information_schema.columns
                where table_schema = current_schema()
                  and table_name = 'jt_terminals'
                  and column_name = 'terminal_phone_identity'
                """); var rows = query.executeQuery()) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("NO");
        }
    }

    private static int queryCount(Connection connection, String sql) throws Exception {
        try (var query = connection.prepareStatement(sql); var rows = query.executeQuery()) {
            assertThat(rows.next()).isTrue();
            return rows.getInt(1);
        }
    }

    private static String schema(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private record ExternalPostgres(String jdbcUrl, String username, String password) {
    }
}
