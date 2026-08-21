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
import com.idavy.drtops.jtgateway.session.TerminalRegistrationIdentity;
import com.idavy.drtops.jtgateway.session.TerminalRegistryPort;
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
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class JtGatewayServerIntegrationTest {
    private static final UUID TERMINAL_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void buildsHandlersInSecurityAndProtocolOrderWithReaderIdleTimeout() {
        DefaultEventLoopGroup businessWorkers = new DefaultEventLoopGroup(1);
        try {
            JtChannelInitializer initializer = new JtChannelInitializer(
                    new ConnectionAdmissionHandler.AdmissionTracker(4, 100),
                    new ApprovingRegistry(),
                    new TerminalSessionRegistry(),
                    businessWorkers,
                    () -> 0,
                    Clock.systemUTC(),
                    80,
                    40,
                    Duration.ofSeconds(5));
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
        try (JtGatewayServer server = new JtGatewayServer(
                configuration, new ApprovingRegistry(), new TerminalSessionRegistry())) {
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
                configuration(4, 100), registry, new TerminalSessionRegistry())) {
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
    void closesAcceptedConnectionsBeforeWorkerExecutors() throws Exception {
        Logger nettyContextLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> warnings = new ListAppender<>();
        warnings.start();
        nettyContextLogger.addAppender(warnings);
        JtGatewayServer server = new JtGatewayServer(
                configuration(4, 100), new ApprovingRegistry(), new TerminalSessionRegistry());
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
                configuration(4, 100), registry, new TerminalSessionRegistry());
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
                configuration(4, 100), registry, new TerminalSessionRegistry());
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

    private static class ApprovingRegistry implements TerminalRegistryPort {
        @Override
        public RegistrationDecision verifyRegistration(TerminalRegistrationIdentity identity) {
            byte[] token = "PIPELINE-TEST-TOKEN".getBytes(StandardCharsets.US_ASCII);
            return RegistrationDecision.approved(
                    TERMINAL_ID,
                    UUID.fromString("33333333-3333-3333-3333-333333333333"),
                    "WGS84",
                    1,
                    token,
                    sha256(token));
        }

        @Override
        public AuthenticationDecision verifyAuthentication(
                UUID terminalId, int tokenVersion, String presentedTokenSha256) {
            return AuthenticationDecision.allow();
        }

        @Override
        public void recordSessionAudit(SessionAuditIngress event) {
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
