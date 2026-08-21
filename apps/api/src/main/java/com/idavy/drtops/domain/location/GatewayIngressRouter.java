package com.idavy.drtops.domain.location;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.domain.alarm.VehicleAlarmIngressService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Separates gateway envelopes before any position mutation; malformed alarms cannot reach GPS ingestion. */
@Component
public class GatewayIngressRouter {
    private final Port port;
    private final GpsLocationIngressService gpsService;
    private final VehicleAlarmIngressService alarmService;
    private final ProtocolAuditIngressService protocolAuditService;
    private final ObjectMapper objectMapper;

    public GatewayIngressRouter(Port port) {
        this.port = Objects.requireNonNull(port, "port");
        this.gpsService = null;
        this.alarmService = null;
        this.protocolAuditService = null;
        this.objectMapper = null;
    }

    @Autowired
    public GatewayIngressRouter(
            GpsLocationIngressService gpsService,
            VehicleAlarmIngressService alarmService,
            ProtocolAuditIngressService protocolAuditService,
            ObjectMapper objectMapper) {
        this.port = null;
        this.gpsService = Objects.requireNonNull(gpsService, "gpsService");
        this.alarmService = Objects.requireNonNull(alarmService, "alarmService");
        this.protocolAuditService = Objects.requireNonNull(protocolAuditService, "protocolAuditService");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public List<GpsLocationIngressService.Result> ingest(List<GatewayIngressEnvelope> batch) {
        if (batch == null || batch.isEmpty() || batch.size() > 50) {
            throw new IllegalArgumentException("invalid ingress batch");
        }
        List<GatewayIngressEnvelope> positions = new ArrayList<>();
        List<GatewayIngressEnvelope> alarms = new ArrayList<>();
        List<GatewayIngressEnvelope> audits = new ArrayList<>();
        for (GatewayIngressEnvelope envelope : batch) {
            if (envelope != null && "ALARM".equals(envelope.kind())) {
                alarms.add(envelope);
            } else if (envelope != null && "PROTOCOL_AUDIT".equals(envelope.kind())) {
                audits.add(envelope);
            } else {
                // Keep Task 9's per-item rejection semantics for null and unsupported envelopes.
                positions.add(envelope);
            }
        }
        if (port != null) {
            port.alarms(List.copyOf(alarms));
            port.audits(List.copyOf(audits));
            port.positions(List.copyOf(positions));
            return List.of();
        }
        List<VehicleAlarmIngressService.AlarmFact> facts = alarms.stream().map(this::decodeAlarm).toList();
        List<ProtocolAuditIngressService.ProtocolAuditFact> auditFacts = audits.stream()
                .map(this::decodeAudit).toList();
        List<GpsLocationIngressService.Result> positionResults = positions.isEmpty()
                ? List.of()
                : gpsService.ingest(Collections.unmodifiableList(new ArrayList<>(positions)));
        if (!facts.isEmpty()) {
            alarmService.ingest(facts);
        }
        if (!auditFacts.isEmpty()) {
            protocolAuditService.ingest(auditFacts);
        }
        return positionResults;
    }

    private ProtocolAuditIngressService.ProtocolAuditFact decodeAudit(GatewayIngressEnvelope envelope) {
        if (envelope.schemaVersion() != 1 || envelope.idempotencyKey() == null || envelope.gatewayReceivedAt() == null) {
            throw new IllegalArgumentException("invalid protocol audit envelope");
        }
        try {
            ProtocolAuditPayload payload = objectMapper.readValue(envelope.payloadJson(), ProtocolAuditPayload.class);
            return new ProtocolAuditIngressService.ProtocolAuditFact(
                    envelope.idempotencyKey(), payload.terminalId(), payload.vehicleId(), payload.reasonCode(),
                    payload.protocolVersion(), payload.messageId(), payload.payloadDigest(), envelope.gatewayReceivedAt());
        } catch (Exception malformed) {
            throw new IllegalArgumentException("invalid protocol audit payload", malformed);
        }
    }

    private VehicleAlarmIngressService.AlarmFact decodeAlarm(GatewayIngressEnvelope envelope) {
        if (envelope.schemaVersion() != 1 || envelope.idempotencyKey() == null || envelope.gatewayReceivedAt() == null) {
            throw new IllegalArgumentException("invalid alarm envelope");
        }
        try {
            AlarmPayload payload = objectMapper.readValue(envelope.payloadJson(), AlarmPayload.class);
            return new VehicleAlarmIngressService.AlarmFact(payload.terminalId(), payload.vehicleId(), payload.standard(),
                    payload.module(), payload.typeCode(), payload.alarmType(), payload.terminalAlarmId(),
                    payload.state(), payload.level(),
                    payload.terminalAlarmIdentifier(), payload.occurredAt(), envelope.gatewayReceivedAt(),
                    payload.longitude(), payload.latitude(), payload.speedKph(), payload.positionIdempotencyKey(),
                    payload.locationQualityStatus(), payload.extensionPayloadDigest());
        } catch (Exception malformed) {
            throw new IllegalArgumentException("invalid alarm payload", malformed);
        }
    }

    public interface Port {
        void alarms(List<GatewayIngressEnvelope> batch);
        void audits(List<GatewayIngressEnvelope> batch);
        void positions(List<GatewayIngressEnvelope> batch);
    }

    private record AlarmPayload(
            UUID terminalId, UUID vehicleId, String standard, String module, long terminalAlarmId,
            int typeCode, String alarmType,
            String state, int level, String terminalAlarmIdentifier, Instant occurredAt, Instant gatewayReceivedAt,
            BigDecimal longitude, BigDecimal latitude, BigDecimal speedKph, int vehicleStatus,
            int alarmSequenceNumber, int attachmentCount, UUID positionIdempotencyKey, String locationQualityStatus,
            String extensionPayloadDigest) { }

    private record ProtocolAuditPayload(
            UUID terminalId,
            UUID vehicleId,
            String reasonCode,
            String protocolVersion,
            int messageId,
            String payloadDigest) { }
}
