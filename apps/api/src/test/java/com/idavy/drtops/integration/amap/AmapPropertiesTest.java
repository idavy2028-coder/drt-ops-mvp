package com.idavy.drtops.integration.amap;

import static org.assertj.core.api.Assertions.assertThat;

import com.idavy.drtops.domain.map.MapProviderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AmapPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void defaultsToDisabledAndKeepsTimeoutDefaults() {
        contextRunner.run(context -> {
            AmapProperties properties = context.getBean(AmapProperties.class);

            assertThat(properties.isEnabled()).isFalse();
            assertThat(properties.getWebServiceKey()).isBlank();
            assertThat(properties.getBaseUrl()).isEqualTo("https://restapi.amap.com");
            assertThat(properties.getConnectTimeoutMs()).isEqualTo(2_000);
            assertThat(properties.getReadTimeoutMs()).isEqualTo(5_000);
            assertThat(properties.providerStatus()).isEqualTo(
                    MapProviderStatus.degraded("AMAP", "disabled", "GCJ-02"));
        });
    }

    @Test
    void reportsMissingWebServiceKeyAsDegradedWhenEnabled() {
        contextRunner
                .withPropertyValues("drt.map.amap.enabled=true", "drt.map.amap.web-service-key=")
                .run(context -> {
                    AmapProperties properties = context.getBean(AmapProperties.class);

                    assertThat(properties.isAvailable()).isFalse();
                    assertThat(properties.providerStatus()).isEqualTo(
                            MapProviderStatus.degraded("AMAP", "missing-web-service-key", "GCJ-02"));
                });
    }

    @Test
    void bindsAmapWebServiceProperties() {
        contextRunner
                .withPropertyValues(
                        "drt.map.amap.enabled=true",
                        "drt.map.amap.web-service-key=test-key",
                        "drt.map.amap.base-url=https://example.test/amap",
                        "drt.map.amap.connect-timeout-ms=1234",
                        "drt.map.amap.read-timeout-ms=6789")
                .run(context -> {
                    AmapProperties properties = context.getBean(AmapProperties.class);

                    assertThat(properties.isAvailable()).isTrue();
                    assertThat(properties.getWebServiceKey()).isEqualTo("test-key");
                    assertThat(properties.getBaseUrl()).isEqualTo("https://example.test/amap");
                    assertThat(properties.getConnectTimeoutMs()).isEqualTo(1_234);
                    assertThat(properties.getReadTimeoutMs()).isEqualTo(6_789);
                    assertThat(properties.providerStatus()).isEqualTo(
                            MapProviderStatus.available("AMAP", "GCJ-02"));
                });
    }

    @EnableConfigurationProperties(AmapProperties.class)
    static class TestConfiguration {
    }
}
