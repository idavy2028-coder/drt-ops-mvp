package com.idavy.drtops.domain.location;

import com.idavy.drtops.domain.terminal.JtGatewayAuditEvent;
import com.idavy.drtops.domain.terminal.JtGatewayAuditEventRepository;
import com.idavy.drtops.domain.terminal.JtTerminalRepository;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Persists sanitized gateway protocol rejections exactly once using the shared ingress receipt. */
@Service
public class ProtocolAuditIngressService {
    private static final Instant POSTGRES_TIMESTAMPTZ_MIN = Instant.ofEpochSecond(-210_866_803_200L);
    private static final Instant POSTGRES_TIMESTAMPTZ_MAX = Instant.ofEpochSecond(9_224_318_016_000L)
            .minusNanos(1_000);
    private final JtGatewayAuditEventRepository audits;
    private final JtGatewayIngressReceiptRepository receipts;
    private final JtGatewayIngressReceiptClaimer receiptClaimer;
    private final TransactionTemplate itemTransaction;
    private final JtTerminalRepository terminals;
    private final VehicleRepository vehicles;

    public ProtocolAuditIngressService(
            JtGatewayAuditEventRepository audits,
            JtGatewayIngressReceiptRepository receipts,
            JtGatewayIngressReceiptClaimer receiptClaimer,
            JtTerminalRepository terminals,
            VehicleRepository vehicles,
            PlatformTransactionManager transactionManager) {
        this.audits = audits;
        this.receipts = receipts;
        this.receiptClaimer = receiptClaimer;
        this.terminals = terminals;
        this.vehicles = vehicles;
        this.itemTransaction = new TransactionTemplate(transactionManager);
        this.itemTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
                    fact.idempotencyKey(), fact.terminalId(), fact.vehicleId(),
                    JtGatewayAuditEvent.EventType.PROTOCOL_REJECTED,
                    JtGatewayAuditEvent.Result.REJECTED, fact.reasonCode(), fact.protocolVersion(),
                    fact.messageId(), fact.payloadDigest(), null,
                    OffsetDateTime.ofInstant(fact.occurredAt(), ZoneOffset.UTC), "JT_GATEWAY_SERVICE"));
            JtGatewayIngressReceipt receipt = receipts.findById(fact.idempotencyKey()).orElseThrow();
            receipt.complete("ACCEPTED", List.of(fact.reasonCode()), OffsetDateTime.now(ZoneOffset.UTC));
        }
    }
    public Result ingest(ProtocolAuditFact fact) {
        if (fact == null || fact.idempotencyKey() == null) {
            throw new IllegalArgumentException("protocol audit item must be correlatable");
        }
        return itemTransaction.execute(status -> ingestClaimed(fact));
    }
    private Result ingestClaimed(ProtocolAuditFact fact) {
        if (receiptClaimer.claim(fact.idempotencyKey()) == 0) {
            JtGatewayIngressReceipt receipt = receipts.findById(fact.idempotencyKey()).orElseThrow();
            return new Result(fact.idempotencyKey(),
                    "ACCEPTED".equals(receipt.getFinalStatus()) ? "REPLAYED" : "REJECTED",
                    receipt.getReasonCodes());
        }
        try {
            validate(fact);
        } catch (IllegalArgumentException invalid) {
            return reject(fact, "INVALID_PAYLOAD");
        }
        if (!terminals.existsById(fact.terminalId()) || !vehicles.existsById(fact.vehicleId())) {
            return reject(fact, "INVALID_PAYLOAD");
        }
        audits.save(JtGatewayAuditEvent.record(
                fact.idempotencyKey(), fact.terminalId(), fact.vehicleId(),
                JtGatewayAuditEvent.EventType.PROTOCOL_REJECTED,
                JtGatewayAuditEvent.Result.REJECTED, fact.reasonCode(), fact.protocolVersion(),
                fact.messageId(), fact.payloadDigest(), null,
                OffsetDateTime.ofInstant(fact.occurredAt(), ZoneOffset.UTC), "JT_GATEWAY_SERVICE"));
        JtGatewayIngressReceipt receipt = receipts.findById(fact.idempotencyKey()).orElseThrow();
        receipt.complete("ACCEPTED", List.of(fact.reasonCode()), OffsetDateTime.now(ZoneOffset.UTC));
        return new Result(fact.idempotencyKey(), "ACCEPTED", List.of(fact.reasonCode()));
    }
    private Result reject(ProtocolAuditFact fact, String reason) {
        JtGatewayIngressReceipt receipt = receipts.findById(fact.idempotencyKey()).orElseThrow();
        receipt.complete("REJECTED", List.of(reason), OffsetDateTime.now(ZoneOffset.UTC));
        return new Result(fact.idempotencyKey(), "REJECTED", List.of(reason));
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
    public record Result(UUID idempotencyKey, String status, List<String> reasonCodes) { }
}
