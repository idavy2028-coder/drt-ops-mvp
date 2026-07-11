package com.idavy.drtops;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

class FlywayPostgresqlSupportTest {

    @Test
    void flywayPostgresqlDatabaseSupportIsAvailableAtRuntime() {
        assertThatCode(() -> Class.forName("org.flywaydb.database.postgresql.PostgreSQLDatabaseType"))
                .doesNotThrowAnyException();
    }
}
