package com.idavy.drtops.domain.location;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

@EnabledIfSystemProperty(named = "drt.integration.postgis", matches = "true")
class PostgisServiceAreaLocationCheckerTest {

    private static final String LOCAL_POSTGIS_URL = "jdbc:postgresql://127.0.0.1:15432/drt_ops";
    private static final String USERNAME = "drt_ops";
    private static final String PASSWORD = "drt_ops";

    @Test
    void coversPointInsideOnBoundaryAndOutsideEnabledArea() {
        if (dockerIsAvailable()) {
            try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgis/postgis:16-3.5")
                    .withDatabaseName("drt_ops")
                    .withUsername(USERNAME)
                    .withPassword(PASSWORD)) {
                postgres.start();
                verifyCovers(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            }
            return;
        }

        verifyCovers(LOCAL_POSTGIS_URL, USERNAME, PASSWORD);
    }

    private static void verifyCovers(String jdbcUrl, String username, String password) {
        Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        DriverManagerDataSource dataSource = new DriverManagerDataSource(jdbcUrl, username, password);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        transaction.executeWithoutResult(status -> {
            jdbcTemplate.update("update service_areas set enabled = false");
            UUID ruleSetId = jdbcTemplate.queryForObject(
                    "select id from dispatch_rule_sets order by created_at limit 1",
                    UUID.class);
            jdbcTemplate.update("""
                    insert into service_areas (
                      id, name, boundary, service_start, service_end, rule_set_id, enabled
                    ) values (?, 'Task 2 PostGIS test',
                      ST_GeogFromText('SRID=4326;POLYGON((121.0 31.0,121.1 31.0,121.1 31.1,121.0 31.1,121.0 31.0))'),
                      '06:00', '23:00', ?, true)
                    """, UUID.randomUUID(), ruleSetId);

            PostgisServiceAreaLocationChecker checker = new PostgisServiceAreaLocationChecker(jdbcTemplate);

            assertThat(checker.isInsideEnabledArea(
                    new BigDecimal("121.0500000"), new BigDecimal("31.0500000"))).isTrue();
            assertThat(checker.isInsideEnabledArea(
                    new BigDecimal("121.0000000"), new BigDecimal("31.0500000"))).isTrue();
            assertThat(checker.isInsideEnabledArea(
                    new BigDecimal("121.2000000"), new BigDecimal("31.2000000"))).isFalse();

            status.setRollbackOnly();
        });
    }

    private static boolean dockerIsAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
