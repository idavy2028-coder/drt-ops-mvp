package com.idavy.drtops.jtgateway.ingress;

import java.util.UUID;

/** Sanitized protocol-rejection evidence; raw frames and terminal identities never enter the outbox. */
public record CanonicalProtocolAudit(
        UUID terminalId,
        UUID vehicleId,
        String reasonCode,
        String protocolVersion,
        int messageId,
        String payloadDigest) {
}
