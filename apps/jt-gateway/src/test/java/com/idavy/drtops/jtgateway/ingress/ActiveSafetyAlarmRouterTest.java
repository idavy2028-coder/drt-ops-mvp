package com.idavy.drtops.jtgateway.ingress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idavy.drtops.jt.protocol.codec.Jt808MessageHeader;
import com.idavy.drtops.jt.protocol.codec.ProtocolVersion;
import com.idavy.drtops.jt.protocol.core.LocationReport;
import com.idavy.drtops.jt.protocol.core.LocationReportCodec;
import com.idavy.drtops.jtgateway.session.TerminalSession;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActiveSafetyAlarmRouterTest {
    private static final UUID TERMINAL_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VEHICLE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID POSITION_KEY = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void emitsTwoCanonicalAlarmsOnlyForTheBoundJsatl12Capabilities() {
        ActiveSafetyAlarmRouter.Result result = new ActiveSafetyAlarmRouter().route(
                session("T/JSATL12-2017", List.of("ADAS", "DMS")), location(
                        "000000000000000201e848000708898000140258005a260115100110642f0000100202020100000200003c001401e8480007088980260115100110000030303030303030260115100110080000652f0000200301020100000000003c001401e8480007088980260115100110000030303030303030260115100110090200"),
                Instant.parse("2026-01-15T02:00:00Z"), POSITION_KEY);

        assertEquals(2, result.alarms().size());
        assertEquals("ADAS", result.alarms().get(0).module());
        assertEquals(0x00001002L, result.alarms().get(0).terminalAlarmId());
        assertEquals("DMS", result.alarms().get(1).module());
        assertEquals(0x00002003L, result.alarms().get(1).terminalAlarmId());
        assertEquals("PHONE", result.alarms().get(1).alarmType());
        assertEquals(POSITION_KEY, result.alarms().get(1).positionIdempotencyKey());
        assertEquals("UNASSESSED", result.alarms().get(1).locationQualityStatus());
        assertTrue(result.rejections().isEmpty());
    }

    @Test
    void doesNotGuessActiveSafetyWithoutTheRegisteredCapabilityProfile() {
        ActiveSafetyAlarmRouter.Result result = new ActiveSafetyAlarmRouter().route(
                session(null, List.of()), location(
                        "000000000000000201e848000708898000140258005a260115100000642f0000100101010137080000003c001401e8480007088980260115100000000030303030303030260115100000010200"),
                Instant.parse("2026-01-15T02:00:00Z"), POSITION_KEY);

        assertTrue(result.alarms().isEmpty());
        assertEquals("UNSUPPORTED_ACTIVE_SAFETY_STANDARD", result.rejections().getFirst().reasonCode());
    }

    @Test
    void rejectsARegisteredButUnimplementedGuangdongProfileWithoutUsingTheJsatl12Decoder() {
        ActiveSafetyAlarmRouter.Result result = new ActiveSafetyAlarmRouter().route(
                session("T/GD-ACTIVE-SAFETY", List.of("ADAS")), location(
                        "000000000000000201e848000708898000140258005a260115100000642f0000100101010137080000003c001401e8480007088980260115100000000030303030303030260115100000010200"),
                Instant.parse("2026-01-15T02:00:00Z"), POSITION_KEY);

        assertTrue(result.alarms().isEmpty());
        assertEquals("UNSUPPORTED_ACTIVE_SAFETY_STANDARD", result.rejections().getFirst().reasonCode());
    }

    @Test
    void ignoresOrdinaryPositionsWithoutActiveSafetyItemsWhenNoCapabilityWasDeclared() {
        ActiveSafetyAlarmRouter.Result result = new ActiveSafetyAlarmRouter().route(
                session(null, List.of()), location(
                        "000000000000000201e848000708898000140258005a260115100000"),
                Instant.parse("2026-01-15T02:00:00Z"), POSITION_KEY);

        assertTrue(result.alarms().isEmpty());
        assertTrue(result.rejections().isEmpty());
    }

    @Test
    void isolatesMalformedAlarmAndNeverTreats1206AsAnAlarmItem() {
        ActiveSafetyAlarmRouter router = new ActiveSafetyAlarmRouter();
        TerminalSession session = session("T/JSATL12-2017", List.of("ADAS", "DMS"));

        ActiveSafetyAlarmRouter.Result malformed = router.route(session, location(
                "000000000000000201e848000708898000140258005a260115100000640100"),
                Instant.parse("2026-01-15T02:00:00Z"), POSITION_KEY);
        ActiveSafetyAlarmRouter.Result control = router.route(session, location(
                "000000000000000201e848000708898000140258005a260115100000120106"),
                Instant.parse("2026-01-15T02:00:00Z"), POSITION_KEY);

        assertTrue(malformed.alarms().isEmpty());
        assertEquals("ACTIVE_SAFETY_EXTENSION_REJECTED", malformed.rejections().getFirst().reasonCode());
        assertTrue(control.alarms().isEmpty());
        assertTrue(control.rejections().isEmpty());
    }

    private static TerminalSession session(String standard, List<String> modules) {
        TerminalSession session = new TerminalSession(new EmbeddedChannel(), Instant.parse("2026-01-15T02:00:00Z"));
        session.registrationAccepted(TERMINAL_ID, VEHICLE_ID, "WGS84", 1, "000000000000", standard, modules);
        session.authenticated(Instant.parse("2026-01-15T02:00:00Z"));
        return session;
    }

    private static LocationReport location(String bodyHex) {
        byte[] body = HexFormat.of().parseHex(bodyHex);
        return new LocationReportCodec().decode(new Jt808MessageHeader(
                0x0200, body.length, body.length, 0, false, ProtocolVersion.JT808_2013, 0,
                "000000000000", 1, null, null), Unpooled.wrappedBuffer(body));
    }
}
