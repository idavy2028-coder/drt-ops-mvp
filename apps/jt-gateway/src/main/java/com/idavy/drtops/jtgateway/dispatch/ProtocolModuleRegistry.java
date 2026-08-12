package com.idavy.drtops.jtgateway.dispatch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.jt.protocol.codec.Jt808Frame;
import com.idavy.drtops.jt.protocol.core.LocationReport;
import com.idavy.drtops.jt.protocol.core.LocationReportCodec;
import com.idavy.drtops.jtgateway.ingress.CanonicalPositionIngress;
import com.idavy.drtops.jtgateway.ingress.GatewayIngressBuffer;
import com.idavy.drtops.jtgateway.ingress.GatewayIngressEnvelope;
import com.idavy.drtops.jtgateway.ingress.IngressKind;
import com.idavy.drtops.jtgateway.ingress.PositionIngressBuffer;
import com.idavy.drtops.jtgateway.session.TerminalSession;
import com.idavy.drtops.jtgateway.session.TerminalSessionState;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Routes authenticated JT/T 808 frames without letting unknown messages enter the position domain. */
public final class ProtocolModuleRegistry {
    private static final int LOCATION_REPORT_MESSAGE_ID = 0x0200;
    private final LocationReportCodec locationCodec;
    private final PositionIngressBuffer positionBuffer;
    private final Clock clock;
    private final AtomicLong unknownMessageCount = new AtomicLong();

    public ProtocolModuleRegistry(
            LocationReportCodec locationCodec, PositionIngressBuffer positionBuffer, Clock clock) {
        this.locationCodec = Objects.requireNonNull(locationCodec, "locationCodec");
        this.positionBuffer = Objects.requireNonNull(positionBuffer, "positionBuffer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ProtocolModuleRegistry(
            LocationReportCodec locationCodec,
            GatewayIngressBuffer gatewayBuffer,
            ObjectMapper objectMapper,
            Clock clock) {
        this(locationCodec, position -> appendToGatewayBuffer(position, gatewayBuffer, objectMapper), clock);
    }

    public DispatchResult dispatch(TerminalSession session, Jt808Frame frame) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(frame, "frame");
        try {
            if (frame.header().messageId() != LOCATION_REPORT_MESSAGE_ID) {
                unknownMessageCount.incrementAndGet();
                return DispatchResult.MAY_ACKNOWLEDGE_SUCCESS;
            }
            if (session.state() != TerminalSessionState.AUTHENTICATED
                    || session.terminalId() == null
                    || session.vehicleId() == null
                    || session.sourceCoordinateSystem() == null
                    || !session.matchesTerminalIdentity(frame.header().terminalIdentity())) {
                return DispatchResult.REJECTED;
            }
            LocationReport report = locationCodec.decode(frame.header(), frame.body());
            Instant gatewayReceivedAt = clock.instant();
            CanonicalPositionIngress ingress = new CanonicalPositionIngress(
                    session.terminalId(),
                    session.vehicleId(),
                    frame.header().protocolVersion().name(),
                    frame.header().serialNumber(),
                    report.longitude(),
                    report.latitude(),
                    session.sourceCoordinateSystem(),
                    report.locatedAt(),
                    gatewayReceivedAt,
                    report.alarmBits(),
                    report.statusBits(),
                    report.speedKph(),
                    report.directionDegrees(),
                    report.altitudeMeters(),
                    report.satelliteCount(),
                    digest(frame));
            GatewayIngressBuffer.WriteResult result = positionBuffer.append(ingress);
            return result == GatewayIngressBuffer.WriteResult.STORED
                            || result == GatewayIngressBuffer.WriteResult.DUPLICATE
                    ? DispatchResult.MAY_ACKNOWLEDGE_SUCCESS
                    : DispatchResult.REJECTED;
        } finally {
            frame.body().release();
        }
    }

    public long unknownMessageCount() {
        return unknownMessageCount.get();
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

    private static String digest(Jt808Frame frame) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] body = new byte[frame.body().readableBytes()];
            frame.body().duplicate().readBytes(body);
            digest.update(body);
            return HexFormat.of().formatHex(digest.digest());
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
