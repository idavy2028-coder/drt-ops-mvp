package com.idavy.drtops.jtgateway.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.List;
import java.util.UUID;

public final class RegistrationDecision {
    private final boolean approved;
    private final UUID terminalId;
    private final UUID vehicleId;
    private final String sourceCoordinateSystem;
    private final String activeSafetyStandard;
    private final List<String> activeSafetyModules;
    private final int tokenVersion;
    private final byte[] authenticationToken;
    private final String authenticationTokenSha256;
    private final RegistrationRejection rejection;

    private RegistrationDecision(
            boolean approved,
            UUID terminalId,
            UUID vehicleId,
            String sourceCoordinateSystem,
            String activeSafetyStandard,
            List<String> activeSafetyModules,
            int tokenVersion,
            byte[] authenticationToken,
            String authenticationTokenSha256,
            RegistrationRejection rejection) {
        this.approved = approved;
        this.terminalId = terminalId;
        this.vehicleId = vehicleId;
        this.sourceCoordinateSystem = sourceCoordinateSystem;
        this.activeSafetyStandard = activeSafetyStandard;
        this.activeSafetyModules = activeSafetyModules == null ? List.of() : List.copyOf(activeSafetyModules);
        this.tokenVersion = tokenVersion;
        this.authenticationToken = authenticationToken == null ? null : authenticationToken.clone();
        this.authenticationTokenSha256 = authenticationTokenSha256;
        this.rejection = rejection;
    }

    public static RegistrationDecision issue(
            UUID terminalId,
            UUID vehicleId,
            String sourceCoordinateSystem,
            int tokenVersion,
            SecureRandom random) {
        Objects.requireNonNull(random, "random");
        byte[] entropy = new byte[32];
        random.nextBytes(entropy);
        byte[] token = Base64.getUrlEncoder().withoutPadding().encode(entropy);
        java.util.Arrays.fill(entropy, (byte) 0);
        return approved(terminalId, vehicleId, sourceCoordinateSystem, null, List.of(), tokenVersion, token, sha256(token));
    }

    public static RegistrationDecision approved(
            UUID terminalId,
            UUID vehicleId,
            String sourceCoordinateSystem,
            int tokenVersion,
            byte[] authenticationToken,
            String tokenSha256) {
        return approved(terminalId, vehicleId, sourceCoordinateSystem, null, List.of(), tokenVersion,
                authenticationToken, tokenSha256);
    }

    public static RegistrationDecision approved(
            UUID terminalId,
            UUID vehicleId,
            String sourceCoordinateSystem,
            String activeSafetyStandard,
            List<String> activeSafetyModules,
            int tokenVersion,
            byte[] authenticationToken,
            String tokenSha256) {
        Objects.requireNonNull(terminalId, "terminalId");
        Objects.requireNonNull(vehicleId, "vehicleId");
        if (!"WGS84".equals(sourceCoordinateSystem) && !"GCJ02".equals(sourceCoordinateSystem)) {
            throw new IllegalArgumentException("sourceCoordinateSystem must be WGS84 or GCJ02");
        }
        Objects.requireNonNull(authenticationToken, "authenticationToken");
        Objects.requireNonNull(tokenSha256, "tokenSha256");
        if (tokenVersion < 1 || authenticationToken.length < 1) {
            throw new IllegalArgumentException("approved registration requires a token version and token");
        }
        if (!MessageDigest.isEqual(
                sha256(authenticationToken).getBytes(StandardCharsets.US_ASCII),
                tokenSha256.getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("token digest does not match");
        }
        return new RegistrationDecision(
                true, terminalId, vehicleId, sourceCoordinateSystem,
                activeSafetyStandard, activeSafetyModules, tokenVersion, authenticationToken, tokenSha256, null);
    }

    public static RegistrationDecision rejected(RegistrationRejection rejection) {
        return new RegistrationDecision(false, null, null, null, null, List.of(), 0, null, null,
                Objects.requireNonNull(rejection, "rejection"));
    }

    public boolean approved() {
        return approved;
    }

    public UUID terminalId() {
        return terminalId;
    }

    public UUID vehicleId() {
        return vehicleId;
    }

    public String sourceCoordinateSystem() {
        return sourceCoordinateSystem;
    }

    public String activeSafetyStandard() {
        return activeSafetyStandard;
    }

    public List<String> activeSafetyModules() {
        return activeSafetyModules;
    }

    public int tokenVersion() {
        return tokenVersion;
    }

    public byte[] authenticationToken() {
        return authenticationToken == null ? null : authenticationToken.clone();
    }

    public String authenticationTokenSha256() {
        return authenticationTokenSha256;
    }

    public RegistrationRejection rejection() {
        return rejection;
    }

    @Override
    public String toString() {
        return approved
                ? "RegistrationDecision[approved=true, terminalId=" + terminalId
                        + ", tokenVersion=" + tokenVersion + ", authenticationToken=REDACTED]"
                : "RegistrationDecision[approved=false, rejection=" + rejection + "]";
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
