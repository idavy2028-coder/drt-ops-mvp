package com.idavy.drtops.domain.terminal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

final class TerminalPhoneIdentity {

    private static final int JT808_2013_HEADER_DIGITS = 12;
    private static final int JT808_2019_HEADER_DIGITS = 20;

    private TerminalPhoneIdentity() {
    }

    static String canonicalForPersistence(String phone, String protocolVersion) {
        if (phone == null) {
            return null;
        }
        String canonicalProtocol = canonicalProtocolVersion(protocolVersion);
        if (canonicalProtocol == null || !phone.matches("\\d+")) {
            return phone;
        }
        int headerDigits = headerDigits(canonicalProtocol);
        if (phone.length() > headerDigits) {
            return phone;
        }
        return "0".repeat(JT808_2019_HEADER_DIGITS - phone.length()) + phone;
    }

    static boolean matches(String stored, String presented, String storedProtocolVersion) {
        if (stored == null || presented == null) {
            return false;
        }
        String canonicalProtocol = canonicalProtocolVersion(storedProtocolVersion);
        if (canonicalProtocol == null || !stored.matches("\\d+") || !presented.matches("\\d+")) {
            return secureEquals(stored, presented);
        }
        int headerDigits = headerDigits(canonicalProtocol);
        if (stored.length() > headerDigits || presented.length() > headerDigits) {
            return false;
        }
        return secureEquals(
                canonicalForPersistence(stored, canonicalProtocol),
                canonicalForPersistence(presented, canonicalProtocol));
    }

    static String canonicalProtocolVersion(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "JT808_2013", "JT/T 808-2013", "JT/T808-2013" -> "JT808_2013";
            case "JT808_2019", "JT/T 808-2019", "JT/T808-2019" -> "JT808_2019";
            default -> null;
        };
    }

    private static int headerDigits(String canonicalProtocol) {
        return "JT808_2019".equals(canonicalProtocol)
                ? JT808_2019_HEADER_DIGITS
                : JT808_2013_HEADER_DIGITS;
    }

    private static boolean secureEquals(String expected, String presented) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
