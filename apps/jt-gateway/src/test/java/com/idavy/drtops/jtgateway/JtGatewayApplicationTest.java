package com.idavy.drtops.jtgateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:gateway_boot;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=false"
})
class JtGatewayApplicationTest {

    @Autowired
    private Environment environment;

    @Autowired
    @Qualifier("jtGatewayHealthIndicator")
    private HealthIndicator gatewayHealth;

    @Test
    void startsWithoutOpeningTheDevicePort() {
        assertThat(environment.getProperty("jt.gateway.tcp.enabled", Boolean.class)).isFalse();
    }

    @Test
    void reservesTheDeviceAndManagementPorts() {
        assertThat(environment.getProperty("jt.gateway.tcp.port", Integer.class)).isEqualTo(7611);
        assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(7612);
        assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health,prometheus");
    }

    @Test
    void reportsAnUnavailableBufferAsDownWithoutThrowingAHealthEndpointError() {
        org.springframework.boot.actuate.health.Health health =
                assertDoesNotThrow(gatewayHealth::health);

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).containsEntry("bufferWritable", false);
    }
}
