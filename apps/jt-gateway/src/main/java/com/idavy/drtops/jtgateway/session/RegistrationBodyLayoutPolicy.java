package com.idavy.drtops.jtgateway.session;

import com.idavy.drtops.jt.protocol.codec.ProtocolVersion;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Explicit, default-closed compatibility for approved protocol identities using legacy registration widths. */
public final class RegistrationBodyLayoutPolicy {
    private final List<byte[]> legacyJt8082019IdentityDigests;

    private RegistrationBodyLayoutPolicy(List<byte[]> configuredDigests) {
        legacyJt8082019IdentityDigests = configuredDigests.stream()
                .map(byte[]::clone)
                .toList();
    }

    public static RegistrationBodyLayoutPolicy disabled() {
        return new RegistrationBodyLayoutPolicy(List.of());
    }

    public static RegistrationBodyLayoutPolicy fromCommaSeparated(String configuredDigests) {
        if (configuredDigests == null || configuredDigests.isBlank()) {
            return disabled();
        }
        List<byte[]> parsed = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (String entry : configuredDigests.split(",", -1)) {
            String digest = entry.trim();
            if (!digest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "legacy registration layout identity must be a lowercase SHA-256 digest");
            }
            if (!unique.add(digest)) {
                throw new IllegalArgumentException(
                        "legacy registration layout identity digest is duplicated");
            }
            parsed.add(HexFormat.of().parseHex(digest));
        }
        return new RegistrationBodyLayoutPolicy(parsed);
    }

    public boolean allowsLegacy2013Widths(
            ProtocolVersion protocolVersion,
            String terminalIdentity) {
        Objects.requireNonNull(protocolVersion, "protocolVersion");
        Objects.requireNonNull(terminalIdentity, "terminalIdentity");
        if (protocolVersion != ProtocolVersion.JT808_2019
                || legacyJt8082019IdentityDigests.isEmpty()) {
            return false;
        }
        byte[] candidate = compositeDigest(protocolVersion, terminalIdentity);
        try {
            return legacyJt8082019IdentityDigests.stream()
                    .anyMatch(configured -> MessageDigest.isEqual(configured, candidate));
        } finally {
            Arrays.fill(candidate, (byte) 0);
        }
    }

    public int configuredIdentityCount() {
        return legacyJt8082019IdentityDigests.size();
    }

    @Override
    public String toString() {
        return "RegistrationBodyLayoutPolicy[enabled="
                + !legacyJt8082019IdentityDigests.isEmpty()
                + ", configuredIdentityCount=" + configuredIdentityCount() + "]";
    }

    private static byte[] compositeDigest(
            ProtocolVersion protocolVersion,
            String terminalIdentity) {
        byte[] canonical = (protocolVersion.name() + '\0' + terminalIdentity)
                .getBytes(StandardCharsets.UTF_8);
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        } finally {
            Arrays.fill(canonical, (byte) 0);
        }
    }
}
