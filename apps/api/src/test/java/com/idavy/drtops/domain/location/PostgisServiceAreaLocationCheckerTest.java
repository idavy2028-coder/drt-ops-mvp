package com.idavy.drtops.domain.location;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
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

    private static final String USERNAME = "drt_ops";
    private static final String PASSWORD = "drt_ops";

    @Test
    void coversPointInsideOnBoundaryAndOutsideEnabledArea() {
        Assumptions.assumeTrue(dockerIsAvailable(), "需要 Docker/Testcontainers 提供隔离 PostGIS 数据库");
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgis/postgis:16-3.5")
                .withDatabaseName("drt_ops")
                .withUsername(USERNAME)
                .withPassword(PASSWORD)) {
            postgres.start();
            verifyCovers(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
    }

    @Test
    void usesOnlyPublishedBoundaryAndFiltersContainsChecksByRequestedServiceArea() {
        Assumptions.assumeTrue(dockerIsAvailable(), "需要 Docker/Testcontainers 提供隔离 PostGIS 数据库");
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgis/postgis:16-3.5")
                .withDatabaseName("drt_ops")
                .withUsername(USERNAME)
                .withPassword(PASSWORD)) {
            postgres.start();
            verifyPublishedBoundarySemantics(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
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
                      id, name, boundary, service_start, service_end, rule_set_id, enabled, published_at
                    ) values (?, 'Task 2 PostGIS test',
                      ST_GeogFromText('SRID=4326;POLYGON((121.0 31.0,121.1 31.0,121.1 31.1,121.0 31.1,121.0 31.0))'),
                      '06:00', '23:00', ?, true, now())
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

    private static void verifyPublishedBoundarySemantics(String jdbcUrl, String username, String password) {
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
                    "select id from dispatch_rule_sets order by created_at limit 1", UUID.class);
            UUID areaA = UUID.randomUUID();
            UUID areaB = UUID.randomUUID();
            jdbcTemplate.update("""
                    insert into service_areas (
                      id, name, boundary, draft_boundary, service_start, service_end, rule_set_id, enabled, published_at
                    ) values (?, '已发布 A',
                      ST_GeogFromText('SRID=4326;POLYGON((121.0 31.0,121.1 31.0,121.1 31.1,121.0 31.1,121.0 31.0))'),
                      ST_GeogFromText('SRID=4326;POLYGON((122.0 32.0,122.1 32.0,122.1 32.1,122.0 32.1,122.0 32.0))'),
                      '06:00', '23:00', ?, true, now())
                    """, areaA, ruleSetId);
            jdbcTemplate.update("""
                    insert into service_areas (
                      id, name, boundary, service_start, service_end, rule_set_id, enabled, published_at
                    ) values (?, '已发布 B',
                      ST_GeogFromText('SRID=4326;POLYGON((123.0 33.0,123.1 33.0,123.1 33.1,123.0 33.1,123.0 33.0))'),
                      '06:00', '23:00', ?, true, now())
                    """, areaB, ruleSetId);

            PostgisServiceAreaLocationChecker checker = new PostgisServiceAreaLocationChecker(jdbcTemplate);
            assertThat(checker.isInsideEnabledArea(new BigDecimal("121.0500000"), new BigDecimal("31.0500000"))).isTrue();
            assertThat(checker.isInsideEnabledArea(new BigDecimal("122.0500000"), new BigDecimal("32.0500000"))).isFalse();
            assertThat(checker.checkPublishedArea(new BigDecimal("122.0500000"), new BigDecimal("32.0500000")).inside())
                    .isFalse();

            ServiceAreaLocationChecker.PublishedAreaCheck areaBCheck = checker.checkPublishedArea(
                    areaB, new BigDecimal("121.0500000"), new BigDecimal("31.0500000"));
            assertThat(areaBCheck.serviceAreaId()).isEqualTo(areaB);
            assertThat(areaBCheck.inside()).isFalse();
            assertThat(areaBCheck.distanceToBoundaryMeters()).isGreaterThan(100_000.0);

            ServiceAreaLocationChecker.PublishedAreaCheck areaACheck = checker.checkPublishedArea(
                    areaA, new BigDecimal("121.0500000"), new BigDecimal("31.0500000"));
            assertThat(areaACheck.serviceAreaId()).isEqualTo(areaA);
            assertThat(areaACheck.inside()).isTrue();
            assertThat(areaACheck.distanceToBoundaryMeters()).isZero();

            jdbcTemplate.update("update service_areas set published_at = null where id in (?, ?)", areaA, areaB);
            ServiceAreaLocationChecker.PublishedAreaCheck noPublishedArea = checker.checkPublishedArea(
                    new BigDecimal("121.0500000"), new BigDecimal("31.0500000"));
            assertThat(noPublishedArea.serviceAreaId()).isNull();
            assertThat(noPublishedArea.inside()).isFalse();
            assertThat(noPublishedArea.distanceToBoundaryMeters()).isNull();

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
