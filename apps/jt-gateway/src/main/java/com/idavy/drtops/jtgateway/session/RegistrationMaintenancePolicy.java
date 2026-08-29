package com.idavy.drtops.jtgateway.session;

import com.idavy.drtops.jt.protocol.codec.ProtocolVersion;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Temporary registration gate that never retains or exposes a plain terminal identity. */
public final class RegistrationMaintenancePolicy {
    public static final String BLOCKED_REASON = "TEMPORARILY_BLOCKED_FOR_MAINTENANCE";
    public static final String EXPIRED_REASON = "MAINTENANCE_WINDOW_EXPIRED";

    private final boolean enabled;
    private final byte[] allowedIdentityDigest;
    private final Instant expiresAt;
    private final Duration auditInterval;
    private final Clock clock;
    private final List<KnownIdentityFingerprint> knownIdentityFingerprints;
    private final AtomicLong allowedAttemptCount = new AtomicLong();
    private final AtomicLong blockedAttemptCount = new AtomicLong();
    private final AtomicLong suppressedAuditCount = new AtomicLong();
    private final Map<String, AuditState> auditStatesBySafeBucket = new HashMap<>();
    private final AtomicLong auditReservationSequence = new AtomicLong();
    private final ConcurrentHashMap<ObservationKey, AtomicLong> fingerprintObservationCounts =
            new ConcurrentHashMap<>();

    private RegistrationMaintenancePolicy(
            boolean enabled,
            byte[] allowedIdentityDigest,
            Instant expiresAt,
            Duration auditInterval,
            Clock clock,
            List<KnownIdentityFingerprint> knownIdentityFingerprints) {
        this.enabled = enabled;
        this.allowedIdentityDigest = allowedIdentityDigest.clone();
        this.expiresAt = expiresAt;
        this.auditInterval = auditInterval;
        this.clock = clock;
        this.knownIdentityFingerprints = List.copyOf(knownIdentityFingerprints);
    }

    public static RegistrationMaintenancePolicy disabled() {
        return new RegistrationMaintenancePolicy(
                false,
                new byte[0],
                Instant.MAX,
                Duration.ofSeconds(60),
                Clock.systemUTC(),
                List.of());
    }

    public static RegistrationMaintenancePolicy enabled(
            String allowedIdentitySha256,
            Instant expiresAt,
            Duration auditInterval,
            Clock clock) {
        return enabled(
                allowedIdentitySha256,
                "",
                expiresAt,
                auditInterval,
                clock);
    }

    public static RegistrationMaintenancePolicy enabled(
            String allowedIdentitySha256,
            String knownIdentityFingerprints,
            Instant expiresAt,
            Duration auditInterval,
            Clock clock) {
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(auditInterval, "auditInterval");
        Objects.requireNonNull(clock, "clock");
        if (allowedIdentitySha256 == null || !allowedIdentitySha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("allowedIdentitySha256 must be a lowercase SHA-256 digest");
        }
        if (auditInterval.isZero() || auditInterval.isNegative()) {
            throw new IllegalArgumentException("auditInterval must be positive");
        }
        if (!expiresAt.isAfter(clock.instant())) {
            throw new IllegalArgumentException("maintenance window must expire in the future");
        }
        return new RegistrationMaintenancePolicy(
                true,
                HexFormat.of().parseHex(allowedIdentitySha256),
                expiresAt,
                auditInterval,
                clock,
                parseKnownIdentityFingerprints(knownIdentityFingerprints));
    }

    public synchronized Evaluation evaluate(ProtocolVersion protocolVersion, String terminalIdentity) {
        Objects.requireNonNull(protocolVersion, "protocolVersion");
        Objects.requireNonNull(terminalIdentity, "terminalIdentity");
        if (!enabled) {
            return Evaluation.allowed("MAINTENANCE_DISABLED", FingerprintMatch.unknown());
        }

        Instant now = clock.instant();
        FingerprintMatch fingerprintMatch = fingerprintMatch(protocolVersion, terminalIdentity);
        observe(fingerprintMatch);
        byte[] candidate = identityDigest(protocolVersion, terminalIdentity);
        boolean expired = !now.isBefore(expiresAt);
        if (!expired && MessageDigest.isEqual(allowedIdentityDigest, candidate)) {
            allowedAttemptCount.incrementAndGet();
            return Evaluation.allowed("MAINTENANCE_ALLOWLIST_MATCH", fingerprintMatch);
        }

        blockedAttemptCount.incrementAndGet();
        String safeBucket = fingerprintMatch.identityMatch() ? fingerprintMatch.alias() : "UNKNOWN";
        AuditState auditState = auditStatesBySafeBucket.computeIfAbsent(safeBucket, ignored -> new AuditState());
        AuditDisposition auditDisposition;
        long reservationId = 0;
        if (auditState.reservationActive) {
            auditDisposition = AuditDisposition.PENDING;
            suppressedAuditCount.incrementAndGet();
        } else if (auditState.lastPersistedAt != null
                && now.isBefore(auditState.lastPersistedAt.plus(auditInterval))) {
            auditDisposition = AuditDisposition.RECENTLY_PERSISTED;
            suppressedAuditCount.incrementAndGet();
        } else {
            auditDisposition = AuditDisposition.REQUIRED;
            reservationId = auditReservationSequence.incrementAndGet();
            auditState.reservationActive = true;
            auditState.reservationId = reservationId;
        }
        return new Evaluation(
                false,
                auditDisposition,
                expired ? EXPIRED_REASON : BLOCKED_REASON,
                fingerprintMatch.alias(),
                fingerprintMatch.identityMatch(),
                fingerprintMatch.protocolMatch(),
                safeBucket,
                reservationId);
    }

    public synchronized void auditPersisted(Evaluation evaluation) {
        completeAuditReservation(evaluation, true);
    }

    public synchronized void auditPersistenceFailed(Evaluation evaluation) {
        completeAuditReservation(evaluation, false);
    }

    public synchronized Snapshot snapshot() {
        List<FingerprintObservation> fingerprintObservations = fingerprintObservationCounts.entrySet()
                .stream()
                .map(entry -> new FingerprintObservation(
                        entry.getKey().alias(),
                        entry.getKey().identityMatch(),
                        entry.getKey().protocolMatch(),
                        entry.getValue().get()))
                .sorted(Comparator.comparing(FingerprintObservation::alias)
                        .thenComparingInt(observation -> observation.identityMatch() ? 1 : 0)
                        .thenComparingInt(observation -> observation.protocolMatch() ? 1 : 0))
                .toList();
        return new Snapshot(
                enabled,
                enabled && !clock.instant().isBefore(expiresAt),
                allowedAttemptCount.get(),
                blockedAttemptCount.get(),
                auditStatesBySafeBucket.size(),
                suppressedAuditCount.get(),
                fingerprintObservations);
    }

    private FingerprintMatch fingerprintMatch(
            ProtocolVersion protocolVersion,
            String terminalIdentity) {
        if (knownIdentityFingerprints.isEmpty()) {
            return FingerprintMatch.unknown();
        }
        byte[] candidate = identityOnlyDigest(terminalIdentity);
        try {
            for (KnownIdentityFingerprint knownIdentity : knownIdentityFingerprints) {
                if (knownIdentity.matches(candidate)) {
                    return new FingerprintMatch(
                            knownIdentity.alias,
                            true,
                            knownIdentity.expectedProtocolVersion == protocolVersion);
                }
            }
            return FingerprintMatch.unknown();
        } finally {
            java.util.Arrays.fill(candidate, (byte) 0);
        }
    }

    private void observe(FingerprintMatch fingerprintMatch) {
        if (knownIdentityFingerprints.isEmpty()) {
            return;
        }
        ObservationKey key = new ObservationKey(
                fingerprintMatch.alias(),
                fingerprintMatch.identityMatch(),
                fingerprintMatch.protocolMatch());
        fingerprintObservationCounts
                .computeIfAbsent(key, ignored -> new AtomicLong())
                .incrementAndGet();
    }

    private static List<KnownIdentityFingerprint> parseKnownIdentityFingerprints(String configured) {
        if (configured == null || configured.isBlank()) {
            return List.of();
        }
        List<KnownIdentityFingerprint> parsed = new ArrayList<>();
        Set<String> aliases = new HashSet<>();
        Set<String> digests = new HashSet<>();
        for (String entry : configured.split(";", -1)) {
            String[] fields = entry.split(":", -1);
            if (fields.length != 3) {
                throw new IllegalArgumentException(
                        "known identity fingerprint entry must contain alias, digest, and protocol");
            }
            String alias = fields[0];
            String digest = fields[1];
            if (!alias.matches("terminal-0[1-4]")) {
                throw new IllegalArgumentException("known identity fingerprint alias is invalid");
            }
            if (!digest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "known identity fingerprint must be a lowercase SHA-256 digest");
            }
            ProtocolVersion expectedProtocolVersion;
            try {
                expectedProtocolVersion = ProtocolVersion.valueOf(fields[2]);
            } catch (IllegalArgumentException malformedProtocol) {
                throw new IllegalArgumentException(
                        "known identity fingerprint protocol is invalid", malformedProtocol);
            }
            if (!aliases.add(alias)) {
                throw new IllegalArgumentException("known identity fingerprint alias is duplicated");
            }
            if (!digests.add(digest)) {
                throw new IllegalArgumentException("known identity fingerprint digest is duplicated");
            }
            parsed.add(new KnownIdentityFingerprint(
                    alias,
                    HexFormat.of().parseHex(digest),
                    expectedProtocolVersion));
        }
        return List.copyOf(parsed);
    }

    private static byte[] identityDigest(ProtocolVersion protocolVersion, String terminalIdentity) {
        byte[] canonical = (protocolVersion.name() + '\0' + terminalIdentity)
                .getBytes(StandardCharsets.UTF_8);
        return sha256(canonical);
    }

    private static byte[] identityOnlyDigest(String terminalIdentity) {
        return sha256(terminalIdentity.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(byte[] canonical) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        } finally {
            java.util.Arrays.fill(canonical, (byte) 0);
        }
    }

    public record Evaluation(
            boolean allowed,
            AuditDisposition auditDisposition,
            String reasonCode,
            String alias,
            boolean identityMatch,
            boolean protocolMatch,
            String auditBucket,
            long auditReservationId) {
        public boolean auditRequired() {
            return auditDisposition == AuditDisposition.REQUIRED;
        }

        public boolean auditPending() {
            return auditDisposition == AuditDisposition.PENDING;
        }

        public boolean recentlyPersisted() {
            return auditDisposition == AuditDisposition.RECENTLY_PERSISTED;
        }

        private static Evaluation allowed(String reasonCode, FingerprintMatch match) {
            return new Evaluation(
                    true, AuditDisposition.NOT_APPLICABLE, reasonCode, match.alias(), match.identityMatch(),
                    match.protocolMatch(), "NONE", 0);
        }
    }

    public enum AuditDisposition {
        NOT_APPLICABLE,
        REQUIRED,
        PENDING,
        RECENTLY_PERSISTED
    }

    public record FingerprintObservation(
            String alias,
            boolean identityMatch,
            boolean protocolMatch,
            long attemptCount) { }

    public record Snapshot(
            boolean enabled,
            boolean expired,
            long allowedAttemptCount,
            long blockedAttemptCount,
            int blockedIdentityCount,
            long suppressedAuditCount,
            List<FingerprintObservation> fingerprintObservations) {
        public Snapshot {
            fingerprintObservations = List.copyOf(fingerprintObservations);
        }
    }

    private record FingerprintMatch(
            String alias,
            boolean identityMatch,
            boolean protocolMatch) {
        private static FingerprintMatch unknown() {
            return new FingerprintMatch("UNKNOWN", false, false);
        }
    }

    private record ObservationKey(
            String alias,
            boolean identityMatch,
            boolean protocolMatch) { }

    private void completeAuditReservation(Evaluation evaluation, boolean persisted) {
        Objects.requireNonNull(evaluation, "evaluation");
        if (!evaluation.auditRequired() || evaluation.auditReservationId() < 1) {
            return;
        }
        AuditState state = auditStatesBySafeBucket.get(evaluation.auditBucket());
        if (state == null || !state.reservationActive
                || state.reservationId != evaluation.auditReservationId()) {
            return;
        }
        if (persisted) {
            state.lastPersistedAt = clock.instant();
        }
        state.reservationActive = false;
        state.reservationId = 0;
    }

    private static final class AuditState {
        private Instant lastPersistedAt;
        private boolean reservationActive;
        private long reservationId;
    }

    private static final class KnownIdentityFingerprint {
        private final String alias;
        private final byte[] identityDigest;
        private final ProtocolVersion expectedProtocolVersion;

        private KnownIdentityFingerprint(
                String alias,
                byte[] identityDigest,
                ProtocolVersion expectedProtocolVersion) {
            this.alias = alias;
            this.identityDigest = identityDigest.clone();
            this.expectedProtocolVersion = expectedProtocolVersion;
        }

        private boolean matches(byte[] candidate) {
            return MessageDigest.isEqual(identityDigest, candidate);
        }
    }
}
