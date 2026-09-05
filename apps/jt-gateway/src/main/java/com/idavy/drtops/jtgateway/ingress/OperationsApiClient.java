package com.idavy.drtops.jtgateway.ingress;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final URI auditEndpoint;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Supplier<String> bearerCredential;
    private final int credentialVersion;
    private final OperationsApiStatus apiStatus;
    private final AtomicBoolean operationsApiReachable = new AtomicBoolean(false);
    private final AtomicBoolean deliveryAttempted = new AtomicBoolean(false);
    private final AtomicBoolean lastDeliverySuccessful = new AtomicBoolean(false);

    public OperationsApiClient(
            RestClient.Builder builder,
            URI endpoint,
            Supplier<String> bearerCredential,
            int credentialVersion) {
        this(builder, endpoint, siblingAuditEndpoint(endpoint), bearerCredential, credentialVersion,
                new OperationsApiStatus(java.time.Clock.systemUTC()));
    }

    public OperationsApiClient(
            RestClient.Builder builder,
            URI endpoint,
            Supplier<String> bearerCredential,
            int credentialVersion,
            OperationsApiStatus apiStatus) {
        this(builder, endpoint, siblingAuditEndpoint(endpoint), bearerCredential, credentialVersion, apiStatus);
    }

    public OperationsApiClient(
            RestClient.Builder builder,
            URI endpoint,
            URI auditEndpoint,
            Supplier<String> bearerCredential,
            int credentialVersion,
            OperationsApiStatus apiStatus) {
        this.restClient = Objects.requireNonNull(builder, "builder").build();
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.auditEndpoint = Objects.requireNonNull(auditEndpoint, "auditEndpoint");
        this.bearerCredential = Objects.requireNonNull(bearerCredential, "bearerCredential");
        if (credentialVersion < 1) {
            throw new IllegalArgumentException("credentialVersion must be positive");
        }
        this.credentialVersion = credentialVersion;
        this.apiStatus = Objects.requireNonNull(apiStatus, "apiStatus");
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
            apiStatus.failure(OperationsApiStatus.Source.INGRESS, "INGRESS");
            return GatewayOutboxDispatcher.DeliveryResult.retryable("CREDENTIAL_UNAVAILABLE");
        }
        try {
            List<GatewayIngressEnvelope> ingress = batch.stream()
                    .filter(envelope -> envelope.kind() != IngressKind.SESSION_AUDIT)
                    .toList();
            List<GatewayIngressEnvelope> audits = batch.stream()
                    .filter(envelope -> envelope.kind() == IngressKind.SESSION_AUDIT)
                    .toList();
            GatewayOutboxDispatcher.DeliveryResult result = ingress.isEmpty()
                    ? GatewayOutboxDispatcher.DeliveryResult.success()
                    : deliverIngress(ingress, credential);
            if (result.successful()) {
                for (GatewayIngressEnvelope audit : audits) {
                    result = deliverAudit(audit, credential);
                    if (!result.successful()) {
                        break;
                    }
                }
            }
            lastDeliverySuccessful.set(result.successful());
            if (result.successful()) {
                operationsApiReachable.set(true);
                apiStatus.success(OperationsApiStatus.Source.INGRESS,
                        audits.isEmpty() ? "INGRESS" : "SESSION_AUDIT");
            } else {
                apiStatus.failure(OperationsApiStatus.Source.INGRESS,
                        audits.isEmpty() ? "INGRESS" : "SESSION_AUDIT");
            }
            return result;
        } catch (RestClientException | JsonProcessingException unavailable) {
            operationsApiReachable.set(false);
            apiStatus.failure(OperationsApiStatus.Source.INGRESS, "DELIVERY");
            return GatewayOutboxDispatcher.DeliveryResult.retryable("CLIENT_UNAVAILABLE");
        }
    }

    private GatewayOutboxDispatcher.DeliveryResult deliverIngress(
            List<GatewayIngressEnvelope> batch, String credential) {
        ResponseEntity<IngressResponse> response = restClient.post()
                .uri(endpoint)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential)
                .header(CREDENTIAL_VERSION_HEADER, Integer.toString(credentialVersion))
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.copyOf(batch))
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), (request, rejected) -> {
                    // Preserve the status as a bounded retry code; never log the body.
                })
                .toEntity(IngressResponse.class);
        HttpStatusCode status = response.getStatusCode();
        operationsApiReachable.set(status.is2xxSuccessful());
        return status.is2xxSuccessful()
                ? validateResponse(batch, response.getBody())
                : GatewayOutboxDispatcher.DeliveryResult.retryable("HTTP_" + status.value());
    }

    private GatewayOutboxDispatcher.DeliveryResult deliverAudit(
            GatewayIngressEnvelope envelope, String credential) throws JsonProcessingException {
        JsonNode decoded = objectMapper.readTree(envelope.payloadJson());
        if (decoded == null || !decoded.isObject()) {
            return GatewayOutboxDispatcher.DeliveryResult.retryable("API_AUDIT_PAYLOAD_INVALID");
        }
        com.fasterxml.jackson.databind.node.ObjectNode payload = decoded.deepCopy();
        payload.put("idempotencyKey", envelope.idempotencyKey().toString());
        ResponseEntity<AuditResponse> response = restClient.post()
                .uri(auditEndpoint)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential)
                .header(CREDENTIAL_VERSION_HEADER, Integer.toString(credentialVersion))
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), (request, rejected) -> {
                })
                .toEntity(AuditResponse.class);
        HttpStatusCode status = response.getStatusCode();
        operationsApiReachable.set(status.is2xxSuccessful());
        if (!status.is2xxSuccessful()) {
            return GatewayOutboxDispatcher.DeliveryResult.retryable("HTTP_" + status.value());
        }
        AuditRecorded recorded = response.getBody() == null ? null : response.getBody().data();
        return recorded != null
                        && envelope.idempotencyKey().equals(recorded.idempotencyKey())
                        && ("ACCEPTED".equals(recorded.status()) || "REPLAYED".equals(recorded.status()))
                ? GatewayOutboxDispatcher.DeliveryResult.success()
                : GatewayOutboxDispatcher.DeliveryResult.retryable("API_AUDIT_RESPONSE_INVALID");
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

    private static URI siblingAuditEndpoint(URI ingressEndpoint) {
        String value = Objects.requireNonNull(ingressEndpoint, "ingressEndpoint").toString();
        return value.endsWith("/ingress")
                ? URI.create(value.substring(0, value.length() - "/ingress".length()) + "/audit-events")
                : ingressEndpoint.resolve("audit-events");
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
        for (int index = 0; index < response.data().size(); index++) {
            IngressResult result = response.data().get(index);
            if (result == null || result.idempotencyKey() == null
                    || !batch.get(index).idempotencyKey().equals(result.idempotencyKey())
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

    private record AuditResponse(AuditRecorded data) { }

    private record AuditRecorded(UUID idempotencyKey, String status) { }
}
