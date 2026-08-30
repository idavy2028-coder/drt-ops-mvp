package com.idavy.drtops.jtgateway.ingress;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.jt.protocol.codec.Jt808Frame;
import com.idavy.drtops.jt.protocol.codec.Jt808MessageHeader;
import com.idavy.drtops.jt.protocol.codec.ProtocolVersion;
import com.idavy.drtops.jt.protocol.core.LocationReportCodec;
import com.idavy.drtops.jt.protocol.core.Jt808CoreModule;
import com.idavy.drtops.jtgateway.dispatch.ProtocolModuleRegistry;
import com.idavy.drtops.jtgateway.session.TerminalSession;
import com.idavy.drtops.jtgateway.session.TerminalSessionContext;
import com.idavy.drtops.jtgateway.session.TerminalSessionRegistry;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalPositionIngressTest {
    private static final UUID TERMINAL_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ONBOARD_SYSTEM_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID VEHICLE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant RECEIVED_AT = Instant.parse("2026-08-12T00:00:00Z");

    @Test
    void usesTheVehicleBindingFrozenIntoTheAuthenticatedSession() {
        TerminalSession session = authenticatedSession(VEHICLE_ID, "GCJ02");
        CapturingBuffer buffer = new CapturingBuffer(GatewayIngressBuffer.WriteResult.STORED);
        ProtocolModuleRegistry registry = new ProtocolModuleRegistry(
                new Jt808CoreModule(new LocationReportCodec()), buffer, claimed(session), Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));
        Jt808Frame frame = locationFrame(126);

        ProtocolModuleRegistry.DispatchResult result = registry.dispatch(session, frame);

        assertTrue(result.mayAcknowledgeSuccess());
        assertEquals(0, frame.body().refCnt());
        assertEquals(VEHICLE_ID, buffer.envelope().vehicleId());
        assertEquals(TERMINAL_ID, buffer.envelope().terminalId());
        assertEquals(ONBOARD_SYSTEM_ID, buffer.envelope().onboardSystemId());
        assertEquals("LOCATION_PRIMARY", buffer.envelope().sourceRole());
        assertEquals("GCJ02", buffer.envelope().rawCoordinateSystem());
    }

    @Test
    void refusesPositionIngressWithoutAnAuthenticatedBindingSnapshot() {
        TerminalSession session = new TerminalSession(new EmbeddedChannel(), RECEIVED_AT);
        CapturingBuffer buffer = new CapturingBuffer(GatewayIngressBuffer.WriteResult.STORED);
        ProtocolModuleRegistry registry = new ProtocolModuleRegistry(
                new Jt808CoreModule(new LocationReportCodec()), buffer, new TerminalSessionRegistry(), Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));
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
    void rejectsMissingOrInvalidOnboardProvenanceAndUnsignedShortSerials() {
        assertThrows(NullPointerException.class, () -> positionWithProvenance(
                TERMINAL_ID, null, VEHICLE_ID, "LOCATION_PRIMARY", 1));
        assertThrows(IllegalArgumentException.class, () -> positionWithProvenance(
                TERMINAL_ID, ONBOARD_SYSTEM_ID, VEHICLE_ID, "DISPATCH", 1));
        assertThrows(IllegalArgumentException.class, () -> positionWithProvenance(
                TERMINAL_ID, ONBOARD_SYSTEM_ID, VEHICLE_ID, "LOCATION_PRIMARY", -1));
        assertThrows(IllegalArgumentException.class, () -> positionWithProvenance(
                TERMINAL_ID, ONBOARD_SYSTEM_ID, VEHICLE_ID, "LOCATION_PRIMARY", 65_536));
    }

    @Test
    void derivesStableIdempotencyKeysFromEveryPositionIdentityField() {
        TerminalSession session = authenticatedSession(VEHICLE_ID, "WGS84");
        CapturingBuffer first = new CapturingBuffer(GatewayIngressBuffer.WriteResult.STORED);
        CapturingBuffer second = new CapturingBuffer(GatewayIngressBuffer.WriteResult.STORED);
        ProtocolModuleRegistry registry = new ProtocolModuleRegistry(
                new Jt808CoreModule(new LocationReportCodec()), first, claimed(session), Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));

        registry.dispatch(session, locationFrame(126));
        new ProtocolModuleRegistry(new Jt808CoreModule(new LocationReportCodec()), second, claimed(session), Clock.fixed(RECEIVED_AT, ZoneOffset.UTC))
                .dispatch(session, locationFrame(126));

        assertEquals(
                ProtocolModuleRegistry.idempotencyKeyFor(first.envelope()),
                ProtocolModuleRegistry.idempotencyKeyFor(second.envelope()));

        CapturingBuffer changedSerial = new CapturingBuffer(GatewayIngressBuffer.WriteResult.STORED);
        new ProtocolModuleRegistry(new Jt808CoreModule(new LocationReportCodec()), changedSerial, claimed(session), Clock.fixed(RECEIVED_AT, ZoneOffset.UTC))
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
                    new Jt808CoreModule(new LocationReportCodec()), new CapturingBuffer(result), claimed(session), Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));
            Jt808Frame frame = locationFrame(126);
            assertTrue(registry.dispatch(session, frame).mayAcknowledgeSuccess());
            assertEquals(0, frame.body().refCnt());
        }

        ProtocolModuleRegistry unavailable = new ProtocolModuleRegistry(
                new Jt808CoreModule(new LocationReportCodec()),
                new CapturingBuffer(GatewayIngressBuffer.WriteResult.UNAVAILABLE),
                claimed(session),
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
                new Jt808CoreModule(new LocationReportCodec()), buffer, claimed(session), Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));

        Jt808Frame frame = unknownFrame();
        ProtocolModuleRegistry.DispatchResult result = registry.dispatch(session, frame);

        assertTrue(result.mayAcknowledgeSuccess());
        assertEquals(1, registry.unknownMessageCount());
        assertNull(buffer.envelope());
        assertEquals(0, frame.body().refCnt());
    }

    @Test
    void refusesUnknownMessagesBeforeTheSessionIsAuthenticatedAndCurrent() {
        TerminalSession unauthenticated = new TerminalSession(new EmbeddedChannel(), RECEIVED_AT);
        CapturingBuffer buffer = new CapturingBuffer(GatewayIngressBuffer.WriteResult.STORED);
        ProtocolModuleRegistry registry = new ProtocolModuleRegistry(
                new Jt808CoreModule(new LocationReportCodec()), buffer, new TerminalSessionRegistry(),
                Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));
        Jt808Frame frame = unknownFrame();

        assertFalse(registry.dispatch(unauthenticated, frame).mayAcknowledgeSuccess());
        assertEquals(0, registry.unknownMessageCount());
        assertNull(buffer.envelope());
        assertEquals(0, frame.body().refCnt());
    }

    @Test
    void persistsCanonicalPositionsThroughTheRealH2GatewayBufferBeforeReportingAcknowledgement() throws Exception {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:position_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        GatewayOutboxRepository repository = new GatewayOutboxRepository(dataSource);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        GatewayIngressBuffer buffer = new GatewayIngressBuffer(
                repository, objectMapper, Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));
        TerminalSession session = authenticatedSession(VEHICLE_ID, "WGS84");
        TerminalSessionRegistry sessions = claimed(session);
        ProtocolModuleRegistry registry = new ProtocolModuleRegistry(new Jt808CoreModule(new LocationReportCodec()), buffer, objectMapper, sessions,
                Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));
        Jt808Frame frame = locationFrame(126);
        assertTrue(registry.dispatch(session, frame).mayAcknowledgeSuccess());
        assertEquals(1, repository.totalCount());
        assertEquals(0, frame.body().refCnt());
        String payloadJson = repository.find(ProtocolModuleRegistry.idempotencyKeyFor(position(
                TERMINAL_ID, "JT808_2013", 126, Instant.parse("2018-10-15T02:10:10Z"),
                "35efad69597feac5fed7258bddb2bef840dede5ad8f9281951a3722077d107a4", RECEIVED_AT, VEHICLE_ID)))
                .orElseThrow().payloadJson();
        CanonicalPositionIngress payload = objectMapper.readValue(
                payloadJson, CanonicalPositionIngress.class);
        assertEquals(TERMINAL_ID, payload.terminalId());
        assertEquals(ONBOARD_SYSTEM_ID, payload.onboardSystemId());
        assertEquals(VEHICLE_ID, payload.vehicleId());
        assertEquals("LOCATION_PRIMARY", payload.sourceRole());
        assertEquals(new BigDecimal("132.444444"), payload.rawLongitude());
        assertEquals(new BigDecimal("12.222222"), payload.rawLatitude());
        assertEquals(Instant.parse("2018-10-15T02:10:10Z"), payload.terminalLocatedAt());
        assertEquals(1, payload.alarmBits());
        assertEquals(2, payload.statusBits());
        assertEquals("35efad69597feac5fed7258bddb2bef840dede5ad8f9281951a3722077d107a4", payload.payloadDigest());
        List<String> fieldOrder = new ArrayList<>();
        objectMapper.readTree(payloadJson).fieldNames().forEachRemaining(fieldOrder::add);
        assertEquals(List.of(
                "terminalId", "onboardSystemId", "vehicleId", "sourceRole",
                "protocolVersion", "messageSerialNo", "rawLongitude", "rawLatitude",
                "rawCoordinateSystem", "terminalLocatedAt", "gatewayReceivedAt",
                "alarmBits", "statusBits", "speedKph", "directionDegrees",
                "altitudeMeters", "satelliteCount", "payloadDigest"), fieldOrder);
        assertFalse(payloadJson.contains("123456789012"));

        Jt808Frame duplicate = locationFrame(126);
        assertTrue(registry.dispatch(session, duplicate).mayAcknowledgeSuccess());
        assertEquals(1, repository.totalCount());
        assertEquals(0, duplicate.body().refCnt());
    }

    @Test
    void rejectsUnauthenticatedIdentityMismatchEncryptionAndConsumedHandshakeMessagesBeforeUnknownClassification() {
        TerminalSession session = authenticatedSession(VEHICLE_ID, "WGS84");
        CapturingBuffer buffer = new CapturingBuffer(GatewayIngressBuffer.WriteResult.STORED);
        ProtocolModuleRegistry registry = new ProtocolModuleRegistry(
                new Jt808CoreModule(new LocationReportCodec()), buffer, claimed(session), Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));

        assertFalse(registry.dispatch(session, frame(0x0fff, 0, "999999999999")).mayAcknowledgeSuccess());
        assertFalse(registry.dispatch(session, frame(0x0fff, 1, "123456789012")).mayAcknowledgeSuccess());
        assertFalse(registry.dispatch(session, frame(0x0100, 0, "123456789012")).mayAcknowledgeSuccess());
        assertFalse(registry.dispatch(session, frame(0x0102, 0, "123456789012")).mayAcknowledgeSuccess());
        assertFalse(registry.dispatch(session, frame(0x0002, 0, "123456789012")).mayAcknowledgeSuccess());
        assertEquals(0, registry.unknownMessageCount());
        assertNull(buffer.envelope());
    }

    @Test
    void linearizesDispatchBeforeOrAfterSessionTakeoverWithoutDeadlock() throws Exception {
        TerminalSession oldSession = authenticatedSession(VEHICLE_ID, "WGS84");
        TerminalSessionRegistry sessions = claimed(oldSession);
        BlockingBuffer buffer = new BlockingBuffer();
        ProtocolModuleRegistry registry = new ProtocolModuleRegistry(
                new Jt808CoreModule(new LocationReportCodec()), buffer, sessions, Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ProtocolModuleRegistry.DispatchResult> firstDispatch = executor.submit(
                    () -> registry.dispatch(oldSession, locationFrame(126)));
            assertTrue(buffer.entered.await(5, TimeUnit.SECONDS));
            TerminalSession replacement = authenticatedSession(VEHICLE_ID, "WGS84");
            CountDownLatch takeoverStarted = new CountDownLatch(1);
            AtomicReference<Thread> takeoverThread = new AtomicReference<>();
            Future<?> takeover = executor.submit(() -> {
                takeoverThread.set(Thread.currentThread());
                takeoverStarted.countDown();
                sessions.claim(replacement);
            });
            assertTrue(takeoverStarted.await(5, TimeUnit.SECONDS));
            assertTrue(awaitBlocked(takeoverThread, 5));
            assertFalse(takeover.isDone());
            buffer.release.countDown();
            assertTrue(firstDispatch.get(5, TimeUnit.SECONDS).mayAcknowledgeSuccess());
            takeover.get(5, TimeUnit.SECONDS);

            Jt808Frame staleFrame = locationFrame(127);
            assertFalse(registry.dispatch(oldSession, staleFrame).mayAcknowledgeSuccess());
            assertEquals(1, buffer.appended);
            assertEquals(0, staleFrame.body().refCnt());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsAnOldDispatchQueuedAfterTakeoverHasCompleted() throws Exception {
        TerminalSession oldSession = authenticatedSession(VEHICLE_ID, "WGS84");
        TerminalSessionRegistry sessions = claimed(oldSession);
        TerminalSession replacement = authenticatedSession(VEHICLE_ID, "WGS84");
        sessions.claim(replacement);
        CapturingBuffer buffer = new CapturingBuffer(GatewayIngressBuffer.WriteResult.STORED);
        ProtocolModuleRegistry registry = new ProtocolModuleRegistry(
                new Jt808CoreModule(new LocationReportCodec()), buffer, sessions, Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));
        CountDownLatch currentLockHeld = new CountDownLatch(1);
        CountDownLatch releaseCurrentLock = new CountDownLatch(1);
        CountDownLatch staleDispatchStarted = new CountDownLatch(1);
        AtomicReference<Thread> staleDispatchThread = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Jt808Frame staleFrame = locationFrame(127);
        try {
            Future<?> currentOperation = executor.submit(() -> sessions.executeIfCurrent(replacement, () -> {
                currentLockHeld.countDown();
                await(releaseCurrentLock);
                return "held";
            }));
            assertTrue(currentLockHeld.await(5, TimeUnit.SECONDS));
            Future<ProtocolModuleRegistry.DispatchResult> staleDispatch = executor.submit(() -> {
                staleDispatchThread.set(Thread.currentThread());
                staleDispatchStarted.countDown();
                return registry.dispatch(oldSession, staleFrame);
            });
            assertTrue(staleDispatchStarted.await(5, TimeUnit.SECONDS));
            assertTrue(awaitBlocked(staleDispatchThread, 5));
            releaseCurrentLock.countDown();

            currentOperation.get(5, TimeUnit.SECONDS);
            assertFalse(staleDispatch.get(5, TimeUnit.SECONDS).mayAcknowledgeSuccess());
            assertNull(buffer.envelope());
            assertEquals(0, staleFrame.body().refCnt());
        } finally {
            releaseCurrentLock.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void refusesSuccessDecisionWhenTheRealGatewayBufferIsUnavailable() {
        TerminalSession session = authenticatedSession(VEHICLE_ID, "WGS84");
        GatewayIngressBuffer buffer = new GatewayIngressBuffer(
                new GatewayOutboxRepository(new AlwaysFailingDataSource()),
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));
        ProtocolModuleRegistry registry = new ProtocolModuleRegistry(
                new Jt808CoreModule(new LocationReportCodec()), buffer, new ObjectMapper().findAndRegisterModules(),
                claimed(session),
                Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));
        Jt808Frame frame = locationFrame(126);

        assertFalse(registry.dispatch(session, frame).mayAcknowledgeSuccess());
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
        session.registrationAccepted(new TerminalSessionContext(
                TERMINAL_ID,
                ONBOARD_SYSTEM_ID,
                vehicleId,
                Set.of("LOCATION_PRIMARY"),
                sourceCoordinateSystem,
                null,
                List.of(),
                5), "123456789012");
        session.authenticated(RECEIVED_AT);
        return session;
    }

    private static boolean awaitBlocked(AtomicReference<Thread> threadReference, int timeoutSeconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            Thread thread = threadReference.get();
            if (thread != null && thread.getState() == Thread.State.BLOCKED) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("test did not release terminal lock");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static TerminalSessionRegistry claimed(TerminalSession session) {
        TerminalSessionRegistry sessions = new TerminalSessionRegistry();
        sessions.claim(session);
        return sessions;
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

    private static Jt808Frame frame(int messageId, int encryptionType, String terminalIdentity) {
        return new Jt808Frame(new Jt808MessageHeader(
                messageId, encryptionType << 10, 0, encryptionType, false, ProtocolVersion.JT808_2013, 0,
                terminalIdentity, 126, null, null), Unpooled.buffer(0), (byte) 0);
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
                terminalId, ONBOARD_SYSTEM_ID, vehicleId, "LOCATION_PRIMARY", protocolVersion, serialNo,
                new BigDecimal("132.444444"), new BigDecimal("12.222222"), "WGS84",
                locatedAt, receivedAt, 1L, 2L, new BigDecimal("6.0"), 0, 40, null, payloadDigest);
    }

    private static CanonicalPositionIngress positionWithProvenance(
            UUID terminalId,
            UUID onboardSystemId,
            UUID vehicleId,
            String sourceRole,
            int serialNo) {
        return new CanonicalPositionIngress(
                terminalId,
                onboardSystemId,
                vehicleId,
                sourceRole,
                "JT808_2019",
                serialNo,
                new BigDecimal("132.444444"),
                new BigDecimal("12.222222"),
                "WGS84",
                RECEIVED_AT,
                RECEIVED_AT,
                0L,
                0L,
                new BigDecimal("6.0"),
                0,
                40,
                null,
                "a".repeat(64));
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

    private static final class BlockingBuffer implements PositionIngressBuffer {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile int appended;

        @Override
        public GatewayIngressBuffer.WriteResult append(CanonicalPositionIngress envelope) {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("test did not release blocked buffer");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            appended++;
            return GatewayIngressBuffer.WriteResult.STORED;
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
