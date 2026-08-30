package com.idavy.drtops.jtgateway.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.idavy.drtops.jt.protocol.codec.Jt808Frame;
import com.idavy.drtops.jt.protocol.codec.Jt808MessageHeader;
import com.idavy.drtops.jt.protocol.codec.ProtocolVersion;
import com.idavy.drtops.jt.protocol.core.Jt808CoreModule;
import com.idavy.drtops.jt.protocol.core.LocationReportCodec;
import com.idavy.drtops.jtgateway.dispatch.ProtocolModuleRegistry;
import com.idavy.drtops.jtgateway.ingress.GatewayIngressBuffer;
import com.idavy.drtops.jtgateway.session.AuthenticationDecision;
import com.idavy.drtops.jtgateway.session.RegistrationAuthenticationHandler;
import com.idavy.drtops.jtgateway.session.RegistrationDecision;
import com.idavy.drtops.jtgateway.session.SessionAuditIngress;
import com.idavy.drtops.jtgateway.session.TerminalRegistrationIdentity;
import com.idavy.drtops.jtgateway.session.TerminalRegistryPort;
import com.idavy.drtops.jtgateway.session.TerminalSession;
import com.idavy.drtops.jtgateway.session.TerminalSessionContext;
import com.idavy.drtops.jtgateway.session.TerminalSessionRegistry;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProtocolDispatchHandlerOwnershipTest {
    private static final String IDENTITY = "123456789012";
    private static final UUID TERMINAL_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ONBOARD_SYSTEM_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID VEHICLE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-21T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void releasesAFrameExactlyOnceAfterSuccessfulDispatch() {
        Fixture fixture = fixture();
        Jt808Frame frame = frame(0x0fff, 0, new byte[] {1});
        try {
            fixture.channel().writeInbound(frame);
            assertEquals(0, frame.body().refCnt());
            Jt808Frame reply = fixture.channel().readOutbound();
            assertNotNull(reply);
            reply.body().release();
        } finally {
            fixture.channel().finishAndReleaseAll();
        }
    }

    @Test
    void releasesAFrameExactlyOnceAfterRejectedDispatch() {
        Fixture fixture = fixture();
        Jt808Frame frame = frame(0x0fff, 1, new byte[] {1});
        try {
            fixture.channel().writeInbound(frame);
            assertEquals(0, frame.body().refCnt());
        } finally {
            fixture.channel().finishAndReleaseAll();
        }
    }

    @Test
    void releasesAFrameExactlyOnceWhenDispatchThrows() {
        Fixture fixture = fixture();
        Jt808Frame frame = frame(0x0200, 0, new byte[] {0});
        try {
            assertThrows(RuntimeException.class, () -> fixture.channel().writeInbound(frame));
            assertEquals(0, frame.body().refCnt());
        } finally {
            fixture.channel().finishAndReleaseAll();
        }
    }

    private static Fixture fixture() {
        TerminalSessionRegistry sessions = new TerminalSessionRegistry();
        RegistrationAuthenticationHandler authentication = new RegistrationAuthenticationHandler(
                new NoOpRegistry(), sessions, CLOCK, Duration.ofSeconds(30));
        ProtocolModuleRegistry modules = new ProtocolModuleRegistry(
                new Jt808CoreModule(new LocationReportCodec()),
                ignored -> GatewayIngressBuffer.WriteResult.STORED,
                sessions,
                CLOCK);
        EmbeddedChannel channel = new EmbeddedChannel(
                authentication, new ProtocolDispatchHandler(authentication, modules));
        TerminalSession session = authentication.session();
        session.registrationAccepted(new TerminalSessionContext(
                TERMINAL_ID,
                ONBOARD_SYSTEM_ID,
                VEHICLE_ID,
                Set.of("LOCATION_PRIMARY"),
                "WGS84",
                null,
                List.of(),
                1), IDENTITY);
        session.authenticated(CLOCK.instant());
        sessions.claim(session);
        return new Fixture(channel);
    }

    private static Jt808Frame frame(int messageId, int encryption, byte[] body) {
        int properties = body.length | (encryption << 10);
        return new Jt808Frame(new Jt808MessageHeader(
                messageId, properties, body.length, encryption, false,
                ProtocolVersion.JT808_2013, 0, IDENTITY, 1, null, null),
                Unpooled.wrappedBuffer(body), (byte) 0);
    }

    private record Fixture(EmbeddedChannel channel) { }

    private static final class NoOpRegistry implements TerminalRegistryPort {
        @Override
        public RegistrationDecision verifyRegistration(TerminalRegistrationIdentity identity) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AuthenticationDecision verifyAuthentication(
                UUID terminalId, int tokenVersion, String presentedTokenSha256) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void recordSessionAudit(SessionAuditIngress event) {
        }
    }
}
