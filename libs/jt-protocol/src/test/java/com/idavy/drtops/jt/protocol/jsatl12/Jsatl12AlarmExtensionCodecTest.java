package com.idavy.drtops.jt.protocol.jsatl12;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.jt.protocol.core.LocationReport;
import com.idavy.drtops.jt.protocol.core.LocationReportCodec;
import com.idavy.drtops.jt.protocol.codec.Jt808MessageHeader;
import com.idavy.drtops.jt.protocol.codec.ProtocolVersion;
import io.netty.buffer.Unpooled;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;

class Jsatl12AlarmExtensionCodecTest {

    private final LocationReportCodec locationCodec = new LocationReportCodec();

    @Test
    void decodesHandCheckedAdasAndDmsAlarmsFromOne0200Body() {
        LocationReport position = decode("000000000000000201e848000708898000140258005a260115100110642f0000100202020100000200003c001401e8480007088980260115100110000030303030303030260115100110080000652f0000200301020100000000003c001401e8480007088980260115100110000030303030303030260115100110090200");

        Jsatl12AlarmExtensionCodec.DecodeResult result = new Jsatl12AlarmExtensionCodec().decode(position);

        assertEquals(2, result.alarms().size());
        assertEquals("ADAS", result.alarms().get(0).module());
        assertEquals("LANE_DEPARTURE", result.alarms().get(0).alarmType());
        assertEquals("END", result.alarms().get(0).state());
        assertEquals("DMS", result.alarms().get(1).module());
        assertEquals("PHONE", result.alarms().get(1).alarmType());
        assertEquals("START", result.alarms().get(1).state());
    }

    @Test
    void mapsTheFourApprovedAlarmTypesAndBothLifecycleStates() {
        assertAlarm("000000000000000201e848000708898000140258005a260115100000642f0000100101010137080000003c001401e8480007088980260115100000000030303030303030260115100000010200",
                "ADAS", "FORWARD_COLLISION", "START");
        assertAlarm("000000000000000201e848000708898000140258005a260115100010642f00001001020101320a0000003c001401e8480007088980260115100010000030303030303030260115100010020000",
                "ADAS", "FORWARD_COLLISION", "END");
        assertAlarm("000000000000000201e848000708898000140258005a260115100020642f0000100201020100000100003c001401e8480007088980260115100020000030303030303030260115100020030100",
                "ADAS", "LANE_DEPARTURE", "START");
        assertAlarm("000000000000000201e848000708898000140258005a260115100030652f0000200101010107000000003c001401e8480007088980260115100030000030303030303030260115100030040200",
                "DMS", "FATIGUE", "START");
        assertAlarm("000000000000000201e848000708898000140258005a260115100100652f0000200202020100000000003c001401e8480007088980260115100100000030303030303030260115100100070000",
                "DMS", "PHONE", "END");
    }

    @Test
    void preservesALegalUnknownAdasCodeWithoutInventingAType() {
        LocationReport position = decode("000000000000000201e848000708898000140258005a260115100120642f0000100301080100000000003c001401e84800070889802601151001200000303030303030302601151001200a0100");

        Jsatl12AlarmExtensionCodec.DecodeResult result = new Jsatl12AlarmExtensionCodec().decode(position);

        assertEquals(1, result.alarms().size());
        assertEquals(0x08, result.alarms().getFirst().typeCode());
        assertEquals("UNKNOWN", result.alarms().getFirst().alarmType());
    }

    @Test
    void rejectsOnlyATruncatedExtensionAndRetainsThePublicLocation() {
        LocationReport position = decode("000000000000000201e848000708898000140258005a260115100000640100");

        Jsatl12AlarmExtensionCodec.DecodeResult result = new Jsatl12AlarmExtensionCodec().decode(position);

        assertEquals(0, result.alarms().size());
        assertEquals("ACTIVE_SAFETY_EXTENSION_REJECTED", result.rejections().getFirst().reasonCode());
        assertEquals("32.000000", position.latitude().toPlainString());
    }

    @Test
    void readsTheTrackedDerivedSyntheticFixtureWithoutUsingAProductionEncoder() throws Exception {
        JsonNode fixture = new ObjectMapper().readTree(getClass().getResourceAsStream(
                "/protocol-fixtures/jsatl12-alarm-fixtures.json"));

        assertEquals("DERIVED_SYNTHETIC", fixture.path("fixtureStatus").asText());
        assertEquals("T/JSATL12-2017", fixture.path("standard").path("code").asText());
        assertEquals(9, fixture.path("samples").size());
        for (JsonNode sample : fixture.path("samples")) {
            assertEquals("DERIVED_SYNTHETIC", sample.path("provenance").asText());
            assertEquals(sample.path("bodySha256").asText(), sha256(sample.path("bodyHex").asText()));
        }
    }

    @Test
    void separatesAlarmIdAndHashesTheFullAlarmIdentifierWhileReadingExtensionOwnedFields() {
        LocationReport position = decode("000000000000000201e848000708898000140258005a260115100000"
                + "642f0000100101010137080000003c001401e8480007088980260115100000000030303030303030260115100000010200");

        Jsatl12AlarmExtensionCodec.DecodedAlarm alarm = new Jsatl12AlarmExtensionCodec().decode(position).alarms().getFirst();

        assertEquals(0x00001001, alarm.alarmId());
        assertEquals("2026-01-15T02:00:00Z", alarm.occurredAt().toString());
        assertEquals("118.000000", alarm.longitude().toPlainString());
        assertEquals("32.000000", alarm.latitude().toPlainString());
        assertEquals(0, alarm.speedKph().compareTo(new java.math.BigDecimal("60.0")));
        assertEquals(64, alarm.terminalAlarmIdentifier().length());
        assertTrue(alarm.terminalAlarmIdentifier().matches("[0-9a-f]{64}"));
        assertNotEquals("00001001", alarm.terminalAlarmIdentifier());
        assertEquals(64, alarm.extensionPayloadDigest().length());
        assertTrue(alarm.extensionPayloadDigest().matches("[0-9a-f]{64}"));
        assertEquals(0, alarm.vehicleStatus());
        assertEquals(1, alarm.alarmSequenceNumber());
        assertEquals(2, alarm.attachmentCount());
    }

    @Test
    void keepsOneSourceAlarmIdAcrossStartAndEndEvenWhenTheirIdentifierEvidenceDiffers() {
        Jsatl12AlarmExtensionCodec codec = new Jsatl12AlarmExtensionCodec();
        Jsatl12AlarmExtensionCodec.DecodedAlarm start = codec.decode(decode(
                "000000000000000201e848000708898000140258005a260115100000"
                        + "642f0000100101010137080000003c001401e8480007088980260115100000000030303030303030260115100000010200"))
                .alarms().getFirst();
        Jsatl12AlarmExtensionCodec.DecodedAlarm end = codec.decode(decode(
                "000000000000000201e848000708898000140258005a260115100010"
                        + "642f00001001020101320a0000003c001401e8480007088980260115100010000030303030303030260115100010020000"))
                .alarms().getFirst();

        assertEquals("START", start.state());
        assertEquals("END", end.state());
        assertEquals(start.alarmId(), end.alarmId());
        assertNotEquals(start.terminalAlarmIdentifier(), end.terminalAlarmIdentifier());
    }

    @Test
    void preservesUnsignedAlarmIdsAcrossTheFullDwordRange() {
        LocationReport position = decode("000000000000000201e848000708898000140258005a260115100000"
                + "642fffffffff01010137080000003c001401e8480007088980260115100000000030303030303030260115100000010200");

        Jsatl12AlarmExtensionCodec.DecodedAlarm alarm = new Jsatl12AlarmExtensionCodec().decode(position).alarms().getFirst();

        assertEquals(4_294_967_295L, alarm.alarmId());
    }

    @Test
    void rejectsOnlyTheMalformedBcdExtensionAndRetainsItsValidSibling() {
        LocationReport position = decode("000000000000000201e848000708898000140258005a260115100110"
                + "642f0000100202020100000200003c001401e8480007088980260115100110000030303030303030260115100110080000"
                + "652f0000200301020100000000003c001401e8480007088980fa0115100110000030303030303030260115100110090200");

        Jsatl12AlarmExtensionCodec.DecodeResult result = new Jsatl12AlarmExtensionCodec().decode(position);

        assertEquals(1, result.alarms().size());
        assertEquals("ADAS", result.alarms().getFirst().module());
        assertEquals(1, result.rejections().size());
        assertEquals("ACTIVE_SAFETY_EXTENSION_REJECTED", result.rejections().getFirst().reasonCode());
    }

    @Test
    void rejectsAnUnknownLifecycleStateInsteadOfBufferingAnUndeliverableAlarm() {
        LocationReport position = decode(
                "000000000000000201e848000708898000140258005a260115100000"
                        + "642f0000100103010137080000003c001401e8480007088980260115100000000030303030303030260115100000010200");

        Jsatl12AlarmExtensionCodec.DecodeResult result = new Jsatl12AlarmExtensionCodec().decode(position);

        assertTrue(result.alarms().isEmpty());
        assertEquals("ACTIVE_SAFETY_EXTENSION_REJECTED", result.rejections().getFirst().reasonCode());
    }

    @Test
    void rejectsOutOfRangeExtensionCoordinatesWhileRetainingThePublicPosition() {
        String valid = "000000000000000201e848000708898000140258005a260115100000"
                + "642f0000100101010137080000003c001401e8480007088980260115100000000030303030303030260115100000010200";
        int extensionLongitude = valid.lastIndexOf("07088980");
        LocationReport position = decode(valid.substring(0, extensionLongitude)
                + "0ac9c740" + valid.substring(extensionLongitude + 8));

        Jsatl12AlarmExtensionCodec.DecodeResult result = new Jsatl12AlarmExtensionCodec().decode(position);

        assertTrue(result.alarms().isEmpty());
        assertEquals("ACTIVE_SAFETY_EXTENSION_REJECTED", result.rejections().getFirst().reasonCode());
        assertEquals("118.000000", position.longitude().toPlainString());
    }

    private static String sha256(String hex) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(HexFormat.of().parseHex(hex)));
    }

    private LocationReport decode(String bodyHex) {
        byte[] body = HexFormat.of().parseHex(bodyHex);
        return locationCodec.decode(new Jt808MessageHeader(
                0x0200, body.length, body.length, 0, false, ProtocolVersion.JT808_2013, 0,
                "000000000000", 1, null, null), Unpooled.wrappedBuffer(body));
    }

    private void assertAlarm(String bodyHex, String module, String type, String state) {
        Jsatl12AlarmExtensionCodec.DecodedAlarm alarm = new Jsatl12AlarmExtensionCodec()
                .decode(decode(bodyHex)).alarms().getFirst();
        assertEquals(module, alarm.module());
        assertEquals(type, alarm.alarmType());
        assertEquals(state, alarm.state());
    }
}
