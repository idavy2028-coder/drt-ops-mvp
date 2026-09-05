package com.idavy.drtops.jtgateway.netty;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.idavy.drtops.jt.protocol.codec.Jt808Frame;
import com.idavy.drtops.jt.protocol.codec.Jt808FrameEncoder;
import com.idavy.drtops.jt.protocol.codec.Jt808MessageHeader;
import com.idavy.drtops.jt.protocol.codec.ProtocolVersion;
import com.idavy.drtops.jtgateway.session.AuthenticationDecision;
import com.idavy.drtops.jtgateway.session.RegistrationDecision;
import com.idavy.drtops.jtgateway.session.SessionAuditIngress;
import com.idavy.drtops.jtgateway.session.SessionLeaseReporter;
import com.idavy.drtops.jtgateway.session.TerminalRegistrationIdentity;
import com.idavy.drtops.jtgateway.session.TerminalRegistryPort;
import com.idavy.drtops.jtgateway.session.TerminalSession;
import com.idavy.drtops.jtgateway.session.TerminalSessionContext;
import com.idavy.drtops.jtgateway.session.TerminalSessionState;
import com.idavy.drtops.jtgateway.session.TerminalSessionRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.IdleStateHandler;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class JtGatewayServerIntegrationTest {
    private static final UUID TERMINAL_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PEER_TERMINAL_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ONBOARD_SYSTEM_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID VEHICLE_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final String AUTHENTICATION_TOKEN = "PIPELINE-TEST-TOKEN";
    private static final Clock TEST_RUNTIME_CLOCK = Clock.systemUTC();
    private static final Duration TEST_LEASE_DURATION = Duration.ofSeconds(180);

    @Test
    void buildsHandlersInSecurityAndProtocolOrderWithReaderIdleTimeout() {
        DefaultEventLoopGroup businessWorkers = new DefaultEventLoopGroup(1);
        try {
            ApprovingRegistry registry = new ApprovingRegistry();
            JtChannelInitializer initializer = new JtChannelInitializer(
                    new ConnectionAdmissionHandler.AdmissionTracker(4, 100),
                    registry,
                    new TerminalSessionRegistry(),
                    businessWorkers,
                    () -> 0,
                    Clock.systemUTC(),
                    80,
                    40,
                    Duration.ofSeconds(5),
                    reporter(registry));
            EmbeddedChannel channel = new EmbeddedChannel(initializer);

            List<String> names = channel.pipeline().names();
            assertOrdered(names,
                    "connectionAdmission",
                    "frameDecoder",
                    "readerIdle",
                    "messageRateLimit",
                    "frameEncoder",
                    "frameOwnership",
                    "businessBackpressure",
                    "registrationAuthentication",
                    "terminalExceptionGuard");
            IdleStateHandler idle = (IdleStateHandler) channel.pipeline().get("readerIdle");
            assertEquals(180, idle.getReaderIdleTimeInMillis() / 1000);
            channel.pipeline().fireExceptionCaught(new IOException("synthetic socket reset"));
            await(() -> !channel.isActive(), Duration.ofSeconds(2));
            channel.finishAndReleaseAll();
        } finally {
            businessWorkers.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    @Test
    void enforcesPerIpConnectionLimitOnRealLoopbackPort() throws Exception {
        JtGatewayServer.Configuration configuration = configuration(1, 100);
        ApprovingRegistry registry = new ApprovingRegistry();
        try (JtGatewayServer server = new JtGatewayServer(
                configuration, registry, new TerminalSessionRegistry(), reporter(registry))) {
            int port = server.start();
            try (Socket first = socket(port); Socket second = socket(port)) {
                second.setSoTimeout(2_000);
                assertEquals(-1, second.getInputStream().read());
                assertTrue(first.isConnected());
            }
        }
    }

    @Test
    void closesConnectionWhenPerIpMessageRateIsExceeded() {
        AtomicLong nanos = new AtomicLong();
        ConnectionAdmissionHandler.AdmissionTracker tracker =
                new ConnectionAdmissionHandler.AdmissionTracker(10, 2, nanos::get);
        EmbeddedChannel channel = new EmbeddedChannel(new MessageRateLimitHandler(tracker));

        assertTrue(channel.writeInbound(frame(0x0002)));
        release(channel.readInbound());
        assertTrue(channel.writeInbound(frame(0x0002)));
        release(channel.readInbound());
        assertFalse(channel.writeInbound(frame(0x0002)));
        assertFalse(channel.isActive());
        channel.finishAndReleaseAll();
    }

    @Test
    void retainsRateWindowAcrossReconnectThenReleasesItAfterWindowExpires() {
        AtomicLong nanos = new AtomicLong();
        ConnectionAdmissionHandler.AdmissionTracker tracker =
                new ConnectionAdmissionHandler.AdmissionTracker(1, 1, nanos::get);
        String remoteIp = "192.0.2.10";
        assertTrue(tracker.tryAcquire(remoteIp));
        assertTrue(tracker.allowMessage(remoteIp));
        assertFalse(tracker.allowMessage(remoteIp));

        tracker.release(remoteIp);
        assertTrue(tracker.tryAcquire(remoteIp));

        assertFalse(tracker.allowMessage(remoteIp));
        nanos.addAndGet(Duration.ofSeconds(1).toNanos());
        assertTrue(tracker.allowMessage(remoteIp));
        tracker.release(remoteIp);
    }

    @Test
    void laterConnectionsPruneExpiredWindowsForInactiveIps() {
        AtomicLong nanos = new AtomicLong();
        ConnectionAdmissionHandler.AdmissionTracker tracker =
                new ConnectionAdmissionHandler.AdmissionTracker(1, 1, nanos::get);
        assertTrue(tracker.tryAcquire("192.0.2.10"));
        assertTrue(tracker.allowMessage("192.0.2.10"));
        tracker.release("192.0.2.10");
        assertEquals(1, tracker.trackedRateWindows());

        nanos.addAndGet(Duration.ofSeconds(1).toNanos());
        assertTrue(tracker.tryAcquire("192.0.2.11"));

        assertEquals(0, tracker.trackedRateWindows());
        tracker.release("192.0.2.11");
    }


    @Test
    void pausesAtHighWatermarkAndResumesBelowLowWatermark() {
        AtomicInteger pending = new AtomicInteger(80);
        AtomicLong nanos = new AtomicLong();
        BusinessQueueBackpressureHandler handler = new BusinessQueueBackpressureHandler(
                pending::get, 80, 40, Duration.ofSeconds(5), nanos::get);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        ByteBuf message = Unpooled.buffer(1).writeByte(1);
        assertTrue(channel.writeInbound(message));
        assertFalse(channel.config().isAutoRead());
        ((ByteBuf) channel.readInbound()).release();

        pending.set(39);
        nanos.addAndGet(Duration.ofMillis(20).toNanos());
        channel.advanceTimeBy(20, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        assertTrue(channel.config().isAutoRead());
        channel.finishAndReleaseAll();
    }

    @Test
    void closesConnectionWhenBusinessQueueRemainsCongested() {
        AtomicInteger pending = new AtomicInteger(80);
        AtomicLong nanos = new AtomicLong();
        BusinessQueueBackpressureHandler handler = new BusinessQueueBackpressureHandler(
                pending::get, 80, 40, Duration.ofSeconds(5), nanos::get);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        assertTrue(channel.writeInbound(Unpooled.buffer(1).writeByte(1)));
        ((ByteBuf) channel.readInbound()).release();

        nanos.addAndGet(Duration.ofSeconds(6).toNanos());
        channel.advanceTimeBy(20, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        assertFalse(channel.isActive());
        channel.finishAndReleaseAll();
    }

    @Test
    void invokesBlockingRegistryPortOnlyOnBoundedBusinessWorker() throws Exception {
        ThreadCapturingRegistry registry = new ThreadCapturingRegistry();
        try (JtGatewayServer server = new JtGatewayServer(
                configuration(4, 100), registry, new TerminalSessionRegistry(),
                reporter(registry))) {
            int port = server.start();
            try (Socket terminal = socket(port)) {
                terminal.getOutputStream().write(registrationPacket());
                terminal.getOutputStream().flush();
                assertTrue(registry.registrationCalled.await(3, TimeUnit.SECONDS));
                assertTrue(registry.threadName.startsWith("jt-business"), registry.threadName);
                assertFalse(registry.threadName.contains("nioEventLoop"), registry.threadName);
            }
        }
    }

    @Test
    void keepsTwoPhysicalSessionsForOneVehicleAndTakesOverOnlyTheSameTerminal()
            throws Exception {
        TerminalSessionRegistry sessions = new TerminalSessionRegistry();
        DualIdentityRegistry registry = new DualIdentityRegistry();
        try (JtGatewayServer server = new JtGatewayServer(
                configuration(4, 100), registry, sessions, reporter(registry))) {
            int port = server.start();
            try (Socket dispatch = socket(port);
                    Socket recorder = socket(port);
                    Socket dispatchReplacement = socket(port)) {
                dispatch.getOutputStream().write(authenticationPacket(
                        "123456789012", AUTHENTICATION_TOKEN));
                dispatch.getOutputStream().flush();
                recorder.getOutputStream().write(authenticationPacket(
                        "999999999999", AUTHENTICATION_TOKEN));
                recorder.getOutputStream().flush();
                await(() -> sessions.current(TERMINAL_ID).isPresent()
                                && sessions.current(PEER_TERMINAL_ID).isPresent(),
                        Duration.ofSeconds(3));

                TerminalSession firstDispatch = sessions.current(TERMINAL_ID).orElseThrow();
                TerminalSession recorderSession = sessions.current(PEER_TERMINAL_ID).orElseThrow();
                assertEquals(TerminalSessionState.AUTHENTICATED, firstDispatch.state());
                assertEquals(TerminalSessionState.AUTHENTICATED, recorderSession.state());
                assertEquals(ONBOARD_SYSTEM_ID, firstDispatch.onboardSystemId());
                assertEquals(ONBOARD_SYSTEM_ID, recorderSession.onboardSystemId());
                assertEquals(VEHICLE_ID, firstDispatch.vehicleId());
                assertEquals(VEHICLE_ID, recorderSession.vehicleId());
                assertEquals(Set.of("DISPATCH", "LOCATION_PRIMARY"), firstDispatch.roles());
                assertEquals(Set.of("LOCATION_BACKUP", "ACTIVE_SAFETY", "VIDEO"),
                        recorderSession.roles());
                assertCurrentFiniteLeases(registry.issuedLeases, 2);
                assertNotEquals(
                        registry.issuedLeases.get(0).owner().connectionId(),
                        registry.issuedLeases.get(1).owner().connectionId(),
                        "physical terminals must own isolated leases");

                dispatchReplacement.getOutputStream().write(authenticationPacket(
                        "123456789012", AUTHENTICATION_TOKEN));
                dispatchReplacement.getOutputStream().flush();
                await(() -> sessions.current(TERMINAL_ID)
                                .filter(current -> current != firstDispatch)
                                .isPresent(),
                        Duration.ofSeconds(3));
                await(() -> !firstDispatch.channel().isActive(), Duration.ofSeconds(3));

                TerminalSession replacement = sessions.current(TERMINAL_ID).orElseThrow();
                assertEquals(TerminalSessionState.CLOSED, firstDispatch.state());
                assertFalse(firstDispatch.channel().isActive());
                assertEquals(TerminalSessionState.AUTHENTICATED, replacement.state());
                assertTrue(replacement.channel().isActive());
                assertSame(recorderSession, sessions.current(PEER_TERMINAL_ID).orElseThrow());
                assertTrue(recorderSession.channel().isActive());
                assertCurrentFiniteLeases(registry.issuedLeases, 3);
                assertEquals(TERMINAL_ID, registry.issuedLeases.get(2).owner().terminalId());
                assertNotEquals(
                        registry.issuedLeases.get(0).owner().connectionId(),
                        registry.issuedLeases.get(2).owner().connectionId(),
                        "same-terminal takeover must be fenced by a new connection owner");
            }
        }
    }

    @Test
    void closesAcceptedConnectionsBeforeWorkerExecutors() throws Exception {
        Logger nettyContextLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> warnings = new ListAppender<>();
        warnings.start();
        nettyContextLogger.addAppender(warnings);
        ApprovingRegistry registry = new ApprovingRegistry();
        JtGatewayServer server = new JtGatewayServer(
                configuration(4, 100), registry, new TerminalSessionRegistry(),
                reporter(registry));
        try {
            int port = server.start();
            try (Socket terminal = socket(port)) {
                await(() -> server.activeConnections() == 1, Duration.ofSeconds(2));

                server.close();

                terminal.setSoTimeout(2_000);
                assertEquals(-1, terminal.getInputStream().read());
                assertEquals(0, server.activeConnections());
            }
        } finally {
            server.close();
            nettyContextLogger.detachAppender(warnings);
        }
        assertTrue(warnings.list.stream().noneMatch(event ->
                        event.getThrowableProxy() != null
                                && java.util.concurrent.RejectedExecutionException.class.getName()
                                .equals(event.getThrowableProxy().getClassName())),
                () -> "shutdown emitted rejected pipeline events: " + warnings.list);
    }

    @Test
    void shutsDownWithoutRejectedEventsWhenPeerClosesFirst() throws Exception {
        Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> warnings = new ListAppender<>();
        warnings.start();
        rootLogger.addAppender(warnings);
        BlockingRegistrationRegistry registry = new BlockingRegistrationRegistry();
        JtGatewayServer server = new JtGatewayServer(
                configuration(4, 100), registry, new TerminalSessionRegistry(),
                reporter(registry));
        try {
            int port = server.start();
            Socket terminal = socket(port);
            await(() -> server.activeConnections() == 1, Duration.ofSeconds(2));
            terminal.getOutputStream().write(registrationPacket());
            terminal.getOutputStream().flush();
            assertTrue(registry.registrationEntered.await(2, TimeUnit.SECONDS));
            terminal.close();
            await(() -> server.activeConnections() == 0, Duration.ofSeconds(2));
            registry.allowRegistrationToReturn.countDown();

            server.close();
        } finally {
            registry.allowRegistrationToReturn.countDown();
            server.close();
            rootLogger.detachAppender(warnings);
        }
        assertTrue(warnings.list.stream().noneMatch(event ->
                        event.getThrowableProxy() != null
                                && java.util.concurrent.RejectedExecutionException.class.getName()
                                .equals(event.getThrowableProxy().getClassName())),
                () -> "shutdown emitted rejected pipeline events: " + warnings.list);
    }

    @Test
    void closeReturnsWithinBoundWhenRegistryCallDoesNotReturn() throws Exception {
        BlockingRegistrationRegistry registry = new BlockingRegistrationRegistry();
        JtGatewayServer server = new JtGatewayServer(
                configuration(4, 100), registry, new TerminalSessionRegistry(),
                reporter(registry));
        CompletableFuture<Void> closing = null;
        try {
            int port = server.start();
            Socket terminal = socket(port);
            terminal.getOutputStream().write(registrationPacket());
            terminal.getOutputStream().flush();
            assertTrue(registry.registrationEntered.await(2, TimeUnit.SECONDS));

            closing = CompletableFuture.runAsync(server::close);
            CompletableFuture<Void> closeAttempt = closing;
            assertDoesNotThrow(() -> closeAttempt.get(4, TimeUnit.SECONDS));
            terminal.close();
        } finally {
            registry.allowRegistrationToReturn.countDown();
            if (closing != null) {
                closing.get(4, TimeUnit.SECONDS);
            }
            server.close();
        }
    }

    private static JtGatewayServer.Configuration configuration(int perIpConnections, int messagesPerSecond) {
        return new JtGatewayServer.Configuration(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                perIpConnections,
                messagesPerSecond,
                1,
                256,
                80,
                40,
                Duration.ofSeconds(5));
    }

    private static Socket socket(int port) throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2_000);
        return socket;
    }

    private static Jt808Frame frame(int messageId) {
        return new Jt808Frame(new Jt808MessageHeader(
                messageId, 0, 0, 0, false, ProtocolVersion.JT808_2013,
                0, "123456789012", 1, null, null), Unpooled.EMPTY_BUFFER, (byte) 0);
    }

    private static byte[] registrationPacket() {
        ByteBuf body = Unpooled.buffer();
        body.writeShort(62).writeShort(621);
        writeFixedAscii(body, "MFG01", 5);
        writeFixedAscii(body, "PILOT-MODEL", 20);
        writeFixedAscii(body, "TERM001", 7);
        body.writeByte(1);
        body.writeCharSequence("PILOT-A", StandardCharsets.US_ASCII);
        Jt808Frame frame = new Jt808Frame(new Jt808MessageHeader(
                0x0100, body.readableBytes(), body.readableBytes(), 0, false,
                ProtocolVersion.JT808_2013, 0, "123456789012", 1, null, null),
                body, (byte) 0);
        EmbeddedChannel encoder = new EmbeddedChannel(new Jt808FrameEncoder());
        try {
            assertTrue(encoder.writeOutbound(frame));
            ByteBuf encoded = encoder.readOutbound();
            try {
                byte[] packet = new byte[encoded.readableBytes()];
                encoded.readBytes(packet);
                return packet;
            } finally {
                encoded.release();
            }
        } finally {
            release(frame);
            encoder.finishAndReleaseAll();
        }
    }

    private static byte[] authenticationPacket(String terminalIdentity, String token) {
        byte[] tokenBytes = token.getBytes(StandardCharsets.US_ASCII);
        Jt808Frame frame = new Jt808Frame(new Jt808MessageHeader(
                0x0102, tokenBytes.length, tokenBytes.length, 0, false,
                ProtocolVersion.JT808_2013, 0, terminalIdentity, 1, null, null),
                Unpooled.wrappedBuffer(tokenBytes), (byte) 0);
        EmbeddedChannel encoder = new EmbeddedChannel(new Jt808FrameEncoder());
        try {
            assertTrue(encoder.writeOutbound(frame));
            ByteBuf encoded = encoder.readOutbound();
            try {
                byte[] packet = new byte[encoded.readableBytes()];
                encoded.readBytes(packet);
                return packet;
            } finally {
                encoded.release();
            }
        } finally {
            release(frame);
            encoder.finishAndReleaseAll();
        }
    }

    private static TerminalSessionContext context(
            UUID terminalId, Set<String> roles) {
        return new TerminalSessionContext(
                2,
                terminalId,
                ONBOARD_SYSTEM_ID,
                VEHICLE_ID,
                4,
                roles,
                "WGS84",
                new TerminalSessionContext.SessionProtocolProfile(
                        "JT808_2013",
                        roles.contains("DISPATCH") ? "VENDOR_DISPATCH" : "NONE",
                        "NONE",
                        roles.contains("VIDEO") ? "JT1078_2016" : "NONE",
                        List.of(), 30, 60),
                null,
                List.of(),
                5);
    }

    private static void writeFixedAscii(ByteBuf target, String value, int length) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        target.writeBytes(bytes);
        target.writeZero(length - bytes.length);
    }

    private static void release(Jt808Frame frame) {
        if (frame != null && frame.body().refCnt() > 0) {
            frame.body().release();
        }
    }

    private static void assertOrdered(List<String> names, String... expected) {
        int previous = -1;
        for (String name : expected) {
            int current = names.indexOf(name);
            assertTrue(current > previous, () -> name + " order in " + names);
            previous = current;
        }
    }

    private static void await(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                fail("condition was not met within " + timeout);
            }
            Thread.onSpinWait();
        }
    }

    private static SessionLeaseReporter reporter(TerminalRegistryPort registry) {
        return new SessionLeaseReporter(registry, Runnable::run, TEST_RUNTIME_CLOCK);
    }

    private static AuthenticationDecision approvedAuthentication(
            TerminalSessionContext context, UUID connectionId) {
        // 与被测runtime使用同一UTC时间源即时签发；保持180秒有限有效期与owner fencing。
        Instant now = TEST_RUNTIME_CLOCK.instant();
        return AuthenticationDecision.allow(
                context,
                new TerminalRegistryPort.SessionLeaseGrant(
                        new TerminalRegistryPort.SessionLeaseOwner(
                                context.terminalId(), "gateway-server-test", connectionId,
                                context.tokenVersion(), 1),
                        now, now, now.plus(TEST_LEASE_DURATION)));
    }

    private static void assertCurrentFiniteLeases(
            List<TerminalRegistryPort.SessionLeaseGrant> leases, int expectedCount) {
        assertEquals(expectedCount, leases.size());
        Instant assertedAt = TEST_RUNTIME_CLOCK.instant();
        for (TerminalRegistryPort.SessionLeaseGrant lease : leases) {
            assertTrue(lease.expiresAt().isAfter(assertedAt),
                    "a freshly authenticated session lease must still be current");
            assertEquals(TEST_LEASE_DURATION,
                    Duration.between(lease.lastValidMessageAt(), lease.expiresAt()),
                    "test leases must stay finite and retain the production renewal window");
            assertTrue(lease.owner().leaseGeneration() > 0,
                    "lease owners must retain a positive fencing generation");
        }
    }

    private static class ApprovingRegistry implements TerminalRegistryPort {
        @Override
        public RegistrationDecision verifyRegistration(TerminalRegistrationIdentity identity) {
            byte[] token = "PIPELINE-TEST-TOKEN".getBytes(StandardCharsets.US_ASCII);
            return RegistrationDecision.approved(
                    context(TERMINAL_ID, Set.of("LOCATION_PRIMARY")),
                    token,
                    sha256(token));
        }

        @Override
        public AuthenticationDecision verifyAuthentication(
                UUID terminalId,
                int tokenVersion,
                String presentedTokenSha256,
                UUID connectionId) {
            return approvedAuthentication(
                    context(terminalId, Set.of("LOCATION_PRIMARY")), connectionId);
        }

        @Override
        public AuthenticationDecision verifyAuthenticationByIdentity(
                ProtocolVersion protocolVersion,
                String terminalPhone,
                String presentedTokenSha256,
                UUID connectionId) {
            return AuthenticationDecision.rejected(
                    com.idavy.drtops.jtgateway.session.AuthenticationRejection.TOKEN_MISMATCH);
        }

        @Override
        public Optional<SessionLeaseGrant> renewSessionLease(SessionLeaseOwner owner) {
            return Optional.empty();
        }

        @Override
        public SessionLeaseReleaseResult releaseSessionLease(
                SessionLeaseOwner owner, String reasonCode) {
            return new SessionLeaseReleaseResult("STALE_OWNER_IGNORED");
        }

        @Override
        public void recordSessionAudit(SessionAuditIngress event) {
        }
    }

    private static final class DualIdentityRegistry extends ApprovingRegistry {
        private final List<SessionLeaseGrant> issuedLeases = new CopyOnWriteArrayList<>();
        private final Map<String, TerminalSessionContext> contexts = Map.of(
                "123456789012",
                context(TERMINAL_ID, Set.of("DISPATCH", "LOCATION_PRIMARY")),
                "999999999999",
                context(PEER_TERMINAL_ID,
                        Set.of("LOCATION_BACKUP", "ACTIVE_SAFETY", "VIDEO")));

        @Override
        public AuthenticationDecision verifyAuthenticationByIdentity(
                ProtocolVersion protocolVersion,
                String terminalPhone,
                String presentedTokenSha256,
                UUID connectionId) {
            TerminalSessionContext context = contexts.get(terminalPhone);
            if (protocolVersion != ProtocolVersion.JT808_2013
                    || context == null
                    || !sha256(AUTHENTICATION_TOKEN.getBytes(StandardCharsets.US_ASCII))
                            .equals(presentedTokenSha256)) {
                return AuthenticationDecision.rejected(
                        com.idavy.drtops.jtgateway.session.AuthenticationRejection.TOKEN_MISMATCH);
            }
            AuthenticationDecision decision = approvedAuthentication(context, connectionId);
            issuedLeases.add(decision.lease());
            return decision;
        }
    }

    private static final class ThreadCapturingRegistry extends ApprovingRegistry {
        private final CountDownLatch registrationCalled = new CountDownLatch(1);
        private volatile String threadName = "not-called";

        @Override
        public RegistrationDecision verifyRegistration(TerminalRegistrationIdentity identity) {
            threadName = Thread.currentThread().getName();
            try {
                return super.verifyRegistration(identity);
            } finally {
                registrationCalled.countDown();
            }
        }
    }

    private static final class BlockingRegistrationRegistry extends ApprovingRegistry {
        private final CountDownLatch registrationEntered = new CountDownLatch(1);
        private final CountDownLatch allowRegistrationToReturn = new CountDownLatch(1);

        @Override
        public RegistrationDecision verifyRegistration(TerminalRegistrationIdentity identity) {
            registrationEntered.countDown();
            try {
                allowRegistrationToReturn.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
            return super.verifyRegistration(identity);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
