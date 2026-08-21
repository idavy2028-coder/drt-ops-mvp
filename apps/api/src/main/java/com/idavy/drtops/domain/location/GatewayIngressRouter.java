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
import java.util.HashSet;
import java.util.Set;
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
        Set<UUID> keys = new HashSet<>();
        for (GatewayIngressEnvelope envelope : batch) {
            if (envelope == null || envelope.idempotencyKey() == null
                    || !keys.add(envelope.idempotencyKey())) {
                throw new IllegalArgumentException("invalid ingress batch keys");
            }
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
        List<GpsLocationIngressService.Result> results = new ArrayList<>(batch.size());
        for (GatewayIngressEnvelope envelope : batch) {
            results.add(ingestOne(envelope));
        }
        return List.copyOf(results);
    }

    private GpsLocationIngressService.Result ingestOne(GatewayIngressEnvelope envelope) {
        if (envelope.schemaVersion() != 1) {
            return rejectForKind(envelope, "UNSUPPORTED_ENVELOPE");
        }
        if (envelope.gatewayReceivedAt() == null) {
            return rejectForKind(envelope, "INVALID_PAYLOAD");
        }
        return switch (envelope.kind() == null ? "" : envelope.kind()) {
            case "POSITION", "LOCATION" -> gpsService.ingest(List.of(envelope)).getFirst();
            case "ALARM" -> ingestAlarm(envelope);
            case "PROTOCOL_AUDIT" -> ingestAudit(envelope);
            case "ATTACHMENT_METADATA" -> gpsService.rejectStable(
                    envelope, "ATTACHMENT_METADATA_CONTRACT_UNAVAILABLE");
            default -> gpsService.reject(envelope, "UNSUPPORTED_ENVELOPE");
        };
    }

    private GpsLocationIngressService.Result ingestAlarm(GatewayIngressEnvelope envelope) {
        final VehicleAlarmIngressService.AlarmFact fact;
        try {
            fact = decodeAlarm(envelope);
        } catch (IllegalArgumentException malformed) {
            return gpsService.rejectStable(envelope, "INVALID_PAYLOAD");
        }
        VehicleAlarmIngressService.Result result = alarmService.ingest(envelope.idempotencyKey(), fact);
        return new GpsLocationIngressService.Result(
                result.idempotencyKey(), result.status(), result.reasonCodes());
    }

    private GpsLocationIngressService.Result ingestAudit(GatewayIngressEnvelope envelope) {
        final ProtocolAuditIngressService.ProtocolAuditFact fact;
        try {
            fact = decodeAudit(envelope);
        } catch (IllegalArgumentException malformed) {
            return gpsService.rejectStable(envelope, "INVALID_PAYLOAD");
        }
        ProtocolAuditIngressService.Result result = protocolAuditService.ingest(fact);
        return new GpsLocationIngressService.Result(
                result.idempotencyKey(), result.status(), result.reasonCodes());
    }

    private GpsLocationIngressService.Result rejectForKind(
            GatewayIngressEnvelope envelope, String reason) {
        return "ALARM".equals(envelope.kind()) || "PROTOCOL_AUDIT".equals(envelope.kind())
                || "ATTACHMENT_METADATA".equals(envelope.kind())
                ? gpsService.rejectStable(envelope, reason)
                : gpsService.reject(envelope, reason);
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
