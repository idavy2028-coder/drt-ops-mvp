package com.idavy.drtops.jtgateway.ingress;

import java.net.URI;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Probes only the public operations API health endpoint and never adds service credentials. */
public final class OperationsApiHealthProbe {
    private final RestClient client;
    private final URI endpoint;
    private final OperationsApiStatus status;

    public OperationsApiHealthProbe(
            RestClient.Builder builder, URI endpoint, OperationsApiStatus status) {
        this.client = Objects.requireNonNull(builder, "builder").build();
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.status = Objects.requireNonNull(status, "status");
    }

    @Scheduled(
            fixedDelayString = "${jt.gateway.health.api-probe-fixed-delay-ms:10000}",
            initialDelayString = "${jt.gateway.health.api-probe-initial-delay-ms:0}",
            scheduler = "gatewayProbeTaskScheduler")
    void probe() {
        try {
            client.get().uri(endpoint).retrieve().toBodilessEntity();
            status.success(OperationsApiStatus.Source.PROBE, "HEALTH_PROBE");
        } catch (RestClientException unavailable) {
            status.failure(OperationsApiStatus.Source.PROBE, "HEALTH_PROBE");
        }
    }
}
