package com.idavy.drtops.jtgateway.ingress;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.jt.protocol.codec.Jt808Frame;
import com.idavy.drtops.jt.protocol.codec.Jt808MessageHeader;
import com.idavy.drtops.jt.protocol.codec.ProtocolVersion;
import com.idavy.drtops.jt.protocol.core.LocationReportCodec;
import com.idavy.drtops.jtgateway.dispatch.ProtocolModuleRegistry;
import com.idavy.drtops.jtgateway.session.TerminalSession;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalPositionIngressTest {
    private static final UUID TERMINAL_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VEHICLE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant RECEIVED_AT = Instant.parse("2026-08-12T00:00:00Z");

    @Test
    void usesTheVehicleBindingFrozenIntoTheAuthenticatedSession() {
        TerminalSession session = authenticatedSession(VEHICLE_ID, "GCJ02");
        CapturingBuffer buffer = new CapturingBuffer(GatewayIngressBuffer.WriteResult.STORED);
        ProtocolModuleRegistry registry = new ProtocolModuleRegistry(
                new LocationReportCodec(), buffer, Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));
        Jt808Frame frame = locationFrame(126);

        ProtocolModuleRegistry.DispatchResult result = registry.dispatch(session, frame);

        assertTrue(result.mayAcknowledgeSuccess());
        assertEquals(0, frame.body().refCnt());
        assertEquals(VEHICLE_ID, buffer.envelope().vehicleId());
        assertEquals(TERMINAL_ID, buffer.envelope().terminalId());
        assertEquals("GCJ02", buffer.envelope().rawCoordinateSystem());
    }

    @Test
    void refusesPositionIngressWithoutAnAuthenticatedBindingSnapshot() {
        TerminalSession session = new TerminalSession(new EmbeddedChannel(), RECEIVED_AT);
        CapturingBuffer buffer = new CapturingBuffer(GatewayIngressBuffer.WriteResult.STORED);
        ProtocolModuleRegistry registry = new ProtocolModuleRegistry(
                new LocationReportCodec(), buffer, Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));
        Jt808Frame frame = locationFrame(126);

        ProtocolModuleRegistry.DispatchResult result = registry.dispatch(session, frame);

        assertFalse(result.mayAcknowledgeSuccess());
        assertNull(buffer.envelope());
        assertEquals(0, frame.body().refCnt());
    }

    @Test
    void refusesToFreezeAnUnsupportedCoordinateSystemIntoTheSession() {
        TerminalSession session = new TerminalSession(new EmbeddedChannel(), RECEIVED_AT);

        assertThrows(IllegalArgumentException.class,
                () -> session.registrationAccepted(TERMINAL_ID, VEHICLE_ID, "BD09", 5, "123456789012"));
    }

    @Test
    void derivesStableIdempotencyKeysFromEveryPositionIdentityField() {
        TerminalSession session = authenticatedSession(VEHICLE_ID, "WGS84");
        CapturingBuffer first = new CapturingBuffer(GatewayIngressBuffer.WriteResult.STORED);
        CapturingBuffer second = new CapturingBuffer(GatewayIngressBuffer.WriteResult.STORED);
        ProtocolModuleRegistry registry = new ProtocolModuleRegistry(
                new LocationReportCodec(), first, Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));

        registry.dispatch(session, locationFrame(126));
        new ProtocolModuleRegistry(new LocationReportCodec(), second, Clock.fixed(RECEIVED_AT, ZoneOffset.UTC))
                .dispatch(session, locationFrame(126));

        assertEquals(
                ProtocolModuleRegistry.idempotencyKeyFor(first.envelope()),
                ProtocolModuleRegistry.idempotencyKeyFor(second.envelope()));

        CapturingBuffer changedSerial = new CapturingBuffer(GatewayIngressBuffer.WriteResult.STORED);
        new ProtocolModuleRegistry(new LocationReportCodec(), changedSerial, Clock.fixed(RECEIVED_AT, ZoneOffset.UTC))
                .dispatch(session, locationFrame(127));
        assertNotEquals(
                ProtocolModuleRegistry.idempotencyKeyFor(first.envelope()),
                ProtocolModuleRegistry.idempotencyKeyFor(changedSerial.envelope()));
    }

    @Test
    void onlyAcknowledgesPositionsStoredOrDeduplicatedByThePersistentBuffer() {
        TerminalSession session = authenticatedSession(VEHICLE_ID, "WGS84");

        for (GatewayIngressBuffer.WriteResult result : new GatewayIngressBuffer.WriteResult[] {
                GatewayIngressBuffer.WriteResult.STORED,
                GatewayIngressBuffer.WriteResult.DUPLICATE}) {
            ProtocolModuleRegistry registry = new ProtocolModuleRegistry(
                    new LocationReportCodec(), new CapturingBuffer(result), Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));
            Jt808Frame frame = locationFrame(126);
            assertTrue(registry.dispatch(session, frame).mayAcknowledgeSuccess());
            assertEquals(0, frame.body().refCnt());
        }

        ProtocolModuleRegistry unavailable = new ProtocolModuleRegistry(
                new LocationReportCodec(),
                new CapturingBuffer(GatewayIngressBuffer.WriteResult.UNAVAILABLE),
                Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));
        Jt808Frame unavailableFrame = locationFrame(126);
        assertFalse(unavailable.dispatch(session, unavailableFrame).mayAcknowledgeSuccess());
        assertEquals(0, unavailableFrame.body().refCnt());
    }

    @Test
    void acknowledgesUnknownLegalMessagesWithoutSendingThemToThePositionDomain() {
        TerminalSession session = authenticatedSession(VEHICLE_ID, "WGS84");
        CapturingBuffer buffer = new CapturingBuffer(GatewayIngressBuffer.WriteResult.STORED);
        ProtocolModuleRegistry registry = new ProtocolModuleRegistry(
                new LocationReportCodec(), buffer, Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));

        Jt808Frame frame = unknownFrame();
        ProtocolModuleRegistry.DispatchResult result = registry.dispatch(session, frame);

        assertTrue(result.mayAcknowledgeSuccess());
        assertEquals(1, registry.unknownMessageCount());
        assertNull(buffer.envelope());
        assertEquals(0, frame.body().refCnt());
    }

    @Test
    void persistsCanonicalPositionsThroughTheRealH2GatewayBufferBeforeReportingAcknowledgement() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:position_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        GatewayOutboxRepository repository = new GatewayOutboxRepository(dataSource);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        GatewayIngressBuffer buffer = new GatewayIngressBuffer(
                repository, objectMapper, Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));
        ProtocolModuleRegistry registry = new ProtocolModuleRegistry(
                new LocationReportCodec(), buffer, objectMapper, Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));
        Jt808Frame frame = locationFrame(126);

        assertTrue(registry.dispatch(authenticatedSession(VEHICLE_ID, "WGS84"), frame).mayAcknowledgeSuccess());
        assertEquals(1, repository.totalCount());
        assertEquals(0, frame.body().refCnt());

        Jt808Frame duplicate = locationFrame(126);
        assertTrue(registry.dispatch(authenticatedSession(VEHICLE_ID, "WGS84"), duplicate).mayAcknowledgeSuccess());
        assertEquals(1, repository.totalCount());
        assertEquals(0, duplicate.body().refCnt());
    }

    @Test
    void refusesSuccessDecisionWhenTheRealGatewayBufferIsUnavailable() {
        GatewayIngressBuffer buffer = new GatewayIngressBuffer(
                new GatewayOutboxRepository(new AlwaysFailingDataSource()),
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));
        ProtocolModuleRegistry registry = new ProtocolModuleRegistry(
                new LocationReportCodec(), buffer, new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));
        Jt808Frame frame = locationFrame(126);

        assertFalse(registry.dispatch(authenticatedSession(VEHICLE_ID, "WGS84"), frame).mayAcknowledgeSuccess());
        assertFalse(buffer.bufferWritable());
        assertEquals(0, frame.body().refCnt());
    }

    @Test
    void idempotencyKeyChangesForEverySpecifiedIdentityComponentButNotVehicleOrReceiptTime() {
        CanonicalPositionIngress base = position(
                TERMINAL_ID, "JT808_2013", 126, Instant.parse("2018-10-15T02:10:10Z"), "a".repeat(64), RECEIVED_AT,
                VEHICLE_ID);
        assertNotEquals(key(base), key(position(UUID.randomUUID(), "JT808_2013", 126,
                base.terminalLocatedAt(), base.payloadDigest(), RECEIVED_AT, VEHICLE_ID)));
        assertNotEquals(key(base), key(position(TERMINAL_ID, "JT808_2019", 126,
                base.terminalLocatedAt(), base.payloadDigest(), RECEIVED_AT, VEHICLE_ID)));
        assertNotEquals(
                ProtocolModuleRegistry.idempotencyKeyFor(base, 0x0200),
                ProtocolModuleRegistry.idempotencyKeyFor(base, 0x0201));
        assertNotEquals(key(base), key(position(TERMINAL_ID, "JT808_2013", 127,
                base.terminalLocatedAt(), base.payloadDigest(), RECEIVED_AT, VEHICLE_ID)));
        assertNotEquals(key(base), key(position(TERMINAL_ID, "JT808_2013", 126,
                base.terminalLocatedAt().plusSeconds(1), base.payloadDigest(), RECEIVED_AT, VEHICLE_ID)));
        assertNotEquals(key(base), key(position(TERMINAL_ID, "JT808_2013", 126,
                base.terminalLocatedAt(), "b".repeat(64), RECEIVED_AT, VEHICLE_ID)));
        assertEquals(key(base), key(position(TERMINAL_ID, "JT808_2013", 126,
                base.terminalLocatedAt(), base.payloadDigest(), RECEIVED_AT.plusSeconds(1), UUID.randomUUID())));
    }

    private static TerminalSession authenticatedSession(UUID vehicleId, String sourceCoordinateSystem) {
        EmbeddedChannel channel = new EmbeddedChannel();
        TerminalSession session = new TerminalSession(channel, RECEIVED_AT);
        session.registrationAccepted(TERMINAL_ID, vehicleId, sourceCoordinateSystem, 5, "123456789012");
        session.authenticated(RECEIVED_AT);
        return session;
    }

    private static Jt808Frame locationFrame(int serialNumber) {
        byte[] body = java.util.HexFormat.of().parseHex(
                "000000010000000200ba7f0e07e4f11c0028003c00001810151010100104000000640202007d");
        return new Jt808Frame(new Jt808MessageHeader(
                0x0200, body.length, body.length, 0, false, ProtocolVersion.JT808_2013, 0,
                "123456789012", serialNumber, null, null), Unpooled.wrappedBuffer(body), (byte) 0);
    }

    private static Jt808Frame unknownFrame() {
        return new Jt808Frame(new Jt808MessageHeader(
                0x0fff, 0, 0, 0, false, ProtocolVersion.JT808_2013, 0,
                "123456789012", 126, null, null), Unpooled.buffer(0), (byte) 0);
    }

    private static CanonicalPositionIngress position(
            UUID terminalId,
            String protocolVersion,
            int serialNo,
            Instant locatedAt,
            String payloadDigest,
            Instant receivedAt,
            UUID vehicleId) {
        return new CanonicalPositionIngress(
                terminalId, vehicleId, protocolVersion, serialNo,
                new BigDecimal("132.444444"), new BigDecimal("12.222222"), "WGS84",
                locatedAt, receivedAt, 1, 2, new BigDecimal("6.0"), 0, 40, null, payloadDigest);
    }

    private static UUID key(CanonicalPositionIngress position) {
        return ProtocolModuleRegistry.idempotencyKeyFor(position);
    }

    private static final class CapturingBuffer implements PositionIngressBuffer {
        private final GatewayIngressBuffer.WriteResult result;
        private CanonicalPositionIngress captured;

        private CapturingBuffer(GatewayIngressBuffer.WriteResult result) {
            this.result = result;
        }

        @Override
        public GatewayIngressBuffer.WriteResult append(CanonicalPositionIngress envelope) {
            captured = envelope;
            return result;
        }

        CanonicalPositionIngress envelope() {
            return captured;
        }
    }

    private static final class AlwaysFailingDataSource implements DataSource {
        @Override
        public java.sql.Connection getConnection() throws java.sql.SQLException {
            throw new java.sql.SQLException("synthetic buffer outage");
        }

        @Override
        public java.sql.Connection getConnection(String username, String password) throws java.sql.SQLException {
            throw new java.sql.SQLException("synthetic buffer outage");
        }

        @Override public <T> T unwrap(Class<T> iface) throws java.sql.SQLException { throw new java.sql.SQLException("unsupported"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException {
            throw new java.sql.SQLFeatureNotSupportedException();
        }
    }
}
