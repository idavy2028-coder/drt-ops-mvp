package com.idavy.drtops.jtgateway.session;

import com.idavy.drtops.jt.protocol.codec.ProtocolVersion;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistrationBodyLayoutPolicyTest {

    @Test
    void defaultsClosedAndAllowsOnlyAnExactProtocolIdentityDigest() throws Exception {
        String identity = "00000000123456789012";
        String digest = compositeDigest(ProtocolVersion.JT808_2019, identity);

        RegistrationBodyLayoutPolicy disabled = RegistrationBodyLayoutPolicy.disabled();
        RegistrationBodyLayoutPolicy configured =
                RegistrationBodyLayoutPolicy.fromCommaSeparated(digest);

        assertFalse(disabled.allowsLegacy2013Widths(ProtocolVersion.JT808_2019, identity));
        assertTrue(configured.allowsLegacy2013Widths(ProtocolVersion.JT808_2019, identity));
        assertFalse(configured.allowsLegacy2013Widths(
                ProtocolVersion.JT808_2019, "00000000999999999999"));
        assertFalse(configured.allowsLegacy2013Widths(ProtocolVersion.JT808_2013, identity));
        String diagnostic = configured.toString();
        assertFalse(diagnostic.contains(identity));
        assertFalse(diagnostic.contains(digest));
    }

    @Test
    void rejectsMalformedOrDuplicateCompatibilityDigests() {
        String digest = "a".repeat(64);
        assertThrows(IllegalArgumentException.class,
                () -> RegistrationBodyLayoutPolicy.fromCommaSeparated("not-a-digest"));
        assertThrows(IllegalArgumentException.class,
                () -> RegistrationBodyLayoutPolicy.fromCommaSeparated(digest + "," + digest));
    }

    private static String compositeDigest(ProtocolVersion version, String identity) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                (version.name() + '\0' + identity).getBytes(StandardCharsets.UTF_8)));
    }
}
