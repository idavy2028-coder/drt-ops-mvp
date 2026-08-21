package com.idavy.drtops.domain.location;

import com.idavy.drtops.domain.terminal.JtGatewayAuditEvent;
import com.idavy.drtops.domain.terminal.JtGatewayAuditEventRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists sanitized gateway protocol rejections exactly once using the shared ingress receipt. */
@Service
public class ProtocolAuditIngressService {
    private static final Instant POSTGRES_TIMESTAMPTZ_MIN = Instant.ofEpochSecond(-210_866_803_200L);
    private static final Instant POSTGRES_TIMESTAMPTZ_MAX = Instant.ofEpochSecond(9_224_318_016_000L)
            .minusNanos(1_000);
    private final JtGatewayAuditEventRepository audits;
    private final JtGatewayIngressReceiptRepository receipts;
    private final JtGatewayIngressReceiptClaimer receiptClaimer;

    public ProtocolAuditIngressService(
            JtGatewayAuditEventRepository audits,
            JtGatewayIngressReceiptRepository receipts,
            JtGatewayIngressReceiptClaimer receiptClaimer) {
        this.audits = audits;
        this.receipts = receipts;
        this.receiptClaimer = receiptClaimer;
    }

    @Transactional
    public void ingest(List<ProtocolAuditFact> batch) {
        if (batch == null || batch.isEmpty() || batch.size() > 50) {
            throw new IllegalArgumentException("invalid protocol audit batch");
        }
        batch.forEach(ProtocolAuditIngressService::validate);
        for (ProtocolAuditFact fact : batch) {
            if (receiptClaimer.claim(fact.idempotencyKey()) == 0) {
                continue;
            }
            audits.save(JtGatewayAuditEvent.record(
                    fact.terminalId(), fact.vehicleId(), JtGatewayAuditEvent.EventType.PROTOCOL_REJECTED,
                    JtGatewayAuditEvent.Result.REJECTED, fact.reasonCode(), fact.protocolVersion(),
                    fact.messageId(), fact.payloadDigest(), null,
                    OffsetDateTime.ofInstant(fact.occurredAt(), ZoneOffset.UTC), "JT_GATEWAY_SERVICE"));
            JtGatewayIngressReceipt receipt = receipts.findById(fact.idempotencyKey()).orElseThrow();
            receipt.complete("ACCEPTED", List.of(fact.reasonCode()), OffsetDateTime.now(ZoneOffset.UTC));
        }
    }

    private static void validate(ProtocolAuditFact fact) {
        if (fact == null || fact.idempotencyKey() == null || fact.terminalId() == null || fact.vehicleId() == null
                || !validText(fact.reasonCode(), 80) || !validText(fact.protocolVersion(), 40)
                || fact.messageId() < 0 || fact.messageId() > 0xffff
                || fact.payloadDigest() == null || !fact.payloadDigest().matches("[0-9a-f]{64}")
                || fact.occurredAt() == null || fact.occurredAt().isBefore(POSTGRES_TIMESTAMPTZ_MIN)
                || fact.occurredAt().isAfter(POSTGRES_TIMESTAMPTZ_MAX)) {
            throw new IllegalArgumentException("invalid protocol audit fact");
        }
    }

    private static boolean validText(String value, int maximumLength) {
        return value != null && !value.isBlank() && value.length() <= maximumLength;
    }

    public record ProtocolAuditFact(
            UUID idempotencyKey,
            UUID terminalId,
            UUID vehicleId,
            String reasonCode,
            String protocolVersion,
            int messageId,
            String payloadDigest,
            Instant occurredAt) {
    }
}
