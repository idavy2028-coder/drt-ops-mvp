package com.idavy.drtops.jtgateway.ingress;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record GatewayIngressEnvelope(
        int schemaVersion,
        UUID idempotencyKey,
        IngressKind kind,
        Instant gatewayReceivedAt,
        String payloadJson) {
    public GatewayIngressEnvelope {
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(gatewayReceivedAt, "gatewayReceivedAt");
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new IllegalArgumentException("payloadJson must not be blank");
        }
    }
}
