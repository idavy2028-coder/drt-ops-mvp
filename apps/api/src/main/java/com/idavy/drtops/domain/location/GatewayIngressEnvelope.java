package com.idavy.drtops.domain.location;

import java.time.Instant;
import java.util.UUID;

public record GatewayIngressEnvelope(int schemaVersion, UUID idempotencyKey, String kind, Instant gatewayReceivedAt, String payloadJson) { }
