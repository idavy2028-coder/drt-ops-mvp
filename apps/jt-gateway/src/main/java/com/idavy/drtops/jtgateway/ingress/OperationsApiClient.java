package com.idavy.drtops.jtgateway.ingress;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class OperationsApiClient implements GatewayOutboxDispatcher.DeliveryClient {
    public static final String CREDENTIAL_VERSION_HEADER = "X-Service-Credential-Version";

    private final RestClient restClient;
    private final URI endpoint;
    private final Supplier<String> bearerCredential;
    private final int credentialVersion;
    private final AtomicBoolean operationsApiReachable = new AtomicBoolean(false);

    public OperationsApiClient(
            RestClient.Builder builder,
            URI endpoint,
            Supplier<String> bearerCredential,
            int credentialVersion) {
        this.restClient = Objects.requireNonNull(builder, "builder").build();
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.bearerCredential = Objects.requireNonNull(bearerCredential, "bearerCredential");
        if (credentialVersion < 1) {
            throw new IllegalArgumentException("credentialVersion must be positive");
        }
        this.credentialVersion = credentialVersion;
    }

    @Override
    public GatewayOutboxDispatcher.DeliveryResult deliver(List<GatewayIngressEnvelope> batch) {
        if (batch == null || batch.isEmpty()) {
            throw new IllegalArgumentException("batch must not be empty");
        }
        String credential = bearerCredential.get();
        if (credential == null || credential.isBlank()) {
            operationsApiReachable.set(false);
            return GatewayOutboxDispatcher.DeliveryResult.retryable("CREDENTIAL_UNAVAILABLE");
        }
        try {
            HttpStatusCode status = restClient.post()
                    .uri(endpoint)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential)
                    .header(CREDENTIAL_VERSION_HEADER, Integer.toString(credentialVersion))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(List.copyOf(batch))
                    .exchange((request, response) -> response.getStatusCode());
            boolean successful = status.is2xxSuccessful();
            operationsApiReachable.set(successful);
            return successful
                    ? GatewayOutboxDispatcher.DeliveryResult.success()
                    : GatewayOutboxDispatcher.DeliveryResult.retryable(
                            "HTTP_" + status.value());
        } catch (RestClientException unavailable) {
            operationsApiReachable.set(false);
            return GatewayOutboxDispatcher.DeliveryResult.retryable("CLIENT_UNAVAILABLE");
        }
    }

    public boolean operationsApiReachable() {
        return operationsApiReachable.get();
    }
}
