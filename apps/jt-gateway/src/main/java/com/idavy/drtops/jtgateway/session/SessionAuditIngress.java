package com.idavy.drtops.jtgateway.session;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SessionAuditIngress(
        SessionAuditType type,
        UUID terminalId,
        String terminalAlias,
        String remoteAddress,
        String reasonCode,
        Instant occurredAt) {
    public SessionAuditIngress {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(terminalAlias, "terminalAlias");
        Objects.requireNonNull(remoteAddress, "remoteAddress");
        Objects.requireNonNull(reasonCode, "reasonCode");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
