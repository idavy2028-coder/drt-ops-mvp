package com.idavy.drtops.jtgateway.session;

import com.idavy.drtops.jt.protocol.codec.Jt808Frame;
import com.idavy.drtops.jt.protocol.codec.Jt808MessageHeader;
import com.idavy.drtops.jt.protocol.codec.ProtocolVersion;
import com.idavy.drtops.jtgateway.netty.JtFrameOwnershipHandler;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.buffer.UnpooledHeapByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RegistrationAuthenticationHandlerTest {
    private static final UUID TERMINAL_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PEER_TERMINAL_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ONBOARD_SYSTEM_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID VEHICLE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String TERMINAL_NUMBER = "123456789012";
    private static final String JT808_2019_TERMINAL_NUMBER = "00000000123456789012";
    private static final String TERMINAL_NUMBER_DIGEST =
            "f72828e7de880d048453dbfc9c96bed75540e613649a95f119ba0caa4e1119d9";
    private static final String AUTHENTICATION_TOKEN = "PILOT-TEST-TOKEN";

    @TempDir
    Path temporaryDirectory;

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
    void rejectsBlankManufacturerBeforeCallingOperationsApi() {
        assertBlankRegistrationFieldRejected(
                registrationFrame("", "PILOT-MODEL", "TERM001", "PILOT-A"),
                "REGISTRATION_MANUFACTURER_EMPTY");
    }

    @Test
    void rejectsBlankTerminalPhoneBeforeCallingOperationsApi() {
        assertBlankRegistrationFieldRejected(
                registrationFrame(""),
                "REGISTRATION_TERMINAL_PHONE_EMPTY");
    }

    @Test
    void rejectsBlankModelBeforeCallingOperationsApi() {
        assertBlankRegistrationFieldRejected(
                registrationFrame("MFG01", "", "TERM001", "PILOT-A"),
                "REGISTRATION_MODEL_EMPTY");
    }

    @Test
    void rejectsBlankTerminalCodeBeforeCallingOperationsApi() {
        assertBlankRegistrationFieldRejected(
                registrationFrame("MFG01", "PILOT-MODEL", "", "PILOT-A"),
                "REGISTRATION_TERMINAL_CODE_EMPTY");
    }

    @Test
    void rejectsBlankVehicleIdentifierBeforeCallingOperationsApi() {
        assertBlankRegistrationFieldRejected(
                registrationFrame("MFG01", "PILOT-MODEL", "TERM001", ""),
                "REGISTRATION_VEHICLE_IDENTIFIER_EMPTY");
    }

    @Test
    void maintenanceAllowlistRejectsNonTargetBeforeCallingOperationsApi() {
        MutableClock clock = new MutableClock();
        FakeTerminalRegistry port = FakeTerminalRegistry.approved();
        RegistrationMaintenancePolicy policy = RegistrationMaintenancePolicy.enabled(
                TERMINAL_NUMBER_DIGEST,
                clock.instant().plus(Duration.ofMinutes(10)),
                Duration.ofSeconds(60),
                clock);
        RegistrationAuthenticationHandler handler = new RegistrationAuthenticationHandler(
                port, new TerminalSessionRegistry(), clock, Duration.ofSeconds(30), policy);
        EmbeddedChannel channel = channel(handler);

        assertFalse(channel.writeInbound(registrationFrame("999999999999")));

        assertEquals(0, port.registrationAttempts());
        assertFalse(channel.isActive());
        assertEquals("TEMPORARILY_BLOCKED_FOR_MAINTENANCE", port.lastAudit().reasonCode());
        assertFalse(port.lastAudit().terminalAlias().contains("999999999999"));
        Jt808Frame response = channel.readOutbound();
        assertEquals(0x8100, response.header().messageId());
        assertEquals(1, response.body().getUnsignedByte(2));
        release(response);
        channel.finishAndReleaseAll();
    }

    @Test
    void maintenanceAllowlistLetsTargetEnterExistingRegistrationFlow() {
        MutableClock clock = new MutableClock();
        FakeTerminalRegistry port = FakeTerminalRegistry.approved();
        RegistrationMaintenancePolicy policy = RegistrationMaintenancePolicy.enabled(
                TERMINAL_NUMBER_DIGEST,
                clock.instant().plus(Duration.ofMinutes(10)),
                Duration.ofSeconds(60),
                clock);
        RegistrationAuthenticationHandler handler = new RegistrationAuthenticationHandler(
                port, new TerminalSessionRegistry(), clock, Duration.ofSeconds(30), policy);
        EmbeddedChannel channel = channel(handler);

        assertFalse(channel.writeInbound(registrationFrame()));

        assertEquals(1, port.registrationAttempts());
        assertTrue(channel.isActive());
        Jt808Frame response = channel.readOutbound();
        assertEquals(0, response.body().getUnsignedByte(2));
        release(response);
        channel.finishAndReleaseAll();
    }

    @Test
    void retriesMaintenanceAuditAfterPersistenceFailureBeforeReplying() {
        MutableClock clock = new MutableClock();
        IllegalStateException persistenceFailure = new IllegalStateException("synthetic audit persistence failure");
        FakeTerminalRegistry port = FakeTerminalRegistry.approved();
        port.failNextAudit(persistenceFailure);
        RegistrationMaintenancePolicy policy = RegistrationMaintenancePolicy.enabled(
                TERMINAL_NUMBER_DIGEST,
                clock.instant().plus(Duration.ofMinutes(10)),
                Duration.ofSeconds(60),
                clock);
        Jt808Frame firstRegistration = registrationFrame("999999999999");
        EmbeddedChannel first = channel(new RegistrationAuthenticationHandler(
                port, new TerminalSessionRegistry(), clock, Duration.ofSeconds(30), policy));

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> first.writeInbound(firstRegistration));

        assertSame(persistenceFailure, thrown);
        assertEquals(0, firstRegistration.body().refCnt());
        assertFalse(first.isActive());
        assertNull(first.readOutbound(), "a non-durable maintenance rejection must not be acknowledged");
        assertEquals(1, port.auditAttempts());

        EmbeddedChannel retry = channel(new RegistrationAuthenticationHandler(
                port, new TerminalSessionRegistry(), clock, Duration.ofSeconds(30), policy));
        assertFalse(retry.writeInbound(registrationFrame("999999999999")));

        assertEquals(2, port.auditAttempts(),
                "a failed append must not consume the maintenance audit interval");
        assertEquals(1, port.auditCount());
        assertFalse(retry.isActive());
        Jt808Frame rejection = retry.readOutbound();
        assertNotNull(rejection);
        assertEquals(1, rejection.body().getUnsignedByte(2));
        release(rejection);
        first.finishAndReleaseAll();
        retry.finishAndReleaseAll();
    }

    @Test
    void pendingMaintenanceAuditFailsConcurrentConnectionClosedAndRetriesAfterFailure() throws Exception {
        MutableClock clock = new MutableClock();
        FakeTerminalRegistry port = FakeTerminalRegistry.approved();
        RegistrationMaintenancePolicy policy = RegistrationMaintenancePolicy.enabled(
                TERMINAL_NUMBER_DIGEST,
                clock.instant().plus(Duration.ofMinutes(10)),
                Duration.ofSeconds(60),
                clock);
        IllegalStateException persistenceFailure = new IllegalStateException(
                "synthetic blocked maintenance audit failure");
        AuditBlock blockedAudit = port.blockNextAudit(
                SessionAuditType.REGISTRATION_REJECTED, persistenceFailure);
        RegistrationAuthenticationHandler handlerA = new RegistrationAuthenticationHandler(
                port, new TerminalSessionRegistry(), clock, Duration.ofSeconds(30), policy);
        RegistrationAuthenticationHandler handlerB = new RegistrationAuthenticationHandler(
                port, new TerminalSessionRegistry(), clock, Duration.ofSeconds(30), policy);
        EmbeddedChannel channelA = channel(handlerA);
        EmbeddedChannel channelB = channel(handlerB);
        Jt808Frame frameA = registrationFrame("999999999999");
        Jt808Frame frameB = registrationFrame("888888888888");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Boolean> requestA = executor.submit(() -> channelA.writeInbound(frameA));

        try {
            assertTrue(blockedAudit.awaitEntered(Duration.ofSeconds(5)));

            assertFalse(channelB.writeInbound(frameB));
            assertEquals(0, frameB.body().refCnt());
            assertFalse(channelB.isActive());
            assertEquals(TerminalSessionState.CLOSED, handlerB.session().state());
            assertNull(channelB.readOutbound(),
                    "an in-flight audit reservation is not evidence that permits a reply");

            blockedAudit.release();
            ExecutionException failed = assertThrows(ExecutionException.class, requestA::get);
            assertSame(persistenceFailure, failed.getCause());
            assertEquals(0, frameA.body().refCnt());
            assertFalse(channelA.isActive());
            assertNull(channelA.readOutbound());

            RegistrationAuthenticationHandler handlerC = new RegistrationAuthenticationHandler(
                    port, new TerminalSessionRegistry(), clock, Duration.ofSeconds(30), policy);
            EmbeddedChannel channelC = channel(handlerC);
            try {
                assertFalse(channelC.writeInbound(registrationFrame("777777777777")));
                assertEquals(2, port.auditAttempts(),
                        "reservation failure must let the next connection retry durable audit");
                Jt808Frame rejection = channelC.readOutbound();
                assertNotNull(rejection);
                release(rejection);
            } finally {
                drainOutbound(channelC);
                channelC.finishAndReleaseAll();
            }
        } finally {
            blockedAudit.release();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            if (frameA.body().refCnt() > 0) {
                release(frameA);
            }
            if (frameB.body().refCnt() > 0) {
                release(frameB);
            }
            drainOutbound(channelA);
            drainOutbound(channelB);
            channelA.finishAndReleaseAll();
            channelB.finishAndReleaseAll();
        }
    }

    @Test
    void pendingMaintenanceAuditFailsConcurrentConnectionClosedThenSafelySuppressesAfterSuccess()
            throws Exception {
        MutableClock clock = new MutableClock();
        FakeTerminalRegistry port = FakeTerminalRegistry.approved();
        RegistrationMaintenancePolicy policy = RegistrationMaintenancePolicy.enabled(
                TERMINAL_NUMBER_DIGEST,
                clock.instant().plus(Duration.ofMinutes(10)),
                Duration.ofSeconds(60),
                clock);
        AuditBlock blockedAudit = port.blockNextAudit(SessionAuditType.REGISTRATION_REJECTED, null);
        RegistrationAuthenticationHandler handlerA = new RegistrationAuthenticationHandler(
                port, new TerminalSessionRegistry(), clock, Duration.ofSeconds(30), policy);
        RegistrationAuthenticationHandler handlerB = new RegistrationAuthenticationHandler(
                port, new TerminalSessionRegistry(), clock, Duration.ofSeconds(30), policy);
        EmbeddedChannel channelA = channel(handlerA);
        EmbeddedChannel channelB = channel(handlerB);
        Jt808Frame frameA = registrationFrame("999999999999");
        Jt808Frame frameB = registrationFrame("888888888888");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Boolean> requestA = executor.submit(() -> channelA.writeInbound(frameA));

        try {
            assertTrue(blockedAudit.awaitEntered(Duration.ofSeconds(5)));

            assertFalse(channelB.writeInbound(frameB));
            assertEquals(0, frameB.body().refCnt());
            assertFalse(channelB.isActive());
            assertNull(channelB.readOutbound(),
                    "pending persistence must fail closed without a protocol reply");

            blockedAudit.release();
            assertFalse(requestA.get(5, TimeUnit.SECONDS));
            Jt808Frame firstRejection = channelA.readOutbound();
            assertNotNull(firstRejection);
            release(firstRejection);

            RegistrationAuthenticationHandler handlerC = new RegistrationAuthenticationHandler(
                    port, new TerminalSessionRegistry(), clock, Duration.ofSeconds(30), policy);
            EmbeddedChannel channelC = channel(handlerC);
            try {
                assertFalse(channelC.writeInbound(registrationFrame("777777777777")));
                assertEquals(1, port.auditAttempts(),
                        "a durable audit may safely suppress repeats during the configured interval");
                Jt808Frame suppressedRejection = channelC.readOutbound();
                assertNotNull(suppressedRejection);
                release(suppressedRejection);
            } finally {
                drainOutbound(channelC);
                channelC.finishAndReleaseAll();
            }
        } finally {
            blockedAudit.release();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            if (frameA.body().refCnt() > 0) {
                release(frameA);
            }
            if (frameB.body().refCnt() > 0) {
                release(frameB);
            }
            drainOutbound(channelA);
            drainOutbound(channelB);
            channelA.finishAndReleaseAll();
            channelB.finishAndReleaseAll();
        }
    }

    @Test
    void rejectsLegacyRegistrationBodyUnderJt8082019HeaderByDefault() {
        FakeTerminalRegistry port = FakeTerminalRegistry.approved();
        EmbeddedChannel channel = channel(
                port, new TerminalSessionRegistry(), new MutableClock());

        assertFalse(channel.writeInbound(jt8082019LegacyRegistrationFrame(
                "MFG01", "PILOT-MODEL", "TERM001", "PILOT-A")));

        assertEquals(0, port.registrationAttempts(),
                "JT808-2019 layout must not be guessed from a legacy-sized body");
        assertFalse(channel.isActive());
        assertEquals(SessionAuditType.REGISTRATION_REJECTED, port.lastAudit().type());
        assertTrue(port.lastAudit().reasonCode().startsWith(
                "MALFORMED_REGISTRATION_BODY_TOO_SHORT_JT808_2019"));
        Jt808Frame response = channel.readOutbound();
        assertEquals(0x8100, response.header().messageId());
        assertEquals(2, response.body().getUnsignedByte(2));
        release(response);
        channel.finishAndReleaseAll();
    }

    @Test
    void allowsLegacyRegistrationBodyOnlyForExactConfiguredProtocolIdentity() throws Exception {
        String allowedDigest = sha256((ProtocolVersion.JT808_2019.name() + '\0'
                + JT808_2019_TERMINAL_NUMBER).getBytes(StandardCharsets.UTF_8));
        RegistrationBodyLayoutPolicy layoutPolicy =
                RegistrationBodyLayoutPolicy.fromCommaSeparated(allowedDigest);
        FakeTerminalRegistry allowedPort = FakeTerminalRegistry.approved();
        RegistrationAuthenticationHandler allowedHandler = new RegistrationAuthenticationHandler(
                allowedPort,
                new TerminalSessionRegistry(),
                new MutableClock(),
                Duration.ofSeconds(30),
                RegistrationMaintenancePolicy.disabled(),
                PrivateVehicleIdentifierCapture.disabled(),
                layoutPolicy);
        EmbeddedChannel allowed = channel(allowedHandler);

        assertFalse(allowed.writeInbound(jt8082019LegacyRegistrationFrame(
                "MFG01", "PILOT-MODEL", "TERM001", "PILOT-A")));

        assertEquals(1, allowedPort.registrationAttempts());
        Jt808Frame accepted = allowed.readOutbound();
        assertNotNull(accepted);
        assertEquals(0, accepted.body().getUnsignedByte(2));
        release(accepted);

        FakeTerminalRegistry otherPort = FakeTerminalRegistry.approved();
        RegistrationAuthenticationHandler otherHandler = new RegistrationAuthenticationHandler(
                otherPort,
                new TerminalSessionRegistry(),
                new MutableClock(),
                Duration.ofSeconds(30),
                RegistrationMaintenancePolicy.disabled(),
                PrivateVehicleIdentifierCapture.disabled(),
                layoutPolicy);
        EmbeddedChannel other = channel(otherHandler);
        assertFalse(other.writeInbound(jt8082019LegacyRegistrationFrame(
                "MFG01", "PILOT-MODEL", "TERM001", "PILOT-A", "00000000999999999999")));

        assertEquals(0, otherPort.registrationAttempts(),
                "an exact compatibility rule must not apply to another protocol identity");
        assertFalse(other.isActive());
        drainOutbound(other);
        allowed.finishAndReleaseAll();
        other.finishAndReleaseAll();
    }

    @Test
    void keepsRegistryMismatchRejectionForExplicitLegacyCompatibilityIdentity() throws Exception {
        FakeTerminalRegistry port = FakeTerminalRegistry.rejected(RegistrationRejection.MODEL_MISMATCH);
        String allowedDigest = sha256((ProtocolVersion.JT808_2019.name() + '\0'
                + JT808_2019_TERMINAL_NUMBER).getBytes(StandardCharsets.UTF_8));
        RegistrationAuthenticationHandler handler = new RegistrationAuthenticationHandler(
                port,
                new TerminalSessionRegistry(),
                new MutableClock(),
                Duration.ofSeconds(30),
                RegistrationMaintenancePolicy.disabled(),
                PrivateVehicleIdentifierCapture.disabled(),
                RegistrationBodyLayoutPolicy.fromCommaSeparated(allowedDigest));
        EmbeddedChannel channel = channel(handler);

        assertFalse(channel.writeInbound(jt8082019LegacyRegistrationFrame(
                "MFG01", "UNLISTED-MODEL", "TERM001", "PILOT-A")));

        assertEquals(1, port.registrationAttempts(),
                "RED: compatibility parsing must still delegate strict preprovision checks to registry");
        assertEquals("UNLISTED-MODEL", port.lastRegistrationIdentity().model());
        assertFalse(channel.isActive());
        assertEquals("MODEL_MISMATCH", port.lastAudit().reasonCode());
        Jt808Frame response = channel.readOutbound();
        assertEquals(0x8100, response.header().messageId());
        assertEquals(1, response.body().getUnsignedByte(2));
        release(response);
        channel.finishAndReleaseAll();
    }

    @Test
    void reportsOnlySafeLayoutMetadataForRegistrationBodyShorterThanLegacyMinimum() {
        FakeTerminalRegistry port = FakeTerminalRegistry.approved();
        EmbeddedChannel channel = channel(
                port, new TerminalSessionRegistry(), new MutableClock());

        assertFalse(channel.writeInbound(jt8082019Frame(0x0100, new byte[12])));

        assertEquals(0, port.registrationAttempts());
        assertFalse(channel.isActive());
        SessionAuditIngress audit = port.lastAudit();
        assertEquals(SessionAuditType.REGISTRATION_REJECTED, audit.type());
        assertTrue(audit.reasonCode().startsWith("MALFORMED_REGISTRATION_BODY_TOO_SHORT"),
                "RED: safe diagnostics must classify the body-length failure");
        assertTrue(audit.reasonCode().contains("JT808_2019"));
        assertTrue(audit.reasonCode().contains("12"));
        assertFalse(audit.reasonCode().contains(JT808_2019_TERMINAL_NUMBER));
        assertFalse(audit.reasonCode().contains("MFG01"));
        assertFalse(audit.reasonCode().contains("PILOT-A"));
        Jt808Frame response = channel.readOutbound();
        assertEquals(0x8100, response.header().messageId());
        assertEquals(2, response.body().getUnsignedByte(2));
        release(response);
        channel.finishAndReleaseAll();
    }

    @Test
    void reportsPrivateCaptureConfigurationFailureWithoutMisclassifyingRegistrationAsMalformed() {
        MutableClock clock = new MutableClock();
        FakeTerminalRegistry port = FakeTerminalRegistry.approved();
        RegistrationMaintenancePolicy policy = RegistrationMaintenancePolicy.enabled(
                TERMINAL_NUMBER_DIGEST,
                clock.instant().plus(Duration.ofMinutes(10)),
                Duration.ofSeconds(60),
                clock);
        Path captureRoot = temporaryDirectory.resolve("private-capture");
        Path captureOutput = captureRoot.resolve("vehicle-identifier.txt");
        PrivateVehicleIdentifierCapture capture = PrivateVehicleIdentifierCapture.enabled(
                captureRoot, captureOutput);
        RegistrationAuthenticationHandler handler = new RegistrationAuthenticationHandler(
                port,
                new TerminalSessionRegistry(),
                clock,
                Duration.ofSeconds(30),
                policy,
                capture);
        EmbeddedChannel channel = channel(handler);

        assertFalse(channel.writeInbound(registrationFrame()));

        assertEquals(1, port.registrationAttempts(),
                "authoritative registry verification must precede private capture");
        assertFalse(Files.exists(captureOutput));
        assertFalse(channel.isActive());
        assertEquals("PRIVATE_CAPTURE_CONFIGURATION_INVALID", port.lastAudit().reasonCode(),
                "RED: a capture-stage failure must not be reported as malformed terminal input");
        Jt808Frame response = channel.readOutbound();
        assertEquals(0x8100, response.header().messageId());
        assertEquals(2, response.body().getUnsignedByte(2));
        release(response);
        channel.finishAndReleaseAll();
    }

    @Test
    void doesNotCaptureVehicleIdentifierWhenModelDoesNotMatchAuthoritativeRegistry() throws Exception {
        assertUntrustedRegistrationDoesNotCapture(
                FakeTerminalRegistry.rejected(RegistrationRejection.MODEL_MISMATCH),
                registrationFrame("MFG01", "FORGED-MODEL", "TERM001", "PRIVATE-PLATE"),
                "model mismatch");
    }

    @Test
    void doesNotCaptureVehicleIdentifierWhenTerminalCodeDoesNotMatchAuthoritativeRegistry() throws Exception {
        assertUntrustedRegistrationDoesNotCapture(
                FakeTerminalRegistry.rejected(RegistrationRejection.TERMINAL_CODE_NOT_FOUND),
                registrationFrame("MFG01", "PILOT-MODEL", "FORGED1", "PRIVATE-PLATE"),
                "terminal code mismatch");
    }

    @Test
    void doesNotCaptureVehicleIdentifierWhenRegistryIsUnavailable() throws Exception {
        IllegalStateException unavailable = new IllegalStateException("synthetic registry unavailable");
        FakeTerminalRegistry port = FakeTerminalRegistry.throwingRegistration(unavailable);
        Path output = temporaryDirectory.resolve("registry-unavailable").resolve("vehicle-identifier.bin");
        RegistrationAuthenticationHandler handler = captureEnabledHandler(port, output);
        EmbeddedChannel channel = channel(handler);
        Jt808Frame registration = registrationFrame();

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> channel.writeInbound(registration));

        assertSame(unavailable, thrown);
        assertFalse(Files.exists(output));
        assertEquals(0, registration.body().refCnt());
        assertFalse(channel.isActive());
        channel.finishAndReleaseAll();
    }

    @Test
    void capturesVehicleIdentifierAfterRegistryConfirmsItIsTheOnlyMismatch() throws Exception {
        FakeTerminalRegistry port = FakeTerminalRegistry.rejected(
                RegistrationRejection.VEHICLE_IDENTIFIER_MISMATCH);
        Path output = temporaryDirectory.resolve("vehicle-mismatch").resolve("vehicle-identifier.bin");
        port.observeCapturePath(output);
        RegistrationAuthenticationHandler handler = captureEnabledHandler(port, output);
        EmbeddedChannel channel = channel(handler);

        assertFalse(channel.writeInbound(registrationFrame(
                "MFG01", "PILOT-MODEL", "TERM001", "PRIVATE-PLATE")));

        assertFalse(port.captureExistedDuringVerification(),
                "private evidence must not be written before authoritative registration verification");
        assertArrayEquals("PRIVATE-PLATE".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(output));
        assertFalse(channel.isActive());
        drainOutbound(channel);
        channel.finishAndReleaseAll();
    }

    @Test
    void releasesFrameAndClosesSessionWhenPrivateCaptureWriteFails() throws Exception {
        MutableClock clock = new MutableClock();
        FakeTerminalRegistry registrySpy = FakeTerminalRegistry.approved();
        String knownFingerprint = "terminal-01:"
                + sha256(TERMINAL_NUMBER.getBytes(StandardCharsets.US_ASCII))
                + ":JT808_2013";
        RegistrationMaintenancePolicy policy = RegistrationMaintenancePolicy.enabled(
                TERMINAL_NUMBER_DIGEST,
                knownFingerprint,
                clock.instant().plus(Duration.ofMinutes(10)),
                Duration.ofSeconds(60),
                clock);
        Path captureRoot = temporaryDirectory.resolve("capture-root-file");
        Files.writeString(captureRoot, "not-a-directory", StandardCharsets.UTF_8);
        Path captureOutput = captureRoot.resolve("vehicle-identifier.txt");
        PrivateVehicleIdentifierCapture capture = PrivateVehicleIdentifierCapture.enabled(
                captureRoot, captureOutput);
        RegistrationAuthenticationHandler handler = new RegistrationAuthenticationHandler(
                registrySpy,
                new TerminalSessionRegistry(),
                clock,
                Duration.ofSeconds(30),
                policy,
                capture);
        EmbeddedChannel channel = channel(handler);
        Jt808Frame registration = registrationFrame();
        Jt808Frame unexpectedResponse = null;

        try {
            assertThrows(
                    IllegalStateException.class,
                    () -> channel.writeInbound(registration),
                    "RED: a private-capture write failure must escape as infrastructure failure");
            assertEquals(1, registrySpy.registrationAttempts(),
                    "authoritative registry verification must precede private capture");
            assertEquals(0, registrySpy.auditCount(),
                    "RED: capture infrastructure failure must not create a terminal-input audit");
            assertIssuedRegistrationTokenDestroyed(registrySpy);
            assertEquals(0, registration.body().refCnt(),
                    "RED: handler must release the inbound frame before propagating capture failure");
            assertFalse(channel.isActive(),
                    "RED: handler must fail-close the terminal channel on capture failure");
            assertEquals(TerminalSessionState.CLOSED, handler.session().state());
            unexpectedResponse = channel.readOutbound();
            assertNull(unexpectedResponse,
                    "RED: infrastructure failure must not emit a terminal registration response");
            assertFalse(Files.exists(captureOutput));
        } finally {
            release(unexpectedResponse);
            if (registration.body().refCnt() > 0) {
                release(registration);
            }
            drainOutbound(channel);
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void doesNotSwallowRegistryIllegalArgumentExceptionAsMalformedRegistration() {
        IllegalArgumentException registryFailure = new IllegalArgumentException(
                "synthetic registry failure");
        FakeTerminalRegistry port = FakeTerminalRegistry.throwingRegistration(registryFailure);
        RegistrationAuthenticationHandler handler = handler(
                port, new TerminalSessionRegistry(), new MutableClock());
        EmbeddedChannel channel = channel(handler);
        Jt808Frame registration = registrationFrame();
        Jt808Frame unexpectedResponse = null;

        try {
            IllegalArgumentException thrown = assertThrows(
                    IllegalArgumentException.class,
                    () -> channel.writeInbound(registration),
                    "RED: registry failures must escape the parser-only malformed-input boundary");
            assertSame(registryFailure, thrown);
            assertEquals(1, port.registrationAttempts());
            assertEquals(0, port.auditCount(),
                    "RED: an infrastructure failure must not create a malformed-registration audit");
            assertEquals(0, registration.body().refCnt(),
                    "RED: handler must release the inbound frame before propagating registry failure");
            assertFalse(channel.isActive(),
                    "RED: handler must fail-close the terminal channel on registry failure");
            assertEquals(TerminalSessionState.CLOSED, handler.session().state());
            unexpectedResponse = channel.readOutbound();
            assertNull(unexpectedResponse,
                    "RED: registry infrastructure failure must not emit a registration response");
        } finally {
            release(unexpectedResponse);
            if (registration.body().refCnt() > 0) {
                release(registration);
            }
            drainOutbound(channel);
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void registrationSuccessWaitsForDurableAuditBeforeReplyOrSessionMutation() {
        IllegalStateException persistenceFailure = new IllegalStateException("synthetic audit persistence failure");
        FakeTerminalRegistry port = FakeTerminalRegistry.approved();
        port.failNextAudit(persistenceFailure);
        TerminalSessionRegistry sessions = new TerminalSessionRegistry();
        RegistrationAuthenticationHandler handler = handler(port, sessions, new MutableClock());
        EmbeddedChannel channel = channel(handler);
        Jt808Frame registration = registrationFrame();

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> channel.writeInbound(registration));

        assertSame(persistenceFailure, thrown);
        assertEquals(0, registration.body().refCnt());
        assertFalse(channel.isActive());
        assertEquals(TerminalSessionState.CLOSED, handler.session().state());
        assertIssuedRegistrationTokenDestroyed(port);
        assertNull(handler.session().terminalId(),
                "registration identity must not become session-visible before audit persistence");
        assertTrue(sessions.current(TERMINAL_ID).isEmpty());
        assertNull(channel.readOutbound(), "a non-durable registration success must not be acknowledged");
        channel.finishAndReleaseAll();
    }

    @Test
    void registrationSessionInstallFailureDestroysIssuedTokenAndClosesTheChannel() {
        FakeTerminalRegistry port = FakeTerminalRegistry.approved();
        RegistrationAuthenticationHandler handler = handler(
                port, new TerminalSessionRegistry(), new MutableClock());
        port.beforeRegistrationApproval(() -> handler.session().markClosed());
        EmbeddedChannel channel = channel(handler);
        Jt808Frame registration = registrationFrame();

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> channel.writeInbound(registration));

        assertTrue(thrown.getMessage().contains("session transition"));
        assertIssuedRegistrationTokenDestroyed(port);
        assertEquals(0, registration.body().refCnt());
        assertEquals(TerminalSessionState.CLOSED, handler.session().state());
        assertFalse(channel.isActive());
        assertNull(channel.readOutbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void registrationReplyWriteFailureDestroysIssuedTokenAndCleansTheSession() {
        IllegalStateException writeFailure = new IllegalStateException(
                "synthetic registration reply write failure");
        FakeTerminalRegistry port = FakeTerminalRegistry.approved();
        TerminalSessionRegistry sessions = new TerminalSessionRegistry();
        RegistrationAuthenticationHandler handler = handler(
                port, sessions, new MutableClock());
        EmbeddedChannel channel = new EmbeddedChannel(
                new OutboundResponseCopy(),
                new RegistrationReplyFailure(writeFailure),
                new JtFrameOwnershipHandler(),
                handler);
        Jt808Frame registration = registrationFrame();

        assertDoesNotThrow(() -> channel.writeInbound(registration));
        channel.runPendingTasks();
        assertIssuedRegistrationTokenDestroyed(port);
        assertEquals(0, registration.body().refCnt());
        assertEquals(TerminalSessionState.CLOSED, handler.session().state());
        assertTrue(sessions.current(TERMINAL_ID).isEmpty());
        assertFalse(channel.isActive());
        channel.finishAndReleaseAll();
    }

    @Test
    void registrationRejectionWaitsForDurableAuditBeforeReplying() {
        IllegalStateException persistenceFailure = new IllegalStateException(
                "synthetic registration rejection audit failure");
        FakeTerminalRegistry port = FakeTerminalRegistry.rejected(RegistrationRejection.MODEL_MISMATCH);
        port.failNextAudit(persistenceFailure);
        RegistrationAuthenticationHandler handler = handler(
                port, new TerminalSessionRegistry(), new MutableClock());
        EmbeddedChannel channel = channel(handler);
        Jt808Frame registration = registrationFrame(
                "MFG01", "FORGED-MODEL", "TERM001", "PILOT-A");

        try {
            IllegalStateException thrown = assertThrows(
                    IllegalStateException.class,
                    () -> channel.writeInbound(registration));
            assertSame(persistenceFailure, thrown);
            assertEquals(0, registration.body().refCnt());
            assertFalse(channel.isActive());
            assertEquals(TerminalSessionState.CLOSED, handler.session().state());
            assertNull(channel.readOutbound(),
                    "a non-durable registration rejection must not be acknowledged");
        } finally {
            if (registration.body().refCnt() > 0) {
                release(registration);
            }
            drainOutbound(channel);
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void authenticationSuccessWaitsForDurableAuditBeforeReplyOrClaim() {
        IllegalStateException persistenceFailure = new IllegalStateException("synthetic audit persistence failure");
        FakeTerminalRegistry port = FakeTerminalRegistry.approved();
        TerminalSessionRegistry sessions = new TerminalSessionRegistry();
        RegistrationAuthenticationHandler handler = handler(port, sessions, new MutableClock());
        EmbeddedChannel channel = channel(handler);
        channel.writeInbound(registrationFrame());
        release(channel.readOutbound());
        port.failNextAudit(persistenceFailure);
        Jt808Frame authentication = authenticationFrame(AUTHENTICATION_TOKEN);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> channel.writeInbound(authentication));

        assertSame(persistenceFailure, thrown);
        assertEquals(0, authentication.body().refCnt());
        assertFalse(channel.isActive());
        assertEquals(TerminalSessionState.CLOSED, handler.session().state());
        assertTrue(sessions.current(TERMINAL_ID).isEmpty(),
                "authentication must not claim a session before audit persistence");
        assertNull(channel.readOutbound(), "a non-durable authentication success must not be acknowledged");
        channel.finishAndReleaseAll();
    }

    @Test
    void firstAuthenticationRejectionWaitsForDurableAuditBeforeFailureCountOrReply() {
        IllegalStateException persistenceFailure = new IllegalStateException(
                "synthetic authentication rejection audit failure");
        FakeTerminalRegistry port = FakeTerminalRegistry.approved();
        RegistrationAuthenticationHandler handler = handler(
                port, new TerminalSessionRegistry(), new MutableClock());
        EmbeddedChannel channel = channel(handler);
        channel.writeInbound(registrationFrame());
        release(channel.readOutbound());
        port.failNextAudit(persistenceFailure);
        Jt808Frame authentication = authenticationFrame("WRONG-FIRST");

        try {
            IllegalStateException thrown = assertThrows(
                    IllegalStateException.class,
                    () -> channel.writeInbound(authentication));
            assertSame(persistenceFailure, thrown);
            assertEquals(0, handler.session().authenticationFailures(),
                    "failure count must not advance before durable rejection audit");
            assertEquals(0, authentication.body().refCnt());
            assertFalse(channel.isActive());
            assertEquals(TerminalSessionState.CLOSED, handler.session().state());
            assertNull(channel.readOutbound());
        } finally {
            if (authentication.body().refCnt() > 0) {
                release(authentication);
            }
            drainOutbound(channel);
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void thirdAuthenticationRejectionDoesNotCommitWhenRejectedAuditFails() {
        assertThirdAuthenticationAuditFailureRollsBack(
                SessionAuditType.AUTHENTICATION_REJECTED,
                "synthetic third rejection audit failure");
    }

    @Test
    void thirdAuthenticationRejectionDoesNotCommitWhenLockedAuditFails() {
        assertThirdAuthenticationAuditFailureRollsBack(
                SessionAuditType.AUTHENTICATION_LOCKED,
                "synthetic lock audit failure");
    }

    @Test
    void authenticationRuntimeFailureReleasesFrameClosesSessionAndPropagatesOriginalException() {
        IllegalStateException registryFailure = new IllegalStateException(
                "synthetic authentication registry failure");
        FakeTerminalRegistry port = FakeTerminalRegistry.throwingAuthentication(registryFailure);
        TerminalSessionRegistry sessions = new TerminalSessionRegistry();
        RegistrationAuthenticationHandler handler = handler(port, sessions, new MutableClock());
        EmbeddedChannel channel = channel(handler);
        channel.writeInbound(registrationFrame());
        release(channel.readOutbound());
        Jt808Frame authentication = authenticationFrame(AUTHENTICATION_TOKEN);
        Jt808Frame unexpectedResponse = null;

        try {
            IllegalStateException thrown = assertThrows(
                    IllegalStateException.class,
                    () -> channel.writeInbound(authentication));
            assertSame(registryFailure, thrown);
            assertEquals(0, authentication.body().refCnt(),
                    "handler must release the authentication frame before propagation");
            assertFalse(channel.isActive());
            assertEquals(TerminalSessionState.CLOSED, handler.session().state());
            assertTrue(sessions.current(TERMINAL_ID).isEmpty());
            unexpectedResponse = channel.readOutbound();
            assertNull(unexpectedResponse,
                    "authentication infrastructure failure must not emit a success response");
        } finally {
            release(unexpectedResponse);
            if (authentication.body().refCnt() > 0) {
                release(authentication);
            }
            drainOutbound(channel);
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void takeoverAuditMustPersistBeforeReplacingTheExistingSession() {
        IllegalStateException persistenceFailure = new IllegalStateException(
                "synthetic takeover audit persistence failure");
        FakeTerminalRegistry port = FakeTerminalRegistry.approved();
        TerminalSessionRegistry sessions = new TerminalSessionRegistry();
        RegistrationAuthenticationHandler firstHandler = handler(port, sessions, new MutableClock());
        RegistrationAuthenticationHandler secondHandler = handler(port, sessions, new MutableClock());
        EmbeddedChannel first = channel(firstHandler);
        EmbeddedChannel second = channel(secondHandler);
        authenticate(first);
        second.writeInbound(registrationFrame());
        release(second.readOutbound());
        port.failAudit(SessionAuditType.SESSION_TAKEN_OVER, persistenceFailure);
        Jt808Frame authentication = authenticationFrame(AUTHENTICATION_TOKEN);

        try {
            IllegalStateException thrown = assertThrows(
                    IllegalStateException.class,
                    () -> second.writeInbound(authentication));
            assertSame(persistenceFailure, thrown);
            assertEquals(0, authentication.body().refCnt());
            assertFalse(second.isActive());
            assertEquals(TerminalSessionState.CLOSED, secondHandler.session().state());
            assertTrue(first.isActive(), "the durable audit gate must preserve the current session");
            assertSame(firstHandler.session(), sessions.current(TERMINAL_ID).orElseThrow());
            assertNull(second.readOutbound());
        } finally {
            if (authentication.body().refCnt() > 0) {
                release(authentication);
            }
            drainOutbound(first);
            drainOutbound(second);
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
        }
    }

    @Test
    void twoPhysicalTerminalsForOneVehicleCoexistAndTakeoverOnlyTheirOwnIdentity() {
        TerminalSessionRegistry sessions = new TerminalSessionRegistry();
        EmbeddedChannel dispatchChannel = new EmbeddedChannel();
        EmbeddedChannel recorderChannel = new EmbeddedChannel();
        EmbeddedChannel replacementChannel = new EmbeddedChannel();
        TerminalSession dispatch = authenticatedSession(
                dispatchChannel,
                terminalContext(TERMINAL_ID, Set.of("DISPATCH", "LOCATION_PRIMARY")),
                TERMINAL_NUMBER);
        TerminalSession recorder = authenticatedSession(
                recorderChannel,
                terminalContext(PEER_TERMINAL_ID,
                        Set.of("LOCATION_BACKUP", "ACTIVE_SAFETY", "VIDEO")),
                "999999999999");
        TerminalSession replacement = authenticatedSession(
                replacementChannel,
                terminalContext(TERMINAL_ID, Set.of("DISPATCH", "LOCATION_PRIMARY")),
                TERMINAL_NUMBER);

        assertTrue(sessions.claim(dispatch).isEmpty());
        assertTrue(sessions.claim(recorder).isEmpty());
        assertSame(dispatch, sessions.current(TERMINAL_ID).orElseThrow());
        assertSame(recorder, sessions.current(PEER_TERMINAL_ID).orElseThrow());
        assertEquals(ONBOARD_SYSTEM_ID, dispatch.onboardSystemId());
        assertEquals(ONBOARD_SYSTEM_ID, recorder.onboardSystemId());
        assertEquals(VEHICLE_ID, dispatch.vehicleId());
        assertEquals(VEHICLE_ID, recorder.vehicleId());

        assertSame(dispatch, sessions.claim(replacement).orElseThrow());

        assertFalse(dispatchChannel.isActive());
        assertTrue(recorderChannel.isActive());
        assertTrue(replacementChannel.isActive());
        assertSame(replacement, sessions.current(TERMINAL_ID).orElseThrow());
        assertSame(recorder, sessions.current(PEER_TERMINAL_ID).orElseThrow());
        assertThrows(UnsupportedOperationException.class,
                () -> recorder.roles().add("WAN_UPLINK"));
        dispatchChannel.finishAndReleaseAll();
        recorderChannel.finishAndReleaseAll();
        replacementChannel.finishAndReleaseAll();
    }

    @Test
    void failedIdentityRestoreLeavesTheUnauthenticatedSessionCompletelyUnbound() {
        EmbeddedChannel channel = new EmbeddedChannel();
        TerminalSession session = new TerminalSession(
                channel, Instant.parse("2026-08-12T00:00:00Z"));

        assertThrows(NullPointerException.class, () -> session.restoreAuthenticatedIdentity(
                terminalContext(TERMINAL_ID, Set.of("LOCATION_PRIMARY")), null));

        assertEquals(TerminalSessionState.CONNECTED_UNAUTHENTICATED, session.state());
        assertNull(session.context());
        assertNull(session.terminalId());
        assertNull(session.onboardSystemId());
        assertNull(session.vehicleId());
        channel.finishAndReleaseAll();
    }

    @Test
    void freshJt8082019AuthenticationRestoresFullPhysicalContextAndIdentityIsolation()
            throws Exception {
        MutableClock clock = new MutableClock();
        FakeTerminalRegistry port = FakeTerminalRegistry.approved();
        TerminalSessionContext context = terminalContext(
                PEER_TERMINAL_ID,
                Set.of("LOCATION_BACKUP", "ACTIVE_SAFETY", "VIDEO"));
        port.approveIdentity(JT808_2019_TERMINAL_NUMBER, context);
        TerminalSessionRegistry sessions = new TerminalSessionRegistry();
        RegistrationAuthenticationHandler handler = handler(port, sessions, clock);
        EmbeddedChannel channel = channel(handler);
        byte[] token = AUTHENTICATION_TOKEN.getBytes(StandardCharsets.US_ASCII);
        byte[] body = new byte[token.length + 1];
        body[0] = (byte) token.length;
        System.arraycopy(token, 0, body, 1, token.length);

        assertFalse(channel.writeInbound(jt8082019Frame(0x0102, body)));
        release(channel.readOutbound());

        assertEquals(TerminalSessionState.AUTHENTICATED, handler.session().state());
        assertEquals(PEER_TERMINAL_ID, handler.session().terminalId());
        assertEquals(ONBOARD_SYSTEM_ID, handler.session().onboardSystemId());
        assertEquals(VEHICLE_ID, handler.session().vehicleId());
        assertEquals(Set.of("LOCATION_BACKUP", "ACTIVE_SAFETY", "VIDEO"),
                handler.session().roles());
        assertEquals("WGS84", handler.session().sourceCoordinateSystem());
        assertEquals("T/JSATL12-2017", handler.session().activeSafetyStandard());
        assertEquals(List.of("ADAS", "DMS"), handler.session().activeSafetyModules());
        assertEquals(5, handler.session().tokenVersion());
        assertSame(handler.session(), sessions.current(PEER_TERMINAL_ID).orElseThrow());
        assertEquals(ProtocolVersion.JT808_2019, port.lastIdentityProtocol());
        assertEquals(JT808_2019_TERMINAL_NUMBER, port.lastIdentityPhone());
        assertEquals(sha256(token), port.lastIdentityDigest());
        assertNotEquals(AUTHENTICATION_TOKEN, port.lastIdentityDigest());

        Jt808Frame changedIdentity = frame(0x0200, new byte[] {1}, "777777777777");
        assertFalse(channel.writeInbound(changedIdentity));
        assertFalse(channel.isActive());
        assertTrue(sessions.current(PEER_TERMINAL_ID).isEmpty());
        assertEquals(SessionAuditType.SESSION_IDENTITY_MISMATCH, port.lastAudit().type());
        channel.finishAndReleaseAll();
    }

    @Test
    void crossConnectionBusinessRejectionInstallsNoContextAndLeaksNoIdentity() {
        FakeTerminalRegistry port = FakeTerminalRegistry.approved();
        port.rejectIdentity(AuthenticationRejection.TOKEN_MISMATCH);
        TerminalSessionRegistry sessions = new TerminalSessionRegistry();
        RegistrationAuthenticationHandler handler = handler(port, sessions, new MutableClock());
        EmbeddedChannel channel = channel(handler);
        Jt808Frame authentication = authenticationFrame("SYNTHETIC-SECRET-TOKEN");

        assertFalse(channel.writeInbound(authentication));

        assertTrue(channel.isActive());
        assertNull(handler.session().terminalId());
        assertNull(handler.session().context());
        assertEquals(1, handler.session().authenticationFailures());
        assertEquals(SessionAuditType.AUTHENTICATION_REJECTED, port.lastAudit().type());
        assertEquals(AuthenticationRejection.TOKEN_MISMATCH.name(),
                port.lastAudit().reasonCode());
        assertNull(port.lastAudit().terminalId());
        assertEquals(TERMINAL_NUMBER, port.lastIdentityPhone());
        assertFalse(port.lastAudit().terminalAlias().contains(TERMINAL_NUMBER));
        assertFalse(port.lastAudit().toString().contains("SYNTHETIC-SECRET-TOKEN"));
        assertTrue(sessions.current(TERMINAL_ID).isEmpty());
        release(channel.readOutbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void identityClientFailureClearsTokenReleasesFrameAndClosesOnlyFailingDevice() {
        IllegalStateException failure = new IllegalStateException("synthetic identity client failure");
        FakeTerminalRegistry port = FakeTerminalRegistry.approved();
        port.failIdentityAuthentication(failure);
        TerminalSessionRegistry sessions = new TerminalSessionRegistry();
        EmbeddedChannel peerChannel = new EmbeddedChannel();
        TerminalSession peer = authenticatedSession(
                peerChannel,
                terminalContext(PEER_TERMINAL_ID, Set.of("LOCATION_BACKUP")),
                "999999999999");
        sessions.claim(peer);
        RegistrationAuthenticationHandler handler = handler(port, sessions, new MutableClock());
        EmbeddedChannel failing = channel(handler);
        CapturingAuthenticationBody body = new CapturingAuthenticationBody(
                AUTHENTICATION_TOKEN.getBytes(StandardCharsets.US_ASCII));
        Jt808Frame authentication = frame(0x0102, body, TERMINAL_NUMBER);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> failing.writeInbound(authentication));

        assertSame(failure, thrown);
        assertEquals(0, authentication.body().refCnt());
        assertEquals(1, body.deallocations());
        assertNotNull(body.capturedDestination());
        assertArrayEquals(new byte[AUTHENTICATION_TOKEN.length()], body.capturedDestination());
        assertFalse(failing.isActive());
        assertEquals(TerminalSessionState.CLOSED, handler.session().state());
        assertTrue(peerChannel.isActive());
        assertSame(peer, sessions.current(PEER_TERMINAL_ID).orElseThrow());
        assertTrue(sessions.current(TERMINAL_ID).isEmpty());
        failing.finishAndReleaseAll();
        peerChannel.finishAndReleaseAll();
    }

    @Test
    void auditAndTakeoverFailuresAfterIdentityRestoreLeaveNoPartialSession() {
        for (SessionAuditType failedAudit : List.of(
                SessionAuditType.AUTHENTICATION_ACCEPTED,
                SessionAuditType.SESSION_TAKEN_OVER)) {
            IllegalStateException failure = new IllegalStateException(
                    "synthetic " + failedAudit + " failure");
            FakeTerminalRegistry port = FakeTerminalRegistry.approved();
            port.approveIdentity(TERMINAL_NUMBER,
                    terminalContext(TERMINAL_ID, Set.of("DISPATCH", "LOCATION_PRIMARY")));
            port.failAudit(failedAudit, failure);
            TerminalSessionRegistry sessions = new TerminalSessionRegistry();
            EmbeddedChannel currentChannel = new EmbeddedChannel();
            TerminalSession current = authenticatedSession(
                    currentChannel,
                    terminalContext(TERMINAL_ID, Set.of("DISPATCH", "LOCATION_PRIMARY")),
                    TERMINAL_NUMBER);
            if (failedAudit == SessionAuditType.SESSION_TAKEN_OVER) {
                sessions.claim(current);
            }
            EmbeddedChannel peerChannel = new EmbeddedChannel();
            TerminalSession peer = authenticatedSession(
                    peerChannel,
                    terminalContext(PEER_TERMINAL_ID, Set.of("LOCATION_BACKUP")),
                    "999999999999");
            sessions.claim(peer);
            RegistrationAuthenticationHandler handler = handler(
                    port, sessions, new MutableClock());
            EmbeddedChannel failing = channel(handler);
            Jt808Frame authentication = authenticationFrame(AUTHENTICATION_TOKEN);

            IllegalStateException thrown = assertThrows(
                    IllegalStateException.class,
                    () -> failing.writeInbound(authentication));

            assertSame(failure, thrown);
            assertEquals(0, authentication.body().refCnt());
            assertFalse(failing.isActive());
            assertEquals(TerminalSessionState.CLOSED, handler.session().state());
            assertSame(peer, sessions.current(PEER_TERMINAL_ID).orElseThrow());
            assertTrue(peerChannel.isActive());
            if (failedAudit == SessionAuditType.SESSION_TAKEN_OVER) {
                assertSame(current, sessions.current(TERMINAL_ID).orElseThrow());
                assertTrue(currentChannel.isActive());
            } else {
                assertTrue(sessions.current(TERMINAL_ID).isEmpty());
            }
            failing.finishAndReleaseAll();
            currentChannel.finishAndReleaseAll();
            peerChannel.finishAndReleaseAll();
        }
    }

    @Test
    void terminalAuthenticationAtomicallyReplacesTheRegistrationAuthorizationSnapshot() {
        UUID currentSystemId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID currentVehicleId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        TerminalSessionContext authenticatedContext = new TerminalSessionContext(
                TERMINAL_ID,
                currentSystemId,
                currentVehicleId,
                Set.of("LOCATION_BACKUP", "VIDEO"),
                "GCJ02",
                "T/GD-ACTIVE-SAFETY",
                List.of("BSD"),
                5);
        FakeTerminalRegistry port = FakeTerminalRegistry.approved();
        port.approveTerminalAuthentication(authenticatedContext);
        TerminalSessionRegistry sessions = new TerminalSessionRegistry();
        EmbeddedChannel peerChannel = new EmbeddedChannel();
        TerminalSession peer = authenticatedSession(
                peerChannel,
                terminalContext(PEER_TERMINAL_ID, Set.of("LOCATION_BACKUP")),
                "999999999999");
        sessions.claim(peer);
        RegistrationAuthenticationHandler handler = handler(
                port, sessions, new MutableClock());
        EmbeddedChannel channel = channel(handler);
        channel.writeInbound(registrationFrame());
        release(channel.readOutbound());
        assertEquals(ONBOARD_SYSTEM_ID, handler.session().onboardSystemId());
        assertEquals(VEHICLE_ID, handler.session().vehicleId());
        assertEquals(Set.of("LOCATION_PRIMARY", "ACTIVE_SAFETY"),
                handler.session().roles());

        channel.writeInbound(authenticationFrame(AUTHENTICATION_TOKEN));
        release(channel.readOutbound());

        assertEquals(TerminalSessionState.AUTHENTICATED, handler.session().state());
        assertEquals(authenticatedContext, handler.session().context());
        assertEquals(currentSystemId, handler.session().onboardSystemId());
        assertEquals(currentVehicleId, handler.session().vehicleId());
        assertEquals(Set.of("LOCATION_BACKUP", "VIDEO"), handler.session().roles());
        assertEquals("GCJ02", handler.session().sourceCoordinateSystem());
        assertEquals("T/GD-ACTIVE-SAFETY", handler.session().activeSafetyStandard());
        assertEquals(List.of("BSD"), handler.session().activeSafetyModules());
        assertSame(peer, sessions.current(PEER_TERMINAL_ID).orElseThrow());
        assertTrue(peerChannel.isActive());
        channel.finishAndReleaseAll();
        peerChannel.finishAndReleaseAll();
    }

    @Test
    void inconsistentTerminalAuthenticationContextCleansOnlyTheFailingConnection() {
        FakeTerminalRegistry port = FakeTerminalRegistry.approved();
        port.approveTerminalAuthentication(terminalContext(
                UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
                Set.of("LOCATION_PRIMARY")));
        TerminalSessionRegistry sessions = new TerminalSessionRegistry();
        EmbeddedChannel peerChannel = new EmbeddedChannel();
        TerminalSession peer = authenticatedSession(
                peerChannel,
                terminalContext(PEER_TERMINAL_ID, Set.of("LOCATION_BACKUP")),
                "999999999999");
        sessions.claim(peer);
        RegistrationAuthenticationHandler handler = handler(
                port, sessions, new MutableClock());
        EmbeddedChannel failing = channel(handler);
        failing.writeInbound(registrationFrame());
        release(failing.readOutbound());
        Jt808Frame authentication = authenticationFrame(AUTHENTICATION_TOKEN);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> failing.writeInbound(authentication));

        assertEquals("authentication context is inconsistent", thrown.getMessage());
        assertEquals(0, authentication.body().refCnt());
        assertFalse(failing.isActive());
        assertEquals(TerminalSessionState.CLOSED, handler.session().state());
        assertTrue(sessions.current(TERMINAL_ID).isEmpty());
        assertSame(peer, sessions.current(PEER_TERMINAL_ID).orElseThrow());
        assertTrue(peerChannel.isActive());
        failing.finishAndReleaseAll();
        peerChannel.finishAndReleaseAll();
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
        assertEquals(ONBOARD_SYSTEM_ID, handler.session().onboardSystemId());
        assertEquals(VEHICLE_ID, handler.session().vehicleId());
        assertEquals(Set.of("LOCATION_PRIMARY", "ACTIVE_SAFETY"),
                handler.session().roles());
        assertEquals("T/JSATL12-2017", handler.session().activeSafetyStandard());
        assertEquals(List.of("ADAS", "DMS"), handler.session().activeSafetyModules());
        assertIssuedRegistrationTokenDestroyed(port);

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
    void preservesAKnownUnimplementedGuangdongProfileOnTheTerminalSession() {
        RegistrationAuthenticationHandler handler = handler(
                FakeTerminalRegistry.approvedWithProfile("T/GD-ACTIVE-SAFETY", List.of("ADAS")),
                new TerminalSessionRegistry(),
                new MutableClock());
        EmbeddedChannel channel = channel(handler);

        assertFalse(channel.writeInbound(registrationFrame()));
        release(channel.readOutbound());

        assertEquals("T/GD-ACTIVE-SAFETY", handler.session().activeSafetyStandard());
        assertEquals(List.of("ADAS"), handler.session().activeSafetyModules());
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
        RegistrationDecision decision = RegistrationDecision.issue(
                terminalContext(TERMINAL_ID, Set.of("LOCATION_PRIMARY")),
                new SecureRandom());
        byte[] token = decision.consumeAuthenticationToken();
        try {
            assertTrue(token.length >= 43);
            assertEquals(sha256(token), decision.authenticationTokenSha256());
            assertFalse(decision.toString().contains(
                    new String(token, StandardCharsets.US_ASCII)));
            assertFalse(decision.hasAvailableAuthenticationToken());
            assertThrows(IllegalStateException.class, decision::consumeAuthenticationToken);
        } finally {
            java.util.Arrays.fill(token, (byte) 0);
            decision.destroyAuthenticationToken();
        }
        assertTrue(decision.authenticationTokenDestroyed());
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

    private static TerminalSessionContext terminalContext(
            UUID terminalId, Set<String> roles) {
        return new TerminalSessionContext(
                terminalId,
                ONBOARD_SYSTEM_ID,
                VEHICLE_ID,
                roles,
                "WGS84",
                "T/JSATL12-2017",
                List.of("ADAS", "DMS"),
                5);
    }

    private static TerminalSession authenticatedSession(
            EmbeddedChannel channel,
            TerminalSessionContext context,
            String terminalIdentity) {
        TerminalSession session = new TerminalSession(
                channel, Instant.parse("2026-08-12T00:00:00Z"));
        session.restoreAuthenticatedIdentity(context, terminalIdentity);
        session.authenticated(Instant.parse("2026-08-12T00:00:01Z"));
        return session;
    }

    private static void assertIssuedRegistrationTokenDestroyed(FakeTerminalRegistry registry) {
        RegistrationDecision decision = registry.lastRegistrationDecision();
        assertNotNull(decision);
        assertFalse(decision.hasAvailableAuthenticationToken());
        assertTrue(decision.authenticationTokenDestroyed());
        assertThrows(IllegalStateException.class, decision::consumeAuthenticationToken);
    }

    private static void authenticate(EmbeddedChannel channel) {
        channel.writeInbound(registrationFrame());
        release(channel.readOutbound());
        channel.writeInbound(authenticationFrame(AUTHENTICATION_TOKEN));
        release(channel.readOutbound());
    }

    private static Jt808Frame registrationFrame() {
        return registrationFrame(TERMINAL_NUMBER);
    }

    private static Jt808Frame registrationFrame(String terminalNumber) {
        ByteBuf body = registrationBody("MFG01", "PILOT-MODEL", "TERM001", "PILOT-A");
        return frame(0x0100, readableBytes(body), terminalNumber);
    }

    private static Jt808Frame registrationFrame(
            String manufacturer, String model, String terminalCode, String vehicleIdentifier) {
        return frame(0x0100, readableBytes(registrationBody(
                manufacturer, model, terminalCode, vehicleIdentifier)));
    }

    private static Jt808Frame jt8082019LegacyRegistrationFrame(
            String manufacturer, String model, String terminalCode, String vehicleIdentifier) {
        return jt8082019LegacyRegistrationFrame(
                manufacturer, model, terminalCode, vehicleIdentifier, JT808_2019_TERMINAL_NUMBER);
    }

    private static Jt808Frame jt8082019LegacyRegistrationFrame(
            String manufacturer,
            String model,
            String terminalCode,
            String vehicleIdentifier,
            String terminalIdentity) {
        ByteBuf body = registrationBody(manufacturer, model, terminalCode, vehicleIdentifier);
        byte[] bytes = readableBytes(body);
        Jt808MessageHeader header = new Jt808MessageHeader(
                0x0100, bytes.length | 0x4000, bytes.length, 0, false,
                ProtocolVersion.JT808_2019, 1, terminalIdentity, 9, null, null);
        return new Jt808Frame(header, Unpooled.wrappedBuffer(bytes), (byte) 0);
    }

    private static Jt808Frame jt8082019Frame(int messageId, byte[] body) {
        Jt808MessageHeader header = new Jt808MessageHeader(
                messageId, body.length | 0x4000, body.length, 0, false,
                ProtocolVersion.JT808_2019, 1, JT808_2019_TERMINAL_NUMBER, 9, null, null);
        return new Jt808Frame(header, Unpooled.wrappedBuffer(body), (byte) 0);
    }

    private static ByteBuf registrationBody(
            String manufacturer, String model, String terminalCode, String vehicleIdentifier) {
        ByteBuf body = Unpooled.buffer();
        body.writeShort(62).writeShort(621);
        writeFixedAscii(body, manufacturer, 5);
        writeFixedAscii(body, model, 20);
        writeFixedAscii(body, terminalCode, 7);
        body.writeByte(1);
        body.writeCharSequence(vehicleIdentifier, StandardCharsets.US_ASCII);
        return body;
    }

    private static void assertBlankRegistrationFieldRejected(Jt808Frame registration, String expectedReason) {
        FakeTerminalRegistry port = FakeTerminalRegistry.rejected(RegistrationRejection.NOT_PREPROVISIONED);
        EmbeddedChannel channel = channel(port, new TerminalSessionRegistry(), new MutableClock());

        assertFalse(channel.writeInbound(registration));

        assertEquals(0, port.registrationAttempts());
        assertFalse(channel.isActive());
        assertEquals(SessionAuditType.REGISTRATION_REJECTED, port.lastAudit().type());
        assertEquals(expectedReason, port.lastAudit().reasonCode());
        Jt808Frame response = channel.readOutbound();
        assertEquals(0x8100, response.header().messageId());
        assertEquals(1, response.body().getUnsignedByte(2));
        release(response);
        channel.finishAndReleaseAll();
    }

    private static void assertThirdAuthenticationAuditFailureRollsBack(
            SessionAuditType failedAudit,
            String failureMessage) {
        IllegalStateException persistenceFailure = new IllegalStateException(failureMessage);
        FakeTerminalRegistry port = FakeTerminalRegistry.approved();
        RegistrationAuthenticationHandler handler = handler(
                port, new TerminalSessionRegistry(), new MutableClock());
        EmbeddedChannel channel = channel(handler);
        channel.writeInbound(registrationFrame());
        release(channel.readOutbound());
        for (int attempt = 1; attempt <= 2; attempt++) {
            channel.writeInbound(authenticationFrame("WRONG-PRIME-" + attempt));
            release(channel.readOutbound());
        }
        assertEquals(2, handler.session().authenticationFailures());
        port.failAudit(failedAudit, persistenceFailure);
        Jt808Frame third = authenticationFrame("WRONG-THIRD");

        try {
            IllegalStateException thrown = assertThrows(
                    IllegalStateException.class,
                    () -> channel.writeInbound(third));
            assertSame(persistenceFailure, thrown);
            assertEquals(2, handler.session().authenticationFailures(),
                    "third failure must not commit until rejection and lock audits are durable");
            assertEquals(0, third.body().refCnt());
            assertFalse(channel.isActive());
            assertEquals(TerminalSessionState.CLOSED, handler.session().state());
            assertNull(channel.readOutbound());
        } finally {
            if (third.body().refCnt() > 0) {
                release(third);
            }
            drainOutbound(channel);
            channel.finishAndReleaseAll();
        }
    }

    private void assertUntrustedRegistrationDoesNotCapture(
            FakeTerminalRegistry port,
            Jt808Frame registration,
            String mismatch) throws Exception {
        Path output = temporaryDirectory.resolve(mismatch.replace(' ', '-'))
                .resolve("vehicle-identifier.bin");
        RegistrationAuthenticationHandler handler = captureEnabledHandler(port, output);
        EmbeddedChannel channel = channel(handler);

        assertFalse(channel.writeInbound(registration));

        assertEquals(1, port.registrationAttempts());
        assertFalse(Files.exists(output), mismatch + " must not produce private evidence");
        assertFalse(channel.isActive());
        drainOutbound(channel);
        channel.finishAndReleaseAll();
    }

    private RegistrationAuthenticationHandler captureEnabledHandler(
            FakeTerminalRegistry port,
            Path output) throws Exception {
        MutableClock clock = new MutableClock();
        String knownFingerprint = "terminal-01:"
                + sha256(TERMINAL_NUMBER.getBytes(StandardCharsets.US_ASCII))
                + ":JT808_2013";
        RegistrationMaintenancePolicy policy = RegistrationMaintenancePolicy.enabled(
                TERMINAL_NUMBER_DIGEST,
                knownFingerprint,
                clock.instant().plus(Duration.ofMinutes(10)),
                Duration.ofSeconds(60),
                clock);
        return new RegistrationAuthenticationHandler(
                port,
                new TerminalSessionRegistry(),
                clock,
                Duration.ofSeconds(30),
                policy,
                PrivateVehicleIdentifierCapture.enabled(output.getParent(), output));
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

    private static Jt808Frame frame(
            int messageId, ByteBuf body, String terminalNumber) {
        Jt808MessageHeader header = new Jt808MessageHeader(
                messageId, body.readableBytes(), body.readableBytes(), 0, false,
                ProtocolVersion.JT808_2013, 0, terminalNumber, 9, null, null);
        return new Jt808Frame(header, body, (byte) 0);
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

    private static final class CapturingAuthenticationBody extends UnpooledHeapByteBuf {
        private final AtomicReference<byte[]> capturedDestination = new AtomicReference<>();
        private final AtomicInteger deallocations = new AtomicInteger();

        private CapturingAuthenticationBody(byte[] token) {
            super(UnpooledByteBufAllocator.DEFAULT, token.length, token.length);
            writeBytes(token);
        }

        @Override
        public ByteBuf getBytes(int index, byte[] destination, int destinationIndex, int length) {
            capturedDestination.compareAndSet(null, destination);
            return super.getBytes(index, destination, destinationIndex, length);
        }

        @Override
        protected void deallocate() {
            deallocations.incrementAndGet();
            super.deallocate();
        }

        byte[] capturedDestination() {
            return capturedDestination.get();
        }

        int deallocations() {
            return deallocations.get();
        }
    }

    private static final class FakeTerminalRegistry implements TerminalRegistryPort {
        private final RegistrationRejection registrationRejection;
        private final String activeSafetyStandard;
        private final List<String> activeSafetyModules;
        private final List<SessionAuditIngress> audits = new ArrayList<>();
        private RuntimeException registrationFailure;
        private RuntimeException authenticationFailure;
        private TerminalSessionContext terminalAuthenticationContext;
        private RuntimeException identityAuthenticationFailure;
        private AuthenticationRejection identityRejection;
        private Map<String, TerminalSessionContext> identityContexts = Map.of();
        private RuntimeException nextAuditFailure;
        private SessionAuditType auditFailureType;
        private int registrationAttempts;
        private int auditAttempts;
        private TerminalRegistrationIdentity lastRegistrationIdentity;
        private RegistrationDecision lastRegistrationDecision;
        private Runnable beforeRegistrationApproval;
        private Path observedCapturePath;
        private boolean captureExistedDuringVerification;
        private ProtocolVersion lastIdentityProtocol;
        private String lastIdentityPhone;
        private String lastIdentityDigest;
        private volatile AuditBlock auditBlock;

        private FakeTerminalRegistry(
                RegistrationRejection registrationRejection,
                String activeSafetyStandard,
                List<String> activeSafetyModules) {
            this.registrationRejection = registrationRejection;
            this.activeSafetyStandard = activeSafetyStandard;
            this.activeSafetyModules = List.copyOf(activeSafetyModules);
        }

        static FakeTerminalRegistry approved() {
            return approvedWithProfile("T/JSATL12-2017", List.of("ADAS", "DMS"));
        }

        static FakeTerminalRegistry approvedWithProfile(String standard, List<String> modules) {
            return new FakeTerminalRegistry(null, standard, modules);
        }

        static FakeTerminalRegistry rejected(RegistrationRejection rejection) {
            return new FakeTerminalRegistry(rejection, null, List.of());
        }

        static FakeTerminalRegistry throwingRegistration(RuntimeException failure) {
            FakeTerminalRegistry registry = approved();
            registry.registrationFailure = failure;
            return registry;
        }

        static FakeTerminalRegistry throwingAuthentication(RuntimeException failure) {
            FakeTerminalRegistry registry = approved();
            registry.authenticationFailure = failure;
            return registry;
        }

        void failNextAudit(RuntimeException failure) {
            nextAuditFailure = failure;
            auditFailureType = null;
        }

        void failAudit(SessionAuditType type, RuntimeException failure) {
            auditFailureType = type;
            nextAuditFailure = failure;
        }

        void approveIdentity(String terminalPhone, TerminalSessionContext context) {
            identityContexts = Map.of(terminalPhone, context);
            identityRejection = null;
        }

        void rejectIdentity(AuthenticationRejection rejection) {
            identityContexts = Map.of();
            identityRejection = rejection;
        }

        void failIdentityAuthentication(RuntimeException failure) {
            identityAuthenticationFailure = failure;
        }

        void approveTerminalAuthentication(TerminalSessionContext context) {
            terminalAuthenticationContext = context;
        }

        void beforeRegistrationApproval(Runnable action) {
            beforeRegistrationApproval = action;
        }

        void observeCapturePath(Path output) {
            observedCapturePath = output;
        }

        AuditBlock blockNextAudit(SessionAuditType type, RuntimeException failure) {
            AuditBlock block = new AuditBlock(type, failure);
            auditBlock = block;
            return block;
        }

        @Override
        public RegistrationDecision verifyRegistration(TerminalRegistrationIdentity identity) {
            registrationAttempts++;
            lastRegistrationIdentity = identity;
            captureExistedDuringVerification = observedCapturePath != null
                    && Files.exists(observedCapturePath);
            if (registrationFailure != null) {
                throw registrationFailure;
            }
            if (registrationRejection != null) {
                return RegistrationDecision.rejected(registrationRejection);
            }
            String expectedTerminalNumber = identity.protocolVersion() == ProtocolVersion.JT808_2019
                    ? JT808_2019_TERMINAL_NUMBER
                    : TERMINAL_NUMBER;
            assertEquals(expectedTerminalNumber, identity.terminalNumber());
            assertEquals("MFG01", identity.manufacturerId());
            assertEquals("PILOT-MODEL", identity.model());
            assertEquals("TERM001", identity.terminalCode());
            assertEquals("PILOT-A", identity.vehicleIdentifier());
            RegistrationDecision decision = RegistrationDecision.approved(
                    new TerminalSessionContext(
                            TERMINAL_ID,
                            ONBOARD_SYSTEM_ID,
                            VEHICLE_ID,
                            Set.of("LOCATION_PRIMARY", "ACTIVE_SAFETY"),
                            "WGS84",
                            activeSafetyStandard,
                            activeSafetyModules,
                            5),
                    AUTHENTICATION_TOKEN.getBytes(StandardCharsets.US_ASCII),
                    uncheckedSha256(AUTHENTICATION_TOKEN.getBytes(StandardCharsets.US_ASCII)));
            lastRegistrationDecision = decision;
            if (beforeRegistrationApproval != null) {
                beforeRegistrationApproval.run();
            }
            return decision;
        }

        @Override
        public AuthenticationDecision verifyAuthentication(
                UUID terminalId, int tokenVersion, String presentedTokenSha256) {
            if (authenticationFailure != null) {
                throw authenticationFailure;
            }
            return uncheckedSha256(AUTHENTICATION_TOKEN.getBytes(StandardCharsets.US_ASCII))
                    .equals(presentedTokenSha256)
                    ? AuthenticationDecision.allow(
                            terminalAuthenticationContext == null
                                    ? terminalContext(
                                            terminalId,
                                            Set.of("LOCATION_PRIMARY", "ACTIVE_SAFETY"))
                                    : terminalAuthenticationContext)
                    : AuthenticationDecision.rejected(AuthenticationRejection.TOKEN_MISMATCH);
        }

        @Override
        public AuthenticationDecision verifyAuthenticationByIdentity(
                ProtocolVersion protocolVersion,
                String terminalPhone,
                String presentedTokenSha256) {
            lastIdentityProtocol = protocolVersion;
            lastIdentityPhone = terminalPhone;
            lastIdentityDigest = presentedTokenSha256;
            if (identityAuthenticationFailure != null) {
                throw identityAuthenticationFailure;
            }
            if (identityRejection != null) {
                return AuthenticationDecision.rejected(identityRejection);
            }
            TerminalSessionContext context = identityContexts.get(terminalPhone);
            if (context == null || !uncheckedSha256(
                    AUTHENTICATION_TOKEN.getBytes(StandardCharsets.US_ASCII))
                    .equals(presentedTokenSha256)) {
                return AuthenticationDecision.rejected(AuthenticationRejection.TOKEN_MISMATCH);
            }
            return AuthenticationDecision.allow(context);
        }

        @Override
        public void recordSessionAudit(SessionAuditIngress event) {
            auditAttempts++;
            AuditBlock block = auditBlock;
            if (block != null && block.matches(event.type()) && block.claim()) {
                block.entered();
                block.awaitRelease();
                if (block.failure() != null) {
                    throw block.failure();
                }
            }
            if (nextAuditFailure != null
                    && (auditFailureType == null || auditFailureType == event.type())) {
                RuntimeException failure = nextAuditFailure;
                nextAuditFailure = null;
                auditFailureType = null;
                throw failure;
            }
            audits.add(event);
        }

        SessionAuditIngress lastAudit() {
            return audits.get(audits.size() - 1);
        }

        int registrationAttempts() {
            return registrationAttempts;
        }

        int auditCount() {
            return audits.size();
        }

        int auditAttempts() {
            return auditAttempts;
        }

        boolean captureExistedDuringVerification() {
            return captureExistedDuringVerification;
        }

        TerminalRegistrationIdentity lastRegistrationIdentity() {
            return lastRegistrationIdentity;
        }

        RegistrationDecision lastRegistrationDecision() {
            return lastRegistrationDecision;
        }

        ProtocolVersion lastIdentityProtocol() {
            return lastIdentityProtocol;
        }

        String lastIdentityPhone() {
            return lastIdentityPhone;
        }

        String lastIdentityDigest() {
            return lastIdentityDigest;
        }

        private static String uncheckedSha256(byte[] value) {
            try {
                return sha256(value);
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }
    }

    private static final class AuditBlock {
        private final SessionAuditType type;
        private final RuntimeException failure;
        private final AtomicBoolean claimed = new AtomicBoolean();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private AuditBlock(SessionAuditType type, RuntimeException failure) {
            this.type = type;
            this.failure = failure;
        }

        private boolean matches(SessionAuditType candidate) {
            return type == candidate;
        }

        private boolean claim() {
            return claimed.compareAndSet(false, true);
        }

        private void entered() {
            entered.countDown();
        }

        private boolean awaitEntered(Duration timeout) throws InterruptedException {
            return entered.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        private void awaitRelease() {
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("synthetic audit block timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("synthetic audit block interrupted", interrupted);
            }
        }

        private void release() {
            release.countDown();
        }

        private RuntimeException failure() {
            return failure;
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

    private static final class RegistrationReplyFailure extends ChannelOutboundHandlerAdapter {
        private final RuntimeException failure;

        private RegistrationReplyFailure(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public void write(
                ChannelHandlerContext context,
                Object message,
                ChannelPromise promise) {
            if (message instanceof Jt808Frame frame
                    && frame.header().messageId() == 0x8100) {
                promise.setFailure(failure);
                return;
            }
            context.write(message, promise);
        }
    }
}
