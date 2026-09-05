package com.idavy.drtops.domain.terminal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "jt_gateway_audit_events")
public class JtGatewayAuditEvent {

    public enum EventType {
        REGISTERED, AUTHENTICATED, ONLINE, OFFLINE, DUPLICATE_LOGIN, SUSPENDED,
        TERMINAL_REPLACED, PROTOCOL_REJECTED, RATE_LIMITED, FORCED_DISCONNECT
    }

    public enum Result { ACCEPTED, REJECTED, APPLIED }

    @Id
    private UUID id;
    @Column(nullable = false, unique = true)
    private UUID idempotencyKey;
    private UUID terminalId;
    private UUID vehicleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private EventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Result result;

    @Column(length = 80)
    private String reasonCode;

    @Column(length = 40)
    private String protocolVersion;

    private Integer messageId;

    @Column(length = 64, columnDefinition = "char(64)")
    private String payloadDigest;

    @Column(length = 80)
    private String remoteAddress;

    @Column(nullable = false)
    private OffsetDateTime occurredAt;

    @Column(nullable = false, length = 120)
    private String gatewayInstance;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    protected JtGatewayAuditEvent() {
    }

    private JtGatewayAuditEvent(
            UUID idempotencyKey,
            UUID terminalId,
            UUID vehicleId,
            EventType eventType,
            Result result,
            String reasonCode,
            String protocolVersion,
            Integer messageId,
            String payloadDigest,
            String remoteAddress,
            OffsetDateTime occurredAt,
            String gatewayInstance) {
        if (payloadDigest != null && !payloadDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("payload digest is invalid");
        }
        if (gatewayInstance == null || gatewayInstance.isBlank()) {
            throw new IllegalArgumentException("gatewayInstance must not be blank");
        }
        this.id = UUID.randomUUID();
        this.idempotencyKey = java.util.Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.terminalId = terminalId;
        this.vehicleId = vehicleId;
        this.eventType = java.util.Objects.requireNonNull(eventType, "eventType");
        this.result = java.util.Objects.requireNonNull(result, "result");
        this.reasonCode = reasonCode;
        this.protocolVersion = protocolVersion;
        this.messageId = messageId;
        this.payloadDigest = payloadDigest;
        this.remoteAddress = remoteAddress;
        this.occurredAt = java.util.Objects.requireNonNull(occurredAt, "occurredAt");
        this.gatewayInstance = gatewayInstance;
        this.createdAt = OffsetDateTime.now();
    }

    public static JtGatewayAuditEvent record(
            UUID terminalId,
            UUID vehicleId,
            EventType eventType,
            Result result,
            String reasonCode,
            String protocolVersion,
            Integer messageId,
            String payloadDigest,
            String remoteAddress,
            OffsetDateTime occurredAt,
            String gatewayInstance) {
        return record(UUID.randomUUID(), terminalId, vehicleId, eventType, result, reasonCode,
                protocolVersion, messageId, payloadDigest, remoteAddress, occurredAt, gatewayInstance);
    }

    public static JtGatewayAuditEvent record(
            UUID idempotencyKey,
            UUID terminalId,
            UUID vehicleId,
            EventType eventType,
            Result result,
            String reasonCode,
            String protocolVersion,
            Integer messageId,
            String payloadDigest,
            String remoteAddress,
            OffsetDateTime occurredAt,
            String gatewayInstance) {
        return new JtGatewayAuditEvent(
                idempotencyKey, terminalId, vehicleId, eventType, result, reasonCode, protocolVersion,
                messageId, payloadDigest, remoteAddress, occurredAt, gatewayInstance);
    }

    public UUID getId() { return id; }
    public UUID getIdempotencyKey() { return idempotencyKey; }
    public UUID getTerminalId() { return terminalId; }
    public UUID getVehicleId() { return vehicleId; }
    public EventType getEventType() { return eventType; }
    public Result getResult() { return result; }
    public String getReasonCode() { return reasonCode; }
    public String getProtocolVersion() { return protocolVersion; }
    public Integer getMessageId() { return messageId; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
    public String getGatewayInstance() { return gatewayInstance; }
}
