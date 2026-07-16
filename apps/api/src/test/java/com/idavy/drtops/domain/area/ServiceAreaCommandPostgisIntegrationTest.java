package com.idavy.drtops.domain.area;

import static org.assertj.core.api.Assertions.assertThat;

import com.idavy.drtops.domain.dispatch.DispatchRuleSet;
import com.idavy.drtops.domain.dispatch.DispatchRuleSetRepository;
import com.idavy.drtops.domain.location.ServiceAreaLocationChecker;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@EnabledIf(value = "integrationEnvironmentAvailable",
        disabledReason = "需要启用 drt.integration.postgis 并由 Docker/Testcontainers 提供隔离 PostGIS 数据库")
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false"
})
class ServiceAreaCommandPostgisIntegrationTest {

    private static PostgreSQLContainer<?> postgres;

    @Autowired
    ServiceAreaCommandService commandService;

    @Autowired
    ServiceAreaLocationChecker locationChecker;

    @Autowired
    ServiceAreaRepository serviceAreaRepository;

    @Autowired
    DispatchRuleSetRepository ruleSetRepository;

    @DynamicPropertySource
    static void postgisProperties(DynamicPropertyRegistry registry) {
        PostgreSQLContainer<?> container = postgres();
        container.start();
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", container::getUsername);
        registry.add("spring.datasource.password", container::getPassword);
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @AfterEach
    void cleanUp() {
        serviceAreaRepository.deleteAll();
        ruleSetRepository.deleteAll();
    }

    @Test
    void writesJtsGeographyAndSwitchesPublishedBoundaryOnlyOnPublish() {
        UUID ruleSetId = UUID.randomUUID();
        UUID areaId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        ruleSetRepository.save(DispatchRuleSet.defaultRules(ruleSetId));

        commandService.create(new ServiceAreaCommandService.CreateServiceAreaCommand(
                areaId,
                "PostGIS 服务区",
                "POLYGON((121.0 31.0,121.1 31.0,121.1 31.1,121.0 31.1,121.0 31.0))",
                "06:00:00",
                "23:00:00",
                ruleSetId), actorId);
        ServiceAreaView publishedA = commandService.publish(areaId, actorId);

        commandService.saveBoundary(areaId, new ServiceAreaBoundaryRequest(
                "POLYGON((122.0 32.0,122.1 32.0,122.1 32.1,122.0 32.1,122.0 32.0))", null), actorId);

        assertThat(locationChecker.checkPublishedArea(areaId, new BigDecimal("121.05"), new BigDecimal("31.05")).inside())
                .isTrue();
        assertThat(locationChecker.checkPublishedArea(areaId, new BigDecimal("122.05"), new BigDecimal("32.05")).inside())
                .isFalse();

        ServiceAreaView publishedB = commandService.publish(areaId, actorId);

        assertThat(publishedA.boundaryVersion()).isEqualTo(1);
        assertThat(publishedB.boundaryVersion()).isEqualTo(2);
        assertThat(locationChecker.checkPublishedArea(areaId, new BigDecimal("122.05"), new BigDecimal("32.05")).inside())
                .isTrue();
    }

    static boolean integrationEnvironmentAvailable() {
        return Boolean.getBoolean("drt.integration.postgis") && dockerIsAvailable();
    }

    private static boolean dockerIsAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static synchronized PostgreSQLContainer<?> postgres() {
        if (postgres == null) {
            postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.5")
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("drt_ops")
                    .withUsername("drt_ops")
                    .withPassword("drt_ops");
        }
        return postgres;
    }
}
