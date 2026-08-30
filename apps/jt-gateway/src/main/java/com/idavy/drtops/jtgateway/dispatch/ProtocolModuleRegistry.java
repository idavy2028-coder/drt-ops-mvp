package com.idavy.drtops.jtgateway.dispatch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.jt.protocol.codec.Jt808Frame;
import com.idavy.drtops.jt.protocol.core.LocationReport;
import com.idavy.drtops.jt.protocol.core.Jt808CoreModule;
import com.idavy.drtops.jt.protocol.jt1078.AlarmAttachmentMessageCodec;
import com.idavy.drtops.jt.protocol.jt1078.Jt1078ControlModule;
import com.idavy.drtops.jtgateway.ingress.CanonicalAttachmentMetadata;
import com.idavy.drtops.jtgateway.ingress.CanonicalPositionIngress;
import com.idavy.drtops.jtgateway.ingress.CanonicalVehicleAlarm;
import com.idavy.drtops.jtgateway.ingress.CanonicalProtocolAudit;
import com.idavy.drtops.jtgateway.ingress.ActiveSafetyAlarmRouter;
import com.idavy.drtops.jtgateway.ingress.GatewayIngressBuffer;
import com.idavy.drtops.jtgateway.ingress.GatewayIngressEnvelope;
import com.idavy.drtops.jtgateway.ingress.IngressKind;
import com.idavy.drtops.jtgateway.ingress.PositionIngressBuffer;
import com.idavy.drtops.jtgateway.session.TerminalSession;
import com.idavy.drtops.jtgateway.session.TerminalSessionRegistry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Routes authenticated JT/T 808 frames without letting unknown messages enter the position domain. */
public final class ProtocolModuleRegistry {
    private static final int LOCATION_REPORT_MESSAGE_ID = 0x0200;
    private static final String ATTACHMENT_SIGNALING_STANDARD = "T/JSATL12-2017";
    private static final java.util.Set<Integer> CONSUMED_MESSAGE_IDS = java.util.Set.of(0x0100, 0x0102, 0x0002);
    private final Jt808CoreModule coreModule;
    private final PositionIngressBuffer positionBuffer;
    private final TerminalSessionRegistry sessionRegistry;
    private final Clock clock;
    private final GatewayIngressBuffer gatewayBuffer;
    private final ObjectMapper objectMapper;
    private final ActiveSafetyAlarmRouter activeSafetyAlarmRouter = new ActiveSafetyAlarmRouter();
    private final Jt1078ControlModule attachmentControlModule = new Jt1078ControlModule();
    private final AtomicLong unknownMessageCount = new AtomicLong();

    public ProtocolModuleRegistry(
            Jt808CoreModule coreModule,
            PositionIngressBuffer positionBuffer,
            TerminalSessionRegistry sessionRegistry,
            Clock clock) {
        this.coreModule = Objects.requireNonNull(coreModule, "coreModule");
        this.positionBuffer = Objects.requireNonNull(positionBuffer, "positionBuffer");
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.gatewayBuffer = null;
        this.objectMapper = null;
    }

    public ProtocolModuleRegistry(
            Jt808CoreModule coreModule,
            GatewayIngressBuffer gatewayBuffer,
            ObjectMapper objectMapper,
            TerminalSessionRegistry sessionRegistry,
            Clock clock) {
        this.coreModule = Objects.requireNonNull(coreModule, "coreModule");
        this.gatewayBuffer = Objects.requireNonNull(gatewayBuffer, "gatewayBuffer");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.positionBuffer = position -> appendToGatewayBuffer(position, this.gatewayBuffer, this.objectMapper);
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DispatchResult dispatch(TerminalSession session, Jt808Frame frame) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(frame, "frame");
        try {
            return sessionRegistry.executeIfCurrent(session, () -> dispatchCurrent(session, frame))
                    .orElse(DispatchResult.REJECTED);
        } finally {
            frame.body().release();
        }
    }

    private DispatchResult dispatchCurrent(TerminalSession session, Jt808Frame frame) {
        if (session.vehicleId() == null
                || session.sourceCoordinateSystem() == null
                || !session.matchesTerminalIdentity(frame.header().terminalIdentity())
                || frame.header().encryptionType() != 0) {
            return DispatchResult.REJECTED;
        }
        int messageId = frame.header().messageId();
        if (CONSUMED_MESSAGE_IDS.contains(messageId)) {
            return DispatchResult.REJECTED;
        }
        if (messageId == Jt1078ControlModule.MSG_ALARM_ATTACHMENT_INFO
                || messageId == Jt1078ControlModule.MSG_FILE_UPLOAD_COMPLETE_NOTIFICATION) {
            return dispatchAttachmentMetadata(session, frame);
        }
        if (messageId != LOCATION_REPORT_MESSAGE_ID) {
            unknownMessageCount.incrementAndGet();
            return DispatchResult.MAY_ACKNOWLEDGE_SUCCESS;
        }
        String sourceRole = locationSourceRole(session);
        if (session.onboardSystemId() == null || sourceRole == null) {
            return DispatchResult.REJECTED;
        }
            LocationReport report = coreModule.decodeLocation(frame.header(), frame.body());
            Instant gatewayReceivedAt = clock.instant();
            CanonicalPositionIngress ingress = new CanonicalPositionIngress(
                    session.terminalId(),
                    session.onboardSystemId(),
                    session.vehicleId(),
                    sourceRole,
                    frame.header().protocolVersion().name(),
                    frame.header().serialNumber(),
                    report.longitude(),
                    report.latitude(),
                    session.sourceCoordinateSystem(),
                    report.locatedAt(),
                    gatewayReceivedAt,
                    Integer.toUnsignedLong(report.alarmBits()),
                    Integer.toUnsignedLong(report.statusBits()),
                    report.speedKph(),
                    report.directionDegrees(),
                    report.altitudeMeters(),
                    report.satelliteCount(),
                    digest(frame));
            GatewayIngressBuffer.WriteResult result = positionBuffer.append(ingress);
        if (result != GatewayIngressBuffer.WriteResult.STORED && result != GatewayIngressBuffer.WriteResult.DUPLICATE) {
            return DispatchResult.REJECTED;
        }
        if (hasActiveSafetyItem(report) && !session.roles().contains("ACTIVE_SAFETY")) {
            appendProtocolAudit(
                    session, frame, gatewayReceivedAt, "DEVICE_ROLE_VIOLATION");
            return DispatchResult.MAY_ACKNOWLEDGE_SUCCESS;
        }
        return appendActiveSafetyAlarms(session, report, ingress, gatewayReceivedAt)
                ? DispatchResult.MAY_ACKNOWLEDGE_SUCCESS : DispatchResult.REJECTED;
    }

    private DispatchResult dispatchAttachmentMetadata(TerminalSession session, Jt808Frame frame) {
        Instant receivedAt = clock.instant();
        if (gatewayBuffer == null) {
            return DispatchResult.REJECTED;
        }
        if (!session.roles().contains("VIDEO")) {
            return appendProtocolAudit(
                    session, frame, receivedAt, "DEVICE_ROLE_VIOLATION");
        }
        if (!ATTACHMENT_SIGNALING_STANDARD.equals(session.activeSafetyStandard())
                || session.activeSafetyModules().isEmpty()) {
            return appendProtocolAudit(
                    session, frame, receivedAt, "ACTIVE_SAFETY_ATTACHMENT_NOT_CAPABLE");
        }
        try {
            Object decoded = attachmentControlModule.decode(
                    frame.header().messageId(), frame.body().duplicate());
            CanonicalAttachmentMetadata metadata = toAttachmentMetadata(
                    session, frame, receivedAt, decoded);
            UUID key = UUID.nameUUIDFromBytes(String.join("|",
                    session.terminalId().toString(),
                    Integer.toString(frame.header().messageId()),
                    Integer.toString(frame.header().serialNumber()),
                    metadata.payloadDigest()).getBytes(StandardCharsets.UTF_8));
            GatewayIngressBuffer.WriteResult result = gatewayBuffer.append(new GatewayIngressEnvelope(
                    1,
                    key,
                    IngressKind.ATTACHMENT_METADATA,
                    receivedAt,
                    objectMapper.writeValueAsString(metadata)));
            return durable(result);
        } catch (RuntimeException | JsonProcessingException malformed) {
            return appendProtocolAudit(
                    session, frame, receivedAt, "ACTIVE_SAFETY_ATTACHMENT_METADATA_REJECTED");
        }
    }

    private CanonicalAttachmentMetadata toAttachmentMetadata(
            TerminalSession session, Jt808Frame frame, Instant receivedAt, Object decoded) {
        String payloadDigest = digest(frame);
        if (decoded instanceof AlarmAttachmentMessageCodec.AlarmAttachmentInfo info) {
            List<CanonicalAttachmentMetadata.AttachmentFile> files = info.attachments().stream()
                    .map(file -> new CanonicalAttachmentMetadata.AttachmentFile(
                            file.fileName(), file.fileSize()))
                    .toList();
            return new CanonicalAttachmentMetadata(
                    session.terminalId(), session.vehicleId(), frame.header().messageId(), receivedAt,
                    digest(info.alarmIdentifier()), digest(info.alarmNumber()), info.infoType(),
                    null, null, files, payloadDigest);
        }
        if (decoded instanceof AlarmAttachmentMessageCodec.FileUploadCompleteNotification complete) {
            return new CanonicalAttachmentMetadata(
                    session.terminalId(), session.vehicleId(), frame.header().messageId(), receivedAt,
                    null, null, null, complete.responseSerialNo(), complete.result(), List.of(), payloadDigest);
        }
        throw new IllegalArgumentException("unsupported attachment metadata message");
    }

    private DispatchResult appendProtocolAudit(
            TerminalSession session, Jt808Frame frame, Instant receivedAt, String reasonCode) {
        if (gatewayBuffer == null || objectMapper == null) {
            return DispatchResult.REJECTED;
        }
        try {
            CanonicalProtocolAudit audit = new CanonicalProtocolAudit(
                    session.terminalId(), session.vehicleId(), reasonCode,
                    frame.header().protocolVersion().name(), frame.header().messageId(), digest(frame));
            UUID key = UUID.nameUUIDFromBytes(String.join("|",
                    session.terminalId().toString(),
                    Integer.toString(frame.header().messageId()),
                    Integer.toString(frame.header().serialNumber()),
                    reasonCode,
                    audit.payloadDigest()).getBytes(StandardCharsets.UTF_8));
            GatewayIngressBuffer.WriteResult result = gatewayBuffer.append(new GatewayIngressEnvelope(
                    1, key, IngressKind.PROTOCOL_AUDIT, receivedAt,
                    objectMapper.writeValueAsString(audit)));
            return durable(result);
        } catch (JsonProcessingException | RuntimeException auditFailure) {
            return DispatchResult.REJECTED;
        }
    }

    private static DispatchResult durable(GatewayIngressBuffer.WriteResult result) {
        return result == GatewayIngressBuffer.WriteResult.STORED
                        || result == GatewayIngressBuffer.WriteResult.DUPLICATE
                ? DispatchResult.MAY_ACKNOWLEDGE_SUCCESS
                : DispatchResult.REJECTED;
    }

    private static String locationSourceRole(TerminalSession session) {
        boolean primary = session.roles().contains("LOCATION_PRIMARY");
        boolean backup = session.roles().contains("LOCATION_BACKUP");
        if (primary == backup) {
            return null;
        }
        return primary ? "LOCATION_PRIMARY" : "LOCATION_BACKUP";
    }

    private static boolean hasActiveSafetyItem(LocationReport report) {
        return report.additionalItems().stream()
                .anyMatch(item -> item.id() == 0x64 || item.id() == 0x65);
    }

    public long unknownMessageCount() {
        return unknownMessageCount.get();
    }

    private boolean appendActiveSafetyAlarms(
            TerminalSession session, LocationReport report, CanonicalPositionIngress position, Instant gatewayReceivedAt) {
        if (gatewayBuffer == null) {
            return true;
        }
        ActiveSafetyAlarmRouter.Result decoded = activeSafetyAlarmRouter.route(
                session, report, gatewayReceivedAt, idempotencyKeyFor(position));
        for (int index = 0; index < decoded.rejections().size(); index++) {
            ActiveSafetyAlarmRouter.Rejection rejection = decoded.rejections().get(index);
            CanonicalProtocolAudit audit = new CanonicalProtocolAudit(
                    session.terminalId(), session.vehicleId(), rejection.reasonCode(),
                    position.protocolVersion(), LOCATION_REPORT_MESSAGE_ID, position.payloadDigest());
            try {
                GatewayIngressBuffer.WriteResult result = gatewayBuffer.append(new GatewayIngressEnvelope(
                        1, idempotencyKeyFor(audit, position, index), IngressKind.PROTOCOL_AUDIT,
                        gatewayReceivedAt, objectMapper.writeValueAsString(audit)));
                if (result != GatewayIngressBuffer.WriteResult.STORED
                        && result != GatewayIngressBuffer.WriteResult.DUPLICATE) {
                    return false;
                }
            } catch (JsonProcessingException serializationFailure) {
                return false;
            }
        }
        for (CanonicalVehicleAlarm alarm : decoded.alarms()) {
            try {
                GatewayIngressBuffer.WriteResult result = gatewayBuffer.append(new GatewayIngressEnvelope(
                        1, idempotencyKeyFor(alarm, position), IngressKind.ALARM, gatewayReceivedAt,
                        objectMapper.writeValueAsString(alarm)), idempotencyKeyFor(position));
                if (result != GatewayIngressBuffer.WriteResult.STORED && result != GatewayIngressBuffer.WriteResult.DUPLICATE) {
                    return false;
                }
            } catch (JsonProcessingException serializationFailure) {
                return false;
            }
        }
        return true;
    }

    private static GatewayIngressBuffer.WriteResult appendToGatewayBuffer(
            CanonicalPositionIngress position,
            GatewayIngressBuffer gatewayBuffer,
            ObjectMapper objectMapper) {
        Objects.requireNonNull(gatewayBuffer, "gatewayBuffer");
        Objects.requireNonNull(objectMapper, "objectMapper");
        UUID idempotencyKey = idempotencyKeyFor(position);
        try {
            return gatewayBuffer.append(new GatewayIngressEnvelope(
                    1,
                    idempotencyKey,
                    IngressKind.LOCATION,
                    position.gatewayReceivedAt(),
                    objectMapper.writeValueAsString(position)));
        } catch (JsonProcessingException serializationFailure) {
            return GatewayIngressBuffer.WriteResult.UNAVAILABLE;
        }
    }

    public static UUID idempotencyKeyFor(CanonicalPositionIngress position) {
        return idempotencyKeyFor(position, LOCATION_REPORT_MESSAGE_ID);
    }

    public static UUID idempotencyKeyFor(CanonicalPositionIngress position, int messageId) {
        String material = String.join("|",
                position.terminalId().toString(),
                position.protocolVersion(),
                Integer.toString(messageId),
                Integer.toString(position.messageSerialNo()),
                position.terminalLocatedAt().toString(),
                position.payloadDigest());
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
    }

    public static UUID idempotencyKeyFor(CanonicalVehicleAlarm alarm, CanonicalPositionIngress position) {
        String material = String.join("|", alarm.terminalId().toString(), alarm.standard(), alarm.module(),
                Long.toUnsignedString(alarm.terminalAlarmId()), Integer.toString(alarm.typeCode()),
                alarm.terminalAlarmIdentifier(),
                alarm.occurredAt().toString(), alarm.extensionPayloadDigest());
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
    }

    static UUID idempotencyKeyFor(CanonicalProtocolAudit audit, CanonicalPositionIngress position, int ordinal) {
        String material = String.join("|", idempotencyKeyFor(position).toString(), audit.reasonCode(),
                Integer.toString(audit.messageId()), Integer.toString(ordinal));
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
    }

    private static String digest(Jt808Frame frame) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] body = new byte[frame.body().readableBytes()];
            frame.body().duplicate().readBytes(body);
            return HexFormat.of().formatHex(digest.digest(body));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String digest(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public enum DispatchResult {
        MAY_ACKNOWLEDGE_SUCCESS(true),
        REJECTED(false);

        private final boolean mayAcknowledgeSuccess;

        DispatchResult(boolean mayAcknowledgeSuccess) {
            this.mayAcknowledgeSuccess = mayAcknowledgeSuccess;
        }

        public boolean mayAcknowledgeSuccess() {
            return mayAcknowledgeSuccess;
        }
    }
}
