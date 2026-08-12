package com.idavy.drtops.jtgateway.session;

import com.idavy.drtops.jt.protocol.codec.Jt808Frame;
import com.idavy.drtops.jt.protocol.codec.Jt808MessageHeader;
import com.idavy.drtops.jt.protocol.codec.ProtocolVersion;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class RegistrationAuthenticationHandler extends ChannelInboundHandlerAdapter {
    private static final int REGISTRATION_MESSAGE_ID = 0x0100;
    private static final int AUTHENTICATION_MESSAGE_ID = 0x0102;
    private static final int HEARTBEAT_MESSAGE_ID = 0x0002;
    private static final AtomicInteger PLATFORM_SERIAL = new AtomicInteger();

    private final TerminalRegistryPort registryPort;
    private final TerminalSessionRegistry sessionRegistry;
    private final Clock clock;
    private final Duration authenticationWindow;
    private TerminalSession session;
    private String observedTerminalAlias = "unknown";

    public RegistrationAuthenticationHandler(
            TerminalRegistryPort registryPort,
            TerminalSessionRegistry sessionRegistry,
            Clock clock,
            Duration authenticationWindow) {
        this.registryPort = java.util.Objects.requireNonNull(registryPort, "registryPort");
        this.sessionRegistry = java.util.Objects.requireNonNull(sessionRegistry, "sessionRegistry");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.authenticationWindow = java.util.Objects.requireNonNull(authenticationWindow, "authenticationWindow");
    }

    @Override
    public void handlerAdded(ChannelHandlerContext context) {
        session = new TerminalSession(context.channel(), clock.instant());
    }

    @Override
    public void channelActive(ChannelHandlerContext context) throws Exception {
        context.executor().schedule(
                () -> closeForAuthenticationTimeout(context),
                authenticationWindow.toMillis(),
                TimeUnit.MILLISECONDS);
        super.channelActive(context);
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        if (!(message instanceof Jt808Frame frame)) {
            context.fireChannelRead(message);
            return;
        }
        String terminalIdentity = frame.header().terminalIdentity();
        observedTerminalAlias = maskedAlias(terminalIdentity);
        if (session.terminalId() != null && !session.matchesTerminalIdentity(terminalIdentity)) {
            release(frame);
            audit(context, SessionAuditType.SESSION_IDENTITY_MISMATCH,
                    "TERMINAL_IDENTITY_CHANGED_WITHIN_SESSION");
            session.close();
            return;
        }
        if (session.state() == TerminalSessionState.CONNECTED_UNAUTHENTICATED
                && clock.instant().isAfter(session.connectedAt().plus(authenticationWindow))) {
            release(frame);
            audit(context, SessionAuditType.AUTHENTICATION_TIMEOUT, "HANDSHAKE_DEADLINE_EXCEEDED");
            session.close();
            return;
        }
        if (session.state() == TerminalSessionState.CONNECTED_UNAUTHENTICATED) {
            handleUnauthenticated(context, frame);
            return;
        }
        if (session.state() == TerminalSessionState.CLOSED) {
            release(frame);
            return;
        }
        handleAuthenticated(context, frame);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext context, Object event) throws Exception {
        if (event instanceof IdleStateEvent idle && idle.state() == IdleState.READER_IDLE) {
            audit(context, SessionAuditType.SESSION_OFFLINE, "READER_IDLE_180_SECONDS");
            sessionRegistry.remove(session);
            session.close();
            return;
        }
        super.userEventTriggered(context, event);
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        releaseSession();
        super.channelInactive(context);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext context) {
        releaseSession();
    }

    public TerminalSession session() {
        return session;
    }

    private void handleUnauthenticated(ChannelHandlerContext context, Jt808Frame frame) {
        if (frame.header().messageId() == REGISTRATION_MESSAGE_ID) {
            handleRegistration(context, frame);
        } else if (frame.header().messageId() == AUTHENTICATION_MESSAGE_ID) {
            handleAuthentication(context, frame);
        } else {
            release(frame);
            audit(context, SessionAuditType.PRE_AUTH_MESSAGE_REJECTED,
                    "MESSAGE_NOT_ALLOWED_BEFORE_AUTHENTICATION");
            session.close();
        }
    }

    private void handleRegistration(ChannelHandlerContext context, Jt808Frame frame) {
        RegistrationDecision decision;
        try {
            decision = registryPort.verifyRegistration(parseRegistration(frame));
        } catch (IllegalArgumentException exception) {
            decision = RegistrationDecision.rejected(RegistrationRejection.MALFORMED_REGISTRATION);
        }
        if (!decision.approved()) {
            writeRegistrationReply(context, frame.header(), decision.rejection().protocolResult(), null);
            release(frame);
            audit(context, SessionAuditType.REGISTRATION_REJECTED, decision.rejection().name());
            session.close();
            return;
        }

        session.registrationAccepted(
                decision.terminalId(), decision.vehicleId(), decision.sourceCoordinateSystem(), decision.tokenVersion(),
                frame.header().terminalIdentity());
        writeRegistrationReply(context, frame.header(), 0, decision.authenticationToken());
        release(frame);
        audit(context, SessionAuditType.REGISTRATION_ACCEPTED, "APPROVED");
    }

    private void handleAuthentication(ChannelHandlerContext context, Jt808Frame frame) {
        AuthenticationDecision decision;
        if (session.terminalId() == null) {
            decision = AuthenticationDecision.rejected(AuthenticationRejection.REGISTRATION_REQUIRED);
        } else {
            byte[] token = parseAuthenticationToken(frame);
            try {
                decision = registryPort.verifyAuthentication(
                        session.terminalId(), session.tokenVersion(), sha256(token));
            } finally {
                java.util.Arrays.fill(token, (byte) 0);
            }
        }

        if (!decision.approved()) {
            int failures = session.recordAuthenticationFailure();
            writeGeneralReply(context, frame.header(), 1);
            release(frame);
            audit(context, SessionAuditType.AUTHENTICATION_REJECTED, decision.rejection().name());
            if (failures >= 3) {
                audit(context, SessionAuditType.AUTHENTICATION_LOCKED, "THREE_CONSECUTIVE_FAILURES");
                session.close();
            }
            return;
        }

        session.authenticated(clock.instant());
        writeGeneralReply(context, frame.header(), 0);
        release(frame);
        sessionRegistry.claim(session).ifPresent(previous ->
                audit(context, SessionAuditType.SESSION_TAKEN_OVER,
                        "PREVIOUS_CONNECTION_REPLACED"));
        audit(context, SessionAuditType.AUTHENTICATION_ACCEPTED, "APPROVED");
    }

    private void handleAuthenticated(ChannelHandlerContext context, Jt808Frame frame) {
        session.touch(clock.instant());
        if (frame.header().messageId() == HEARTBEAT_MESSAGE_ID) {
            writeGeneralReply(context, frame.header(), 0);
            release(frame);
            return;
        }
        context.fireChannelRead(frame);
    }

    private void closeForAuthenticationTimeout(ChannelHandlerContext context) {
        if (session != null && session.state() == TerminalSessionState.CONNECTED_UNAUTHENTICATED) {
            audit(context, SessionAuditType.AUTHENTICATION_TIMEOUT, "HANDSHAKE_DEADLINE_EXCEEDED");
            session.close();
        }
    }

    private void releaseSession() {
        if (session != null) {
            sessionRegistry.remove(session);
            session.markClosed();
        }
    }

    private TerminalRegistrationIdentity parseRegistration(Jt808Frame frame) {
        ByteBuf body = frame.body().duplicate();
        boolean version2019 = frame.header().protocolVersion() == ProtocolVersion.JT808_2019;
        int manufacturerLength = version2019 ? 11 : 5;
        int modelLength = version2019 ? 30 : 20;
        int terminalCodeLength = version2019 ? 30 : 7;
        int minimum = 4 + manufacturerLength + modelLength + terminalCodeLength + 1;
        if (body.readableBytes() < minimum) {
            throw new IllegalArgumentException("registration body is too short");
        }
        body.skipBytes(4);
        String manufacturer = readFixed(body, manufacturerLength, StandardCharsets.US_ASCII);
        String model = readFixed(body, modelLength, StandardCharsets.US_ASCII);
        String terminalCode = readFixed(body, terminalCodeLength, StandardCharsets.US_ASCII);
        body.skipBytes(1);
        String vehicleIdentifier = readFixed(body, body.readableBytes(), Charset.forName("GBK"));
        return new TerminalRegistrationIdentity(
                frame.header().protocolVersion(), frame.header().terminalIdentity(), manufacturer,
                model, terminalCode, vehicleIdentifier);
    }

    private byte[] parseAuthenticationToken(Jt808Frame frame) {
        ByteBuf body = frame.body().duplicate();
        if (frame.header().protocolVersion() == ProtocolVersion.JT808_2019) {
            if (!body.isReadable()) {
                return new byte[0];
            }
            int tokenLength = body.readUnsignedByte();
            if (tokenLength > body.readableBytes()) {
                return new byte[0];
            }
            byte[] token = new byte[tokenLength];
            body.readBytes(token);
            return token;
        }
        byte[] token = new byte[body.readableBytes()];
        body.readBytes(token);
        return token;
    }

    private void writeRegistrationReply(
            ChannelHandlerContext context, Jt808MessageHeader request, int result, byte[] token) {
        ByteBuf body = Unpooled.buffer();
        body.writeShort(request.serialNumber()).writeByte(result);
        if (result == 0 && token != null) {
            body.writeBytes(token);
        }
        context.writeAndFlush(responseFrame(0x8100, request, body));
    }

    private void writeGeneralReply(ChannelHandlerContext context, Jt808MessageHeader request, int result) {
        ByteBuf body = Unpooled.buffer(5);
        body.writeShort(request.serialNumber()).writeShort(request.messageId()).writeByte(result);
        context.writeAndFlush(responseFrame(0x8001, request, body));
    }

    private Jt808Frame responseFrame(int messageId, Jt808MessageHeader request, ByteBuf body) {
        int properties = body.readableBytes();
        if (request.protocolVersion() == ProtocolVersion.JT808_2019) {
            properties |= 0x4000;
        }
        Jt808MessageHeader header = new Jt808MessageHeader(
                messageId, properties, body.readableBytes(), 0, false,
                request.protocolVersion(), request.protocolVersionByte(), request.terminalIdentity(),
                PLATFORM_SERIAL.updateAndGet(current -> current == 0xffff ? 1 : current + 1), null, null);
        return new Jt808Frame(header, body, (byte) 0);
    }

    private void audit(ChannelHandlerContext context, SessionAuditType type, String reason) {
        UUID terminalId = session == null ? null : session.terminalId();
        String terminalAlias = terminalId == null
                ? observedTerminalAlias
                : session.terminalAlias();
        registryPort.recordSessionAudit(new SessionAuditIngress(
                type,
                terminalId,
                terminalAlias,
                remoteAddress(context),
                reason,
                clock.instant()));
    }

    private static String readFixed(ByteBuf body, int length, Charset charset) {
        byte[] value = new byte[length];
        body.readBytes(value);
        int end = 0;
        while (end < value.length && value[end] != 0) {
            end++;
        }
        return new String(value, 0, end, charset).trim();
    }

    private static String maskedAlias(String terminalIdentity) {
        if (terminalIdentity == null || terminalIdentity.isBlank()) {
            return "unknown";
        }
        int visible = Math.min(4, terminalIdentity.length());
        return "****" + terminalIdentity.substring(terminalIdentity.length() - visible);
    }

    private static String remoteAddress(ChannelHandlerContext context) {
        return context.channel().remoteAddress() == null
                ? "unknown"
                : context.channel().remoteAddress().toString();
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void release(Jt808Frame frame) {
        frame.body().release();
    }
}
