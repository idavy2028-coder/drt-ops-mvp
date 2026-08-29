package com.idavy.drtops.jtgateway.session;

import com.idavy.drtops.jt.protocol.codec.ProtocolVersion;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistrationMaintenancePolicyTest {
    private static final String ALLOWED_IDENTITY_DIGEST =
            "f72828e7de880d048453dbfc9c96bed75540e613649a95f119ba0caa4e1119d9";

    @Test
    void disabledPolicyAllowsRegistrationWithoutMaintenanceAudit() {
        RegistrationMaintenancePolicy.Evaluation evaluation =
                RegistrationMaintenancePolicy.disabled().evaluate(
                        ProtocolVersion.JT808_2013, "999999999999");

        assertTrue(evaluation.allowed());
        assertFalse(evaluation.auditRequired());
        assertEquals("MAINTENANCE_DISABLED", evaluation.reasonCode());
    }

    @Test
    void allowsOnlyConfiguredProtocolAndTerminalIdentityWithoutExposingDigest() {
        MutableClock clock = new MutableClock();
        RegistrationMaintenancePolicy policy = RegistrationMaintenancePolicy.enabled(
                ALLOWED_IDENTITY_DIGEST,
                clock.instant().plus(Duration.ofMinutes(10)),
                Duration.ofSeconds(60),
                clock);

        RegistrationMaintenancePolicy.Evaluation allowed = policy.evaluate(
                ProtocolVersion.JT808_2013, "123456789012");
        RegistrationMaintenancePolicy.Evaluation wrongIdentity = policy.evaluate(
                ProtocolVersion.JT808_2013, "999999999999");
        RegistrationMaintenancePolicy.Evaluation wrongProtocol = policy.evaluate(
                ProtocolVersion.JT808_2019, "123456789012");

        assertTrue(allowed.allowed());
        assertFalse(wrongIdentity.allowed());
        assertTrue(wrongIdentity.auditRequired());
        assertEquals("TEMPORARILY_BLOCKED_FOR_MAINTENANCE", wrongIdentity.reasonCode());
        assertFalse(wrongProtocol.allowed());
        assertEquals(1, policy.snapshot().allowedAttemptCount());
        assertEquals(2, policy.snapshot().blockedAttemptCount());
        assertEquals(1, policy.snapshot().blockedIdentityCount(),
                "unknown network identities must share one bounded audit bucket");
        assertFalse(policy.snapshot().toString().contains(ALLOWED_IDENTITY_DIGEST));
    }

    @Test
    void rateLimitsDurableBlockedAuditPerIdentityButCountsEveryAttempt() {
        MutableClock clock = new MutableClock();
        RegistrationMaintenancePolicy policy = RegistrationMaintenancePolicy.enabled(
                ALLOWED_IDENTITY_DIGEST,
                clock.instant().plus(Duration.ofMinutes(10)),
                Duration.ofSeconds(60),
                clock);

        RegistrationMaintenancePolicy.Evaluation first = policy.evaluate(
                ProtocolVersion.JT808_2013, "999999999999");
        policy.auditPersisted(first);
        RegistrationMaintenancePolicy.Evaluation suppressed = policy.evaluate(
                ProtocolVersion.JT808_2013, "999999999999");
        clock.advance(Duration.ofSeconds(60));
        RegistrationMaintenancePolicy.Evaluation nextWindow = policy.evaluate(
                ProtocolVersion.JT808_2013, "999999999999");

        assertTrue(first.auditRequired());
        assertFalse(suppressed.auditRequired());
        assertTrue(nextWindow.auditRequired());
        assertEquals(3, policy.snapshot().blockedAttemptCount());
        assertEquals(1, policy.snapshot().blockedIdentityCount());
        assertEquals(1, policy.snapshot().suppressedAuditCount());
    }

    @Test
    void expiresClosedAndRejectsInvalidOrAlreadyExpiredConfiguration() {
        MutableClock clock = new MutableClock();
        RegistrationMaintenancePolicy policy = RegistrationMaintenancePolicy.enabled(
                ALLOWED_IDENTITY_DIGEST,
                clock.instant().plus(Duration.ofSeconds(30)),
                Duration.ofSeconds(60),
                clock);
        clock.advance(Duration.ofSeconds(31));

        RegistrationMaintenancePolicy.Evaluation expired = policy.evaluate(
                ProtocolVersion.JT808_2013, "123456789012");

        assertFalse(expired.allowed());
        assertEquals("MAINTENANCE_WINDOW_EXPIRED", expired.reasonCode());
        assertTrue(policy.snapshot().expired());
        assertThrows(IllegalArgumentException.class, () -> RegistrationMaintenancePolicy.enabled(
                "not-a-sha256", clock.instant().plusSeconds(30), Duration.ofSeconds(60), clock));
        assertThrows(IllegalArgumentException.class, () -> RegistrationMaintenancePolicy.enabled(
                ALLOWED_IDENTITY_DIGEST, clock.instant(), Duration.ofSeconds(60), clock));
    }

    @Test
    void reportsSafeIdentityAndProtocolMatchesWithoutRetainingSensitiveFingerprintMaterial() {
        MutableClock clock = new MutableClock();
        String terminal01Identity = "123456789012";
        String terminal02Identity = "999999999999";
        String unknownIdentity = "000000000003";
        String terminal01IdentityDigest = identityOnlyDigest(terminal01Identity);
        String terminal02IdentityDigest = identityOnlyDigest(terminal02Identity);
        String knownIdentityFingerprints = String.join(";",
                "terminal-01:" + terminal01IdentityDigest + ":JT808_2019",
                "terminal-02:" + terminal02IdentityDigest + ":JT808_2013");
        RegistrationMaintenancePolicy policy = enabledWithFingerprintDiagnostics(
                compositeDigest(ProtocolVersion.JT808_2019, terminal01Identity),
                knownIdentityFingerprints,
                clock.instant().plus(Duration.ofMinutes(10)),
                Duration.ofSeconds(60),
                clock);

        RegistrationMaintenancePolicy.Evaluation protocolMismatch = policy.evaluate(
                ProtocolVersion.JT808_2013, terminal01Identity);
        RegistrationMaintenancePolicy.Evaluation knownNonTarget = policy.evaluate(
                ProtocolVersion.JT808_2013, terminal02Identity);
        RegistrationMaintenancePolicy.Evaluation unknown = policy.evaluate(
                ProtocolVersion.JT808_2019, unknownIdentity);

        assertFalse(protocolMismatch.allowed());
        assertFingerprintDiagnostic(protocolMismatch, "terminal-01", true, false);
        assertFalse(knownNonTarget.allowed());
        assertFingerprintDiagnostic(knownNonTarget, "terminal-02", true, true);
        assertFalse(unknown.allowed());
        assertFingerprintDiagnostic(unknown, "UNKNOWN", false, false);

        Object observationsValue = invokeAccessor(policy.snapshot(), "fingerprintObservations");
        assertTrue(observationsValue instanceof List<?>);
        List<?> observations = (List<?>) observationsValue;
        assertEquals(3, observations.size());
        assertObservation(observations, "terminal-01", true, false, 1L);
        assertObservation(observations, "terminal-02", true, true, 1L);
        assertObservation(observations, "UNKNOWN", false, false, 1L);

        String safeDiagnosticText = protocolMismatch + "|" + knownNonTarget + "|" + unknown
                + "|" + policy.snapshot();
        assertFalse(safeDiagnosticText.contains(terminal01Identity));
        assertFalse(safeDiagnosticText.contains(terminal02Identity));
        assertFalse(safeDiagnosticText.contains(unknownIdentity));
        assertFalse(safeDiagnosticText.contains(terminal01IdentityDigest));
        assertFalse(safeDiagnosticText.contains(terminal02IdentityDigest));
    }

    @Test
    void highCardinalityUnknownIdentitiesShareOneBoundedAuditBucket() {
        MutableClock clock = new MutableClock();
        RegistrationMaintenancePolicy policy = RegistrationMaintenancePolicy.enabled(
                ALLOWED_IDENTITY_DIGEST,
                clock.instant().plus(Duration.ofMinutes(10)),
                Duration.ofSeconds(60),
                clock);

        for (int index = 0; index < 10_000; index++) {
            policy.evaluate(ProtocolVersion.JT808_2013, String.format("%012d", 800_000_000_000L + index));
        }

        assertEquals(10_000, policy.snapshot().blockedAttemptCount());
        assertEquals(1, policy.snapshot().blockedIdentityCount(),
                "arbitrary network identities must not grow maintenance state");
        assertEquals(9_999, policy.snapshot().suppressedAuditCount());
    }

    @Test
    void knownIdentitiesUseSafeAliasBucketsAndUnknownsUseOneAggregateBucket() {
        MutableClock clock = new MutableClock();
        String knownIdentityFingerprints = String.join(";",
                "terminal-01:" + identityOnlyDigest("100000000001") + ":JT808_2019",
                "terminal-02:" + identityOnlyDigest("100000000002") + ":JT808_2019",
                "terminal-03:" + identityOnlyDigest("100000000003") + ":JT808_2019",
                "terminal-04:" + identityOnlyDigest("100000000004") + ":JT808_2019");
        RegistrationMaintenancePolicy policy = RegistrationMaintenancePolicy.enabled(
                ALLOWED_IDENTITY_DIGEST,
                knownIdentityFingerprints,
                clock.instant().plus(Duration.ofMinutes(10)),
                Duration.ofSeconds(60),
                clock);

        for (int index = 1; index <= 4; index++) {
            RegistrationMaintenancePolicy.Evaluation evaluation = policy.evaluate(
                    ProtocolVersion.JT808_2013, "10000000000" + index);
            assertTrue(evaluation.auditRequired());
            policy.auditPersisted(evaluation);
        }
        RegistrationMaintenancePolicy.Evaluation unknown = policy.evaluate(
                ProtocolVersion.JT808_2013, "999999999999");
        policy.auditPersisted(unknown);

        assertEquals(5, policy.snapshot().blockedIdentityCount());
    }

    @Test
    void concurrentUnknownAttemptsReserveOneAuditAndOpenTheNextIntervalAfterPersistence() throws Exception {
        MutableClock clock = new MutableClock();
        RegistrationMaintenancePolicy policy = RegistrationMaintenancePolicy.enabled(
                ALLOWED_IDENTITY_DIGEST,
                clock.instant().plus(Duration.ofMinutes(10)),
                Duration.ofSeconds(60),
                clock);
        int workers = 32;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RegistrationMaintenancePolicy.Evaluation>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < workers; index++) {
                int identity = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return policy.evaluate(
                            ProtocolVersion.JT808_2013,
                            String.format("%012d", 700_000_000_000L + identity));
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<RegistrationMaintenancePolicy.Evaluation> evaluations = new ArrayList<>();
            for (Future<RegistrationMaintenancePolicy.Evaluation> future : futures) {
                evaluations.add(future.get(5, TimeUnit.SECONDS));
            }

            List<RegistrationMaintenancePolicy.Evaluation> reserved = evaluations.stream()
                    .filter(RegistrationMaintenancePolicy.Evaluation::auditRequired)
                    .toList();
            assertEquals(1, reserved.size());
            policy.auditPersisted(reserved.get(0));
            assertFalse(policy.evaluate(
                    ProtocolVersion.JT808_2013, "699999999999").auditRequired());
            clock.advance(Duration.ofSeconds(60));
            assertTrue(policy.evaluate(
                    ProtocolVersion.JT808_2013, "699999999998").auditRequired());
            assertEquals(1, policy.snapshot().blockedIdentityCount());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static RegistrationMaintenancePolicy enabledWithFingerprintDiagnostics(
            String allowedIdentityDigest,
            String knownIdentityFingerprints,
            Instant expiresAt,
            Duration auditInterval,
            Clock clock) {
        try {
            Method method = RegistrationMaintenancePolicy.class.getMethod(
                    "enabled",
                    String.class,
                    String.class,
                    Instant.class,
                    Duration.class,
                    Clock.class);
            return (RegistrationMaintenancePolicy) method.invoke(
                    null,
                    allowedIdentityDigest,
                    knownIdentityFingerprints,
                    expiresAt,
                    auditInterval,
                    clock);
        } catch (NoSuchMethodException exception) {
            throw new AssertionError(
                    "RED: maintenance policy does not accept safe known-identity fingerprints", exception);
        } catch (IllegalAccessException exception) {
            throw new AssertionError("maintenance policy factory is not accessible", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new AssertionError("maintenance policy factory failed", cause);
        }
    }

    private static void assertFingerprintDiagnostic(
            Object evaluation,
            String alias,
            boolean identityMatch,
            boolean protocolMatch) {
        assertEquals(alias, invokeAccessor(evaluation, "alias"));
        assertEquals(identityMatch, invokeAccessor(evaluation, "identityMatch"));
        assertEquals(protocolMatch, invokeAccessor(evaluation, "protocolMatch"));
    }

    private static void assertObservation(
            List<?> observations,
            String alias,
            boolean identityMatch,
            boolean protocolMatch,
            long attemptCount) {
        Object observation = observations.stream()
                .filter(candidate -> alias.equals(invokeAccessor(candidate, "alias")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing safe observation for " + alias));
        assertEquals(identityMatch, invokeAccessor(observation, "identityMatch"));
        assertEquals(protocolMatch, invokeAccessor(observation, "protocolMatch"));
        assertEquals(attemptCount,
                ((Number) invokeAccessor(observation, "attemptCount")).longValue());
    }

    private static Object invokeAccessor(Object target, String accessor) {
        try {
            return target.getClass().getMethod(accessor).invoke(target);
        } catch (NoSuchMethodException exception) {
            throw new AssertionError("RED: missing safe diagnostic accessor " + accessor, exception);
        } catch (IllegalAccessException | InvocationTargetException exception) {
            throw new AssertionError("safe diagnostic accessor failed: " + accessor, exception);
        }
    }

    private static String identityOnlyDigest(String terminalIdentity) {
        return sha256(terminalIdentity);
    }

    private static String compositeDigest(
            ProtocolVersion protocolVersion,
            String terminalIdentity) {
        return sha256(protocolVersion.name() + '\0' + terminalIdentity);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant current = Instant.parse("2026-08-25T00:00:00Z");

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }
}
