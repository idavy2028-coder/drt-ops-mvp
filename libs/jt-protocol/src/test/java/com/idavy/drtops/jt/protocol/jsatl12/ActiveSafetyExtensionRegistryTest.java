package com.idavy.drtops.jt.protocol.jsatl12;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idavy.drtops.jt.protocol.codec.Jt808MessageHeader;
import com.idavy.drtops.jt.protocol.codec.ProtocolVersion;
import com.idavy.drtops.jt.protocol.core.LocationReport;
import com.idavy.drtops.jt.protocol.core.LocationReportCodec;
import io.netty.buffer.Unpooled;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class ActiveSafetyExtensionRegistryTest {

    @Test
    void selectsOnlyTheExtensionDeclaredByTheTerminalCapability() {
        ActiveSafetyExtensionRegistry registry = new ActiveSafetyExtensionRegistry(
                List.of(new Jsatl12ActiveSafetyModule()));

        ActiveSafetyDecodeResult result = registry.decode(
                new ActiveSafetyCapabilityProfile("T/JSATL12-2017", List.of("ADAS")),
                location("000000000000000201e848000708898000140258005a260115100000642f0000100101010137080000003c001401e8480007088980260115100000000030303030303030260115100000010200"));

        assertEquals(1, result.alarms().size());
        assertEquals("ADAS", result.alarms().getFirst().module());
    }

    @Test
    void dropsARejectionThatBelongsOnlyToADisabledModule() {
        ActiveSafetyExtensionRegistry registry = new ActiveSafetyExtensionRegistry(
                List.of(new Jsatl12ActiveSafetyModule()));

        ActiveSafetyDecodeResult result = registry.decode(
                new ActiveSafetyCapabilityProfile("T/JSATL12-2017", List.of("ADAS")),
                location("000000000000000201e848000708898000140258005a260115100000650100"));

        assertTrue(result.alarms().isEmpty());
        assertTrue(result.rejections().isEmpty());
    }

    @Test
    void rejectsAnUnimplementedStandardWithoutFallingBackToJsatl12() {
        ActiveSafetyExtensionRegistry registry = new ActiveSafetyExtensionRegistry(
                List.of(new Jsatl12ActiveSafetyModule()));

        ActiveSafetyDecodeResult result = registry.decode(
                new ActiveSafetyCapabilityProfile("T/GD-ACTIVE-SAFETY", List.of("ADAS")),
                location("000000000000000201e848000708898000140258005a260115100000642f0000100101010137080000003c001401e8480007088980260115100000000030303030303030260115100000010200"));

        assertTrue(result.alarms().isEmpty());
        assertEquals("UNSUPPORTED_ACTIVE_SAFETY_STANDARD", result.rejections().getFirst().reasonCode());
    }

    @Test
    void isolatesAThrowingExtensionAndPreservesTheAlreadyDecodedLocation() {
        ActiveSafetyExtension broken = new ActiveSafetyExtension() {
            @Override public String standardCode() { return "BROKEN"; }
            @Override public boolean supports(ActiveSafetyCapabilityProfile profile) { return true; }
            @Override public ActiveSafetyDecodeResult decode(LocationReport position) {
                throw new IllegalArgumentException("fixture failure");
            }
        };
        LocationReport position = location("000000000000000201e848000708898000140258005a260115100000");

        ActiveSafetyDecodeResult result = new ActiveSafetyExtensionRegistry(List.of(broken)).decode(
                new ActiveSafetyCapabilityProfile("BROKEN", List.of("ADAS")), position);

        assertEquals("32.000000", position.latitude().toPlainString());
        assertTrue(result.alarms().isEmpty());
        assertEquals("ACTIVE_SAFETY_EXTENSION_REJECTED", result.rejections().getFirst().reasonCode());
    }

    private static LocationReport location(String bodyHex) {
        byte[] body = HexFormat.of().parseHex(bodyHex);
        return new LocationReportCodec().decode(new Jt808MessageHeader(
                0x0200, body.length, body.length, 0, false, ProtocolVersion.JT808_2013, 0,
                "000000000000", 1, null, null), Unpooled.wrappedBuffer(body));
    }
}
