package com.idavy.drtops.jtgateway.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class RegistrationDecision {
    private final boolean approved;
    private final TerminalSessionContext context;
    private final byte[] authenticationToken;
    private boolean authenticationTokenConsumed;
    private final String authenticationTokenSha256;
    private final RegistrationRejection rejection;

    private RegistrationDecision(
            boolean approved,
            TerminalSessionContext context,
            byte[] authenticationToken,
            String authenticationTokenSha256,
            RegistrationRejection rejection) {
        this.approved = approved;
        this.context = context;
        this.authenticationToken = authenticationToken == null ? null : authenticationToken.clone();
        this.authenticationTokenSha256 = authenticationTokenSha256;
        this.rejection = rejection;
    }

    public static RegistrationDecision issue(
            TerminalSessionContext context,
            SecureRandom random) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(random, "random");
        byte[] entropy = new byte[32];
        byte[] token = null;
        try {
            random.nextBytes(entropy);
            token = Base64.getUrlEncoder().withoutPadding().encode(entropy);
            return approved(context, token, sha256(token));
        } finally {
            java.util.Arrays.fill(entropy, (byte) 0);
            if (token != null) {
                java.util.Arrays.fill(token, (byte) 0);
            }
        }
    }

    /** @deprecated approval without an onboard-system context is no longer valid. */
    @Deprecated(forRemoval = false)
    public static RegistrationDecision issue(
            UUID terminalId,
            UUID vehicleId,
            String sourceCoordinateSystem,
            int tokenVersion,
            SecureRandom random) {
        throw new IllegalArgumentException(
                "approved registration requires an onboard-system context");
    }

    /** @deprecated approval without an onboard-system context is no longer valid. */
    @Deprecated(forRemoval = false)
    public static RegistrationDecision approved(
            UUID terminalId,
            UUID vehicleId,
            String sourceCoordinateSystem,
            int tokenVersion,
            byte[] authenticationToken,
            String tokenSha256) {
        throw new IllegalArgumentException(
                "approved registration requires an onboard-system context");
    }

    /** @deprecated approval without an onboard-system context is no longer valid. */
    @Deprecated(forRemoval = false)
    public static RegistrationDecision approved(
            UUID terminalId,
            UUID vehicleId,
            String sourceCoordinateSystem,
            String activeSafetyStandard,
            List<String> activeSafetyModules,
            int tokenVersion,
            byte[] authenticationToken,
            String tokenSha256) {
        throw new IllegalArgumentException(
                "approved registration requires an onboard-system context");
    }

    public static RegistrationDecision approved(
            TerminalSessionContext context,
            byte[] authenticationToken,
            String tokenSha256) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(authenticationToken, "authenticationToken");
        Objects.requireNonNull(tokenSha256, "tokenSha256");
        if (authenticationToken.length < 1) {
            throw new IllegalArgumentException("approved registration requires a token");
        }
        if (!MessageDigest.isEqual(
                sha256(authenticationToken).getBytes(StandardCharsets.US_ASCII),
                tokenSha256.getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("token digest does not match");
        }
        return new RegistrationDecision(
                true, context, authenticationToken, tokenSha256, null);
    }

    public static RegistrationDecision rejected(RegistrationRejection rejection) {
        return new RegistrationDecision(false, null, null, null,
                Objects.requireNonNull(rejection, "rejection"));
    }

    public boolean approved() {
        return approved;
    }

    public UUID terminalId() {
        return context == null ? null : context.terminalId();
    }

    public UUID onboardSystemId() {
        return context == null ? null : context.onboardSystemId();
    }

    public UUID vehicleId() {
        return context == null ? null : context.vehicleId();
    }

    public String sourceCoordinateSystem() {
        return context == null ? null : context.sourceCoordinateSystem();
    }

    public String activeSafetyStandard() {
        return context == null ? null : context.activeSafetyStandard();
    }

    public List<String> activeSafetyModules() {
        return context == null ? List.of() : context.activeSafetyModules();
    }

    public Set<String> roles() {
        return context == null ? Set.of() : context.roles();
    }

    public int tokenVersion() {
        return context == null ? 0 : context.tokenVersion();
    }

    public TerminalSessionContext context() {
        return context;
    }

    public synchronized byte[] consumeAuthenticationToken() {
        if (!approved || authenticationToken == null || authenticationTokenConsumed) {
            throw new IllegalStateException("authentication token is not available");
        }
        authenticationTokenConsumed = true;
        return authenticationToken;
    }

    public synchronized void destroyAuthenticationToken() {
        if (authenticationToken != null) {
            java.util.Arrays.fill(authenticationToken, (byte) 0);
        }
        authenticationTokenConsumed = true;
    }

    public synchronized boolean hasAvailableAuthenticationToken() {
        return approved && authenticationToken != null && !authenticationTokenConsumed;
    }

    public synchronized boolean authenticationTokenDestroyed() {
        if (authenticationToken == null) {
            return true;
        }
        for (byte value : authenticationToken) {
            if (value != 0) {
                return false;
            }
        }
        return true;
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
                ? "RegistrationDecision[approved=true, terminalId=" + context.terminalId()
                        + ", tokenVersion=" + context.tokenVersion()
                        + ", authenticationToken=REDACTED]"
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
