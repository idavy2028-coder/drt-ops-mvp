package com.idavy.drtops.jtgateway.session;

import com.idavy.drtops.jt.protocol.codec.Jt808Frame;
import com.idavy.drtops.jt.protocol.codec.Jt808MessageHeader;
import com.idavy.drtops.jt.protocol.codec.ProtocolVersion;
import com.idavy.drtops.jtgateway.netty.JtFrameOwnershipHandler;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RegistrationAuthenticationHandlerTest {
    private static final UUID TERMINAL_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String TERMINAL_NUMBER = "123456789012";
    private static final String AUTHENTICATION_TOKEN = "PILOT-TEST-TOKEN";

    @Test
    void acceptsOnlyRegistrationOrAuthenticationDuringThirtySecondWindow() {
        MutableClock clock = new MutableClock();
        FakeTerminalRegistry port = FakeTerminalRegistry.approved();
        TerminalSessionRegistry sessions = new TerminalSessionRegistry();
        EmbeddedChannel channel = channel(port, sessions, clock);

        assertFalse(channel.writeInbound(frame(0x0200, new byte[]{1})));
        assertFalse(channel.isActive());
        assertEquals(SessionAuditType.PRE_AUTH_MESSAGE_REJECTED, port.lastAudit().type());
        assertFalse(port.lastAudit().terminalAlias().contains(TERMINAL_NUMBER));
        channel.finishAndReleaseAll();

        EmbeddedChannel expired = channel(port, sessions, clock);
        clock.advance(Duration.ofSeconds(31));
        assertFalse(expired.writeInbound(authenticationFrame(AUTHENTICATION_TOKEN)));
        assertFalse(expired.isActive());
        assertEquals(SessionAuditType.AUTHENTICATION_TIMEOUT, port.lastAudit().type());
        expired.finishAndReleaseAll();
    }

    @Test
    void rejectsUnknownSuspendedAndInvalidBindingRegistrations() {
        for (RegistrationRejection rejection : List.of(
                RegistrationRejection.NOT_PREPROVISIONED,
                RegistrationRejection.TERMINAL_SUSPENDED,
                RegistrationRejection.BINDING_INACTIVE)) {
            FakeTerminalRegistry port = FakeTerminalRegistry.rejected(rejection);
            EmbeddedChannel channel = channel(port, new TerminalSessionRegistry(), new MutableClock());

            assertFalse(channel.writeInbound(registrationFrame()));
            assertFalse(channel.isActive());
            assertEquals(rejection.name(), port.lastAudit().reasonCode());
            drainOutbound(channel);
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void registersAuthenticatesRefreshesHeartbeatAndRejectsUnauthenticatedLocation() {
        MutableClock clock = new MutableClock();
        FakeTerminalRegistry port = FakeTerminalRegistry.approved();
        TerminalSessionRegistry sessions = new TerminalSessionRegistry();
        RegistrationAuthenticationHandler handler = handler(port, sessions, clock);
        EmbeddedChannel channel = channel(handler);

        assertFalse(channel.writeInbound(registrationFrame()));
        Jt808Frame registrationReply = channel.readOutbound();
        assertEquals(0x8100, registrationReply.header().messageId());
        assertTrue(registrationReply.body().toString(StandardCharsets.US_ASCII).contains(AUTHENTICATION_TOKEN));
        release(registrationReply);
        assertEquals(TerminalSessionState.CONNECTED_UNAUTHENTICATED, handler.session().state());

        assertFalse(channel.writeInbound(authenticationFrame(AUTHENTICATION_TOKEN)));
        release(channel.readOutbound());
        assertEquals(TerminalSessionState.AUTHENTICATED, handler.session().state());
        assertEquals(TERMINAL_ID, handler.session().terminalId());

        Instant beforeHeartbeat = handler.session().lastValidMessageAt();
        clock.advance(Duration.ofSeconds(60));
        assertFalse(channel.writeInbound(frame(0x0002, new byte[0])));
        release(channel.readOutbound());
        assertTrue(handler.session().lastValidMessageAt().isAfter(beforeHeartbeat));

        Jt808Frame location = frame(0x0200, new byte[]{1, 2});
        assertTrue(channel.writeInbound(location));
        Jt808Frame forwarded = channel.readInbound();
        assertSame(location, forwarded);
        release(forwarded);
        channel.finishAndReleaseAll();
    }

    @Test
    void closesAfterThirdFailedAuthentication() {
        MutableClock clock = new MutableClock();
        FakeTerminalRegistry port = FakeTerminalRegistry.approved();
        RegistrationAuthenticationHandler handler = handler(port, new TerminalSessionRegistry(), clock);
        EmbeddedChannel channel = channel(handler);
        channel.writeInbound(registrationFrame());
        release(channel.readOutbound());

        for (int attempt = 1; attempt <= 2; attempt++) {
            assertFalse(channel.writeInbound(authenticationFrame("WRONG-" + attempt)));
            release(channel.readOutbound());
            assertTrue(channel.isActive());
        }
        assertFalse(channel.writeInbound(authenticationFrame("WRONG-3")));
        assertFalse(channel.isActive());
        assertEquals(3, handler.session().authenticationFailures());
        assertEquals(SessionAuditType.AUTHENTICATION_LOCKED, port.lastAudit().type());
        drainOutbound(channel);
        channel.finishAndReleaseAll();
    }

    @Test
    void readerIdleMarksSessionOfflineAfterOneHundredEightySeconds() {
        MutableClock clock = new MutableClock();
        FakeTerminalRegistry port = FakeTerminalRegistry.approved();
        RegistrationAuthenticationHandler handler = handler(port, new TerminalSessionRegistry(), clock);
        EmbeddedChannel channel = channel(handler);
        authenticate(channel);

        clock.advance(Duration.ofSeconds(181));
        channel.pipeline().fireUserEventTriggered(io.netty.handler.timeout.IdleStateEvent.READER_IDLE_STATE_EVENT);
        assertFalse(channel.isActive());
        assertEquals(TerminalSessionState.CLOSED, handler.session().state());
        assertEquals(SessionAuditType.SESSION_OFFLINE, port.lastAudit().type());
        channel.finishAndReleaseAll();
    }

    @Test
    void aNewAuthenticatedConnectionTakesOverTheOldOne() {
        MutableClock clock = new MutableClock();
        FakeTerminalRegistry port = FakeTerminalRegistry.approved();
        TerminalSessionRegistry sessions = new TerminalSessionRegistry();
        RegistrationAuthenticationHandler firstHandler = handler(port, sessions, clock);
        RegistrationAuthenticationHandler secondHandler = handler(port, sessions, clock);
        EmbeddedChannel first = channel(firstHandler);
        EmbeddedChannel second = channel(secondHandler);

        authenticate(first);
        assertTrue(first.isActive());
        authenticate(second);

        assertFalse(first.isActive());
        assertTrue(second.isActive());
        assertSame(secondHandler.session(), sessions.current(TERMINAL_ID).orElseThrow());
        assertTrue(port.audits.stream().anyMatch(event -> event.type() == SessionAuditType.SESSION_TAKEN_OVER));
        first.finishAndReleaseAll();
        second.finishAndReleaseAll();
    }

    @Test
    void rejectsIdentityChangeAfterRegistrationWithoutKeepingPlainTerminalNumber() {
        MutableClock clock = new MutableClock();
        FakeTerminalRegistry port = FakeTerminalRegistry.approved();
        RegistrationAuthenticationHandler handler = handler(
                port, new TerminalSessionRegistry(), clock);
        EmbeddedChannel channel = channel(handler);
        authenticate(channel);

        Jt808Frame forgedLocation = frame(0x0200, new byte[]{1, 2}, "999999999999");
        assertFalse(channel.writeInbound(forgedLocation));

        assertFalse(channel.isActive());
        assertEquals(SessionAuditType.SESSION_IDENTITY_MISMATCH, port.lastAudit().type());
        assertFalse(handler.session().terminalAlias().contains(TERMINAL_NUMBER));
        channel.finishAndReleaseAll();
    }

    @Test
    void issuesHighEntropyTokenAndRedactsItFromDiagnosticText() throws Exception {
        RegistrationDecision decision = RegistrationDecision.issue(TERMINAL_ID, 7, new SecureRandom());
        byte[] token = decision.authenticationToken();

        assertTrue(token.length >= 43);
        assertEquals(sha256(token), decision.authenticationTokenSha256());
        assertFalse(decision.toString().contains(new String(token, StandardCharsets.US_ASCII)));
    }

    @Test
    void releasesOutboundResponseBodyAfterWriteCompletes() {
        AtomicReference<Jt808Frame> written = new AtomicReference<>();
        ChannelOutboundHandlerAdapter sink = new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                written.set((Jt808Frame) message);
                promise.setSuccess();
            }
        };
        EmbeddedChannel channel = new EmbeddedChannel(
                sink,
                new JtFrameOwnershipHandler(),
                handler(FakeTerminalRegistry.approved(), new TerminalSessionRegistry(), new MutableClock()));

        assertFalse(channel.writeInbound(registrationFrame()));

        assertNotNull(written.get());
        assertEquals(0, written.get().body().refCnt());
        channel.finishAndReleaseAll();
    }

    @Test
    void removingHandlerReleasesAuthenticatedSessionWithoutClosingChannel() {
        MutableClock clock = new MutableClock();
        FakeTerminalRegistry port = FakeTerminalRegistry.approved();
        TerminalSessionRegistry sessions = new TerminalSessionRegistry();
        RegistrationAuthenticationHandler handler = handler(port, sessions, clock);
        EmbeddedChannel channel = channel(handler);
        authenticate(channel);
        assertTrue(sessions.current(TERMINAL_ID).isPresent());

        channel.pipeline().remove(handler);

        assertTrue(channel.isActive());
        assertTrue(sessions.current(TERMINAL_ID).isEmpty());
        assertEquals(TerminalSessionState.CLOSED, handler.session().state());
        channel.finishAndReleaseAll();
    }

    private static EmbeddedChannel channel(
            TerminalRegistryPort port, TerminalSessionRegistry sessions, Clock clock) {
        return channel(handler(port, sessions, clock));
    }

    private static EmbeddedChannel channel(RegistrationAuthenticationHandler handler) {
        return new EmbeddedChannel(
                new OutboundResponseCopy(), new JtFrameOwnershipHandler(), handler);
    }

    private static RegistrationAuthenticationHandler handler(
            TerminalRegistryPort port, TerminalSessionRegistry sessions, Clock clock) {
        return new RegistrationAuthenticationHandler(port, sessions, clock, Duration.ofSeconds(30));
    }

    private static void authenticate(EmbeddedChannel channel) {
        channel.writeInbound(registrationFrame());
        release(channel.readOutbound());
        channel.writeInbound(authenticationFrame(AUTHENTICATION_TOKEN));
        release(channel.readOutbound());
    }

    private static Jt808Frame registrationFrame() {
        ByteBuf body = Unpooled.buffer();
        body.writeShort(62).writeShort(621);
        writeFixedAscii(body, "MFG01", 5);
        writeFixedAscii(body, "PILOT-MODEL", 20);
        writeFixedAscii(body, "TERM001", 7);
        body.writeByte(1);
        body.writeCharSequence("PILOT-A", StandardCharsets.US_ASCII);
        return frame(0x0100, readableBytes(body));
    }

    private static Jt808Frame authenticationFrame(String token) {
        return frame(0x0102, token.getBytes(StandardCharsets.US_ASCII));
    }

    private static Jt808Frame frame(int messageId, byte[] body) {
        return frame(messageId, body, TERMINAL_NUMBER);
    }

    private static Jt808Frame frame(int messageId, byte[] body, String terminalNumber) {
        Jt808MessageHeader header = new Jt808MessageHeader(
                messageId, body.length, body.length, 0, false,
                ProtocolVersion.JT808_2013, 0, terminalNumber, 9, null, null);
        return new Jt808Frame(header, Unpooled.wrappedBuffer(body), (byte) 0);
    }

    private static void writeFixedAscii(ByteBuf target, String value, int length) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        target.writeBytes(bytes);
        target.writeZero(length - bytes.length);
    }

    private static byte[] readableBytes(ByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.readBytes(bytes);
        buffer.release();
        return bytes;
    }

    private static void release(Jt808Frame frame) {
        if (frame != null) {
            frame.body().release();
        }
    }

    private static void drainOutbound(EmbeddedChannel channel) {
        Jt808Frame frame;
        while ((frame = channel.readOutbound()) != null) {
            release(frame);
        }
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-08-12T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class FakeTerminalRegistry implements TerminalRegistryPort {
        private final RegistrationRejection registrationRejection;
        private final List<SessionAuditIngress> audits = new ArrayList<>();

        private FakeTerminalRegistry(RegistrationRejection registrationRejection) {
            this.registrationRejection = registrationRejection;
        }

        static FakeTerminalRegistry approved() {
            return new FakeTerminalRegistry(null);
        }

        static FakeTerminalRegistry rejected(RegistrationRejection rejection) {
            return new FakeTerminalRegistry(rejection);
        }

        @Override
        public RegistrationDecision verifyRegistration(TerminalRegistrationIdentity identity) {
            if (registrationRejection != null) {
                return RegistrationDecision.rejected(registrationRejection);
            }
            assertEquals(TERMINAL_NUMBER, identity.terminalNumber());
            assertEquals("MFG01", identity.manufacturerId());
            assertEquals("PILOT-MODEL", identity.model());
            assertEquals("TERM001", identity.terminalCode());
            assertEquals("PILOT-A", identity.vehicleIdentifier());
            return RegistrationDecision.approved(
                    TERMINAL_ID,
                    5,
                    AUTHENTICATION_TOKEN.getBytes(StandardCharsets.US_ASCII),
                    uncheckedSha256(AUTHENTICATION_TOKEN.getBytes(StandardCharsets.US_ASCII)));
        }

        @Override
        public AuthenticationDecision verifyAuthentication(
                UUID terminalId, int tokenVersion, String presentedTokenSha256) {
            return uncheckedSha256(AUTHENTICATION_TOKEN.getBytes(StandardCharsets.US_ASCII))
                    .equals(presentedTokenSha256)
                    ? AuthenticationDecision.allow()
                    : AuthenticationDecision.rejected(AuthenticationRejection.TOKEN_MISMATCH);
        }

        @Override
        public void recordSessionAudit(SessionAuditIngress event) {
            audits.add(event);
        }

        SessionAuditIngress lastAudit() {
            return audits.get(audits.size() - 1);
        }

        private static String uncheckedSha256(byte[] value) {
            try {
                return sha256(value);
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }
    }

    private static final class OutboundResponseCopy extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
            if (message instanceof Jt808Frame frame) {
                context.write(new Jt808Frame(
                        frame.header(), frame.body().copy(), frame.checksum()), promise);
                return;
            }
            context.write(message, promise);
        }
    }
}
