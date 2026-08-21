package com.idavy.drtops.jtgateway.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.jt.protocol.codec.Jt808Frame;
import com.idavy.drtops.jt.protocol.codec.Jt808MessageHeader;
import com.idavy.drtops.jt.protocol.codec.ProtocolVersion;
import com.idavy.drtops.jt.protocol.core.Jt808CoreModule;
import com.idavy.drtops.jt.protocol.core.LocationReportCodec;
import com.idavy.drtops.jtgateway.ingress.GatewayIngressBuffer;
import com.idavy.drtops.jtgateway.ingress.GatewayOutboxRepository;
import com.idavy.drtops.jtgateway.ingress.IngressKind;
import com.idavy.drtops.jtgateway.session.TerminalSession;
import com.idavy.drtops.jtgateway.session.TerminalSessionRegistry;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ProtocolModuleRegistryActiveSafetyDispatchTest {
    @Test
    void writesPositionAndEachDeclaredActiveSafetyAlarmAsSeparateGatewayEnvelopes() {
        DataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:alarm_dispatch;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        GatewayOutboxRepository repository = new GatewayOutboxRepository(dataSource);
        GatewayIngressBuffer buffer = new GatewayIngressBuffer(repository, new ObjectMapper().findAndRegisterModules(), Clock.systemUTC());
        TerminalSession session = session("T/JSATL12-2017", List.of("ADAS", "DMS"));
        TerminalSessionRegistry sessions = new TerminalSessionRegistry(); sessions.claim(session);
        ProtocolModuleRegistry registry = new ProtocolModuleRegistry(new Jt808CoreModule(new LocationReportCodec()), buffer,
                new ObjectMapper().findAndRegisterModules(), sessions, Clock.fixed(Instant.parse("2026-01-15T02:00:00Z"), ZoneOffset.UTC));

        assertTrue(registry.dispatch(session, frame()).mayAcknowledgeSuccess());
        assertEquals(3, repository.totalCount());
        List<GatewayOutboxRepository.OutboxEntry> dependency = repository.claimHighPriorityDependencies(
                Instant.now(), 10);
        assertEquals(List.of(IngressKind.LOCATION), dependency.stream()
                .map(GatewayOutboxRepository.OutboxEntry::kind).toList());
        repository.markDelivered(dependency, Instant.now());
        assertEquals(2, repository.claimEligible(Instant.now(), GatewayOutboxRepository.Priority.HIGH, 10).stream()
                .map(GatewayOutboxRepository.OutboxEntry::kind).filter(kind -> kind == IngressKind.ALARM).count());
    }

    @Test
    void doesNotGenerateAnAlarmEnvelopeForAnActual1206Message() {
        DataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:alarm_dispatch_1206;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        GatewayOutboxRepository repository = new GatewayOutboxRepository(dataSource);
        GatewayIngressBuffer buffer = new GatewayIngressBuffer(repository, new ObjectMapper().findAndRegisterModules(), Clock.systemUTC());
        TerminalSession session = session("T/JSATL12-2017", List.of("ADAS", "DMS"));
        TerminalSessionRegistry sessions = new TerminalSessionRegistry(); sessions.claim(session);
        ProtocolModuleRegistry registry = new ProtocolModuleRegistry(new Jt808CoreModule(new LocationReportCodec()), buffer,
                new ObjectMapper().findAndRegisterModules(), sessions, Clock.fixed(Instant.parse("2026-01-15T02:00:00Z"), ZoneOffset.UTC));
        Jt808Frame fileUploadCompleted = new Jt808Frame(new Jt808MessageHeader(
                0x1206, 3, 3, 0, false, ProtocolVersion.JT808_2013, 0,
                "000000000000", 2, null, null),
                Unpooled.wrappedBuffer(new byte[] {0, 1, 0}), (byte) 0);

        assertTrue(registry.dispatch(session, fileUploadCompleted).mayAcknowledgeSuccess());
        List<IngressKind> kinds = repository.claimEligible(
                        Instant.now(), GatewayOutboxRepository.Priority.HIGH, 10).stream()
                .map(GatewayOutboxRepository.OutboxEntry::kind)
                .toList();
        assertEquals(List.of(IngressKind.ATTACHMENT_METADATA), kinds);
        assertEquals(0, kinds.stream().filter(kind -> kind == IngressKind.ALARM).count());
    }

    @Test
    void persistsMalformedActiveSafetyExtensionAsHighPriorityAuditWhileKeepingThePosition() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:alarm_dispatch_audit;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        GatewayOutboxRepository repository = new GatewayOutboxRepository(dataSource);
        GatewayIngressBuffer buffer = new GatewayIngressBuffer(
                repository, new ObjectMapper().findAndRegisterModules(), Clock.systemUTC());
        TerminalSession session = session("T/JSATL12-2017", List.of("ADAS", "DMS"));
        TerminalSessionRegistry sessions = new TerminalSessionRegistry();
        sessions.claim(session);
        ProtocolModuleRegistry registry = new ProtocolModuleRegistry(
                new Jt808CoreModule(new LocationReportCodec()), buffer,
                new ObjectMapper().findAndRegisterModules(), sessions,
                Clock.fixed(Instant.parse("2026-01-15T02:00:00Z"), ZoneOffset.UTC));

        assertTrue(registry.dispatch(session, malformedAlarmFrame()).mayAcknowledgeSuccess());
        assertEquals(2, repository.totalCount());
        assertEquals(List.of("PROTOCOL_AUDIT"), repository.claimEligible(
                        Instant.now(), GatewayOutboxRepository.Priority.HIGH, 10).stream()
                .map(entry -> entry.kind().name()).toList());
    }

    @Test
    void derivesAlarmIdempotencyFromAlarmFactsRatherThanTheContainingPositionSerial() {
        com.idavy.drtops.jtgateway.ingress.CanonicalVehicleAlarm alarm = new com.idavy.drtops.jtgateway.ingress.CanonicalVehicleAlarm(
                UUID.fromString("11111111-1111-1111-1111-111111111111"), UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "T/JSATL12-2017", "ADAS", 0x00001001L, 1, "FORWARD_COLLISION", "START", 1, "a".repeat(64),
                Instant.parse("2026-01-15T02:00:00Z"), Instant.parse("2026-01-15T02:00:01Z"),
                new java.math.BigDecimal("118.000000"), new java.math.BigDecimal("32.000000"),
                new java.math.BigDecimal("60.0"), 0, 1, 2,
                UUID.fromString("33333333-3333-3333-3333-333333333333"), "UNASSESSED", "b".repeat(64));
        com.idavy.drtops.jtgateway.ingress.CanonicalPositionIngress first = position(1, "c".repeat(64));
        com.idavy.drtops.jtgateway.ingress.CanonicalPositionIngress replayedInAnotherFrame = position(9, "d".repeat(64));

        assertEquals(ProtocolModuleRegistry.idempotencyKeyFor(alarm, first),
                ProtocolModuleRegistry.idempotencyKeyFor(alarm, replayedInAnotherFrame));
    }

    private static TerminalSession session(String standard, List<String> modules) {
        TerminalSession session = new TerminalSession(new EmbeddedChannel(), Instant.parse("2026-01-15T02:00:00Z"));
        session.registrationAccepted(UUID.fromString("11111111-1111-1111-1111-111111111111"), UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "WGS84", 1, "000000000000", standard, modules);
        session.authenticated(Instant.parse("2026-01-15T02:00:00Z")); return session;
    }
    private static Jt808Frame frame() {
        byte[] body = HexFormat.of().parseHex("000000000000000201e848000708898000140258005a260115100110642f0000100202020100000200003c001401e8480007088980260115100110000030303030303030260115100110080000652f0000200301020100000000003c001401e8480007088980260115100110000030303030303030260115100110090200");
        return new Jt808Frame(new Jt808MessageHeader(0x0200, body.length, body.length, 0, false, ProtocolVersion.JT808_2013, 0, "000000000000", 1, null, null), Unpooled.wrappedBuffer(body), (byte) 0);
    }

    private static Jt808Frame malformedAlarmFrame() {
        byte[] body = HexFormat.of().parseHex(
                "000000000000000201e848000708898000140258005a260115100000640100");
        return new Jt808Frame(new Jt808MessageHeader(
                0x0200, body.length, body.length, 0, false, ProtocolVersion.JT808_2013, 0,
                "000000000000", 3, null, null), Unpooled.wrappedBuffer(body), (byte) 0);
    }

    private static com.idavy.drtops.jtgateway.ingress.CanonicalPositionIngress position(int serial, String digest) {
        return new com.idavy.drtops.jtgateway.ingress.CanonicalPositionIngress(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"), "JT808_2013", serial,
                new java.math.BigDecimal("118.000000"), new java.math.BigDecimal("32.000000"), "WGS84",
                Instant.parse("2026-01-15T02:00:00Z"), Instant.parse("2026-01-15T02:00:01Z"),
                0, 0, new java.math.BigDecimal("60.0"), 90, 20, 8, digest);
    }
}
