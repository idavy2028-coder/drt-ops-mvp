package com.idavy.drtops.jtgateway.ingress;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.List;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class OperationsApiClient implements GatewayOutboxDispatcher.DeliveryClient {
    public static final String CREDENTIAL_VERSION_HEADER = "X-Service-Credential-Version";

    private final RestClient restClient;
    private final URI endpoint;
    private final Supplier<String> bearerCredential;
    private final int credentialVersion;
    private final AtomicBoolean operationsApiReachable = new AtomicBoolean(false);
    private final AtomicBoolean deliveryAttempted = new AtomicBoolean(false);
    private final AtomicBoolean lastDeliverySuccessful = new AtomicBoolean(false);

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
        deliveryAttempted.set(true);
        lastDeliverySuccessful.set(false);
        String credential = bearerCredential.get();
        if (credential == null || credential.isBlank()) {
            operationsApiReachable.set(false);
            return GatewayOutboxDispatcher.DeliveryResult.retryable("CREDENTIAL_UNAVAILABLE");
        }
        try {
            ResponseEntity<IngressResponse> response = restClient.post()
                    .uri(endpoint)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential)
                    .header(CREDENTIAL_VERSION_HEADER, Integer.toString(credentialVersion))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(List.copyOf(batch))
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (request, rejected) -> {
                        // Preserve the status as a bounded retry code; never read or log the body.
                    })
                    .toEntity(IngressResponse.class);
            HttpStatusCode status = response.getStatusCode();
            boolean successful = status.is2xxSuccessful();
            operationsApiReachable.set(successful);
            if (!successful) {
                return GatewayOutboxDispatcher.DeliveryResult.retryable("HTTP_" + status.value());
            }
            GatewayOutboxDispatcher.DeliveryResult result = validateResponse(batch, response.getBody());
            lastDeliverySuccessful.set(result.successful());
            return result;
        } catch (RestClientException unavailable) {
            operationsApiReachable.set(false);
            return GatewayOutboxDispatcher.DeliveryResult.retryable("CLIENT_UNAVAILABLE");
        }
    }

    public boolean operationsApiReachable() {
        return operationsApiReachable.get();
    }

    public boolean deliveryAttempted() {
        return deliveryAttempted.get();
    }

    public boolean lastDeliverySuccessful() {
        return lastDeliverySuccessful.get();
    }

    private static GatewayOutboxDispatcher.DeliveryResult validateResponse(
            List<GatewayIngressEnvelope> batch, IngressResponse response) {
        if (response == null || response.data() == null || response.data().size() != batch.size()) {
            return GatewayOutboxDispatcher.DeliveryResult.retryable("API_RESPONSE_INCOMPLETE");
        }
        Set<UUID> expected = new HashSet<>();
        for (GatewayIngressEnvelope envelope : batch) {
            expected.add(envelope.idempotencyKey());
        }
        Set<UUID> received = new HashSet<>();
        for (IngressResult result : response.data()) {
            if (result == null || result.idempotencyKey() == null
                    || !expected.contains(result.idempotencyKey())
                    || !received.add(result.idempotencyKey())) {
                return GatewayOutboxDispatcher.DeliveryResult.retryable("API_RESPONSE_INCOMPLETE");
            }
            if ("REJECTED".equals(result.status())) {
                return GatewayOutboxDispatcher.DeliveryResult.retryable("API_ITEM_REJECTED");
            }
            if (!("ACCEPTED".equals(result.status()) || "REPLAYED".equals(result.status()))) {
                return GatewayOutboxDispatcher.DeliveryResult.retryable("API_ITEM_NOT_SUCCESSFUL");
            }
        }
        return received.equals(expected)
                ? GatewayOutboxDispatcher.DeliveryResult.success()
                : GatewayOutboxDispatcher.DeliveryResult.retryable("API_RESPONSE_INCOMPLETE");
    }

    private record IngressResponse(List<IngressResult> data) { }

    private record IngressResult(UUID idempotencyKey, String status, List<String> reasonCodes) { }
}
