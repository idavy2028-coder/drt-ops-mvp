package com.idavy.drtops.jtgateway.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.jt.protocol.codec.Jt808Frame;
import com.idavy.drtops.jt.protocol.codec.Jt808MessageHeader;
import com.idavy.drtops.jt.protocol.codec.ProtocolVersion;
import com.idavy.drtops.jt.protocol.core.Jt808CoreModule;
import com.idavy.drtops.jt.protocol.core.LocationReportCodec;
import com.idavy.drtops.jtgateway.ingress.GatewayIngressBuffer;
import com.idavy.drtops.jtgateway.ingress.GatewayOutboxRepository;
import com.idavy.drtops.jtgateway.ingress.IngressKind;
import com.idavy.drtops.jtgateway.ingress.CanonicalProtocolAudit;
import com.idavy.drtops.jtgateway.session.TerminalSession;
import com.idavy.drtops.jtgateway.session.TerminalSessionContext;
import com.idavy.drtops.jtgateway.session.TerminalSessionRegistry;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ProtocolModuleRegistryActiveSafetyDispatchTest {
    private static final UUID TERMINAL_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ONBOARD_SYSTEM_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID VEHICLE_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void compatibilityConstructorKeepsDurableBaseAckWhenRoleAuditSinkIsUnavailable() {
        for (GatewayIngressBuffer.WriteResult durable : List.of(
                GatewayIngressBuffer.WriteResult.STORED,
                GatewayIngressBuffer.WriteResult.DUPLICATE)) {
            AtomicInteger baseAppends = new AtomicInteger();
            TerminalSession session = session(
                    Set.of("LOCATION_PRIMARY"),
                    "T/JSATL12-2017",
                    List.of("ADAS", "DMS"));
            TerminalSessionRegistry sessions = new TerminalSessionRegistry();
            sessions.claim(session);
            ProtocolModuleRegistry registry = new ProtocolModuleRegistry(
                    new Jt808CoreModule(new LocationReportCodec()),
                    position -> {
                        baseAppends.incrementAndGet();
                        return durable;
                    },
                    sessions,
                    Clock.fixed(Instant.parse("2026-01-15T02:00:00Z"), ZoneOffset.UTC));

            ProtocolModuleRegistry.DispatchResult result = assertDoesNotThrow(
                    () -> registry.dispatch(session, frame()));

            assertTrue(result.mayAcknowledgeSuccess());
            assertEquals(1, baseAppends.get());
        }
    }
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
    void primaryAndBackupRolesEachPersistBasePositionWithSessionProvenance() throws Exception {
        for (String role : List.of("LOCATION_PRIMARY", "LOCATION_BACKUP")) {
            Fixture fixture = fixture(
                    "location_role_" + role.toLowerCase() + "_" + UUID.randomUUID(),
                    Set.of(role), null, List.of(), new ObjectMapper().findAndRegisterModules());

            assertTrue(fixture.registry().dispatch(fixture.session(), basicLocationFrame())
                    .mayAcknowledgeSuccess());
            assertEquals(1, fixture.repository().totalCount());
            GatewayOutboxRepository.OutboxEntry stored = fixture.repository()
                    .claimEligible(Instant.now(), GatewayOutboxRepository.Priority.LOCATION, 10)
                    .getFirst();
            JsonNode payload = fixture.mapper().readTree(stored.payloadJson());
            assertEquals(IngressKind.LOCATION, stored.kind());
            assertEquals(TERMINAL_ID.toString(), payload.path("terminalId").asText());
            assertEquals(ONBOARD_SYSTEM_ID.toString(), payload.path("onboardSystemId").asText());
            assertEquals(VEHICLE_ID.toString(), payload.path("vehicleId").asText());
            assertEquals(role, payload.path("sourceRole").asText());
            assertFalse(stored.payloadJson().contains("000000000000"));
            assertFalse(stored.payloadJson().contains("authentication"));
        }
    }

    @Test
    void rejectsMissingOrAmbiguousLocationRoleBeforeBasePersistence() {
        for (Set<String> roles : List.of(
                Set.of("VIDEO"),
                Set.of("LOCATION_PRIMARY", "LOCATION_BACKUP"))) {
            Fixture fixture = fixture(
                    "invalid_location_role_" + UUID.randomUUID(),
                    roles, null, List.of(), new ObjectMapper().findAndRegisterModules());

            ProtocolModuleRegistry.DispatchResult result = assertDoesNotThrow(
                    () -> fixture.registry().dispatch(fixture.session(), basicLocationFrame()));
            assertFalse(result.mayAcknowledgeSuccess());
            assertEquals(0, fixture.repository().totalCount());
        }
    }

    @Test
    void keepsDurableBaseAckAndAuditsWhenActiveSafetyRoleIsMissing() throws Exception {
        Fixture fixture = fixture(
                "missing_active_safety_" + UUID.randomUUID(),
                Set.of("LOCATION_PRIMARY"),
                "T/JSATL12-2017",
                List.of("ADAS", "DMS"),
                new ObjectMapper().findAndRegisterModules());

        assertTrue(fixture.registry().dispatch(fixture.session(), frame()).mayAcknowledgeSuccess());
        assertEquals(2, fixture.repository().totalCount());
        List<GatewayOutboxRepository.OutboxEntry> location = fixture.repository().claimEligible(
                Instant.now(), GatewayOutboxRepository.Priority.LOCATION, 10);
        assertEquals(List.of(IngressKind.LOCATION), location.stream()
                .map(GatewayOutboxRepository.OutboxEntry::kind).toList());
        fixture.repository().markDelivered(location, Instant.now());
        List<GatewayOutboxRepository.OutboxEntry> optional = fixture.repository().claimEligible(
                Instant.now(), GatewayOutboxRepository.Priority.HIGH, 10);
        assertEquals(List.of(IngressKind.PROTOCOL_AUDIT), optional.stream()
                .map(GatewayOutboxRepository.OutboxEntry::kind).toList());
        JsonNode audit = fixture.mapper().readTree(optional.getFirst().payloadJson());
        assertEquals("DEVICE_ROLE_VIOLATION", audit.path("reasonCode").asText());
        assertFalse(optional.getFirst().payloadJson().contains("000000000000"));
    }

    @Test
    void auditSerializationFailureCannotReverseDurableBaseAckOrStoreUnauthorizedAlarm() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ObjectMapper auditFailingMapper = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) throws JsonProcessingException {
                if (value instanceof CanonicalProtocolAudit) {
                    throw new JsonProcessingException("synthetic protocol audit serialization failure") { };
                }
                return mapper.writeValueAsString(value);
            }
        };
        auditFailingMapper.findAndRegisterModules();
        Fixture fixture = fixture(
                "missing_active_safety_audit_failure_" + UUID.randomUUID(),
                Set.of("LOCATION_PRIMARY"),
                "T/JSATL12-2017",
                List.of("ADAS", "DMS"),
                auditFailingMapper);

        assertTrue(fixture.registry().dispatch(fixture.session(), frame()).mayAcknowledgeSuccess());
        assertEquals(1, fixture.repository().totalCount());
        assertEquals(List.of(IngressKind.LOCATION), fixture.repository()
                .claimEligible(Instant.now(), GatewayOutboxRepository.Priority.LOCATION, 10).stream()
                .map(GatewayOutboxRepository.OutboxEntry::kind).toList());
    }

    @Test
    void runtimeAuditFailureCannotReverseDurableBaseAckOrStoreUnauthorizedAlarm() {
        Fixture fixture = fixture(
                "missing_active_safety_runtime_audit_failure_" + UUID.randomUUID(),
                Set.of("LOCATION_PRIMARY"),
                "T/JSATL12-2017",
                List.of("ADAS", "DMS"),
                runtimeAuditFailingMapper());
        Jt808Frame input = frame();
        ProtocolModuleRegistry.DispatchResult result = null;
        RuntimeException escaped = null;

        try {
            result = fixture.registry().dispatch(fixture.session(), input);
        } catch (RuntimeException failure) {
            escaped = failure;
        }

        assertEquals(1, fixture.repository().totalCount(),
                "the base LOCATION must already be durable before optional audit serialization");
        assertEquals(List.of(IngressKind.LOCATION), fixture.repository()
                .claimEligible(Instant.now(), GatewayOutboxRepository.Priority.LOCATION, 10).stream()
                .map(GatewayOutboxRepository.OutboxEntry::kind).toList());
        assertTrue(fixture.repository()
                .claimEligible(Instant.now(), GatewayOutboxRepository.Priority.HIGH, 10).isEmpty(),
                "no unauthorized alarm or failed audit may be stored");
        assertEquals(0, input.body().refCnt());
        assertNull(escaped, "optional audit runtime failure must not escape after durable base ingress");
        assertNotNull(result);
        assertTrue(result.mayAcknowledgeSuccess());
    }

    @Test
    void auditsAndSkipsAttachmentMetadataWhenVideoRoleIsMissing() throws Exception {
        Fixture fixture = fixture(
                "missing_video_" + UUID.randomUUID(),
                Set.of("LOCATION_PRIMARY"),
                "T/JSATL12-2017",
                List.of("ADAS", "DMS"),
                new ObjectMapper().findAndRegisterModules());

        assertTrue(fixture.registry().dispatch(fixture.session(), fileUploadCompletedFrame())
                .mayAcknowledgeSuccess());
        assertEquals(1, fixture.repository().totalCount());
        List<GatewayOutboxRepository.OutboxEntry> entries = fixture.repository().claimEligible(
                Instant.now(), GatewayOutboxRepository.Priority.HIGH, 10);
        assertEquals(List.of(IngressKind.PROTOCOL_AUDIT), entries.stream()
                .map(GatewayOutboxRepository.OutboxEntry::kind).toList());
        assertEquals("DEVICE_ROLE_VIOLATION", fixture.mapper()
                .readTree(entries.getFirst().payloadJson()).path("reasonCode").asText());
    }

    @Test
    void runtimeAuditFailureRejectsUnauthorizedAttachmentWithoutStoringMetadata() {
        Fixture fixture = fixture(
                "missing_video_runtime_audit_failure_" + UUID.randomUUID(),
                Set.of("LOCATION_PRIMARY"),
                "T/JSATL12-2017",
                List.of("ADAS", "DMS"),
                runtimeAuditFailingMapper());
        Jt808Frame input = fileUploadCompletedFrame();
        ProtocolModuleRegistry.DispatchResult result = null;
        RuntimeException escaped = null;

        try {
            result = fixture.registry().dispatch(fixture.session(), input);
        } catch (RuntimeException failure) {
            escaped = failure;
        }

        assertEquals(0, fixture.repository().totalCount(),
                "missing VIDEO authority must not persist attachment metadata");
        assertEquals(0, input.body().refCnt());
        assertNull(escaped, "audit runtime failure must fail closed without escaping");
        assertNotNull(result);
        assertFalse(result.mayAcknowledgeSuccess());
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
        Jt808Frame fileUploadCompleted = fileUploadCompletedFrame();

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
        return session(
                Set.of("LOCATION_PRIMARY", "ACTIVE_SAFETY", "VIDEO"),
                standard,
                modules);
    }

    private static TerminalSession session(
            Set<String> roles, String standard, List<String> modules) {
        TerminalSession session = new TerminalSession(new EmbeddedChannel(), Instant.parse("2026-01-15T02:00:00Z"));
        session.registrationAccepted(new TerminalSessionContext(
                TERMINAL_ID,
                ONBOARD_SYSTEM_ID,
                VEHICLE_ID,
                roles,
                "WGS84",
                standard,
                modules,
                1), "000000000000");
        session.authenticated(Instant.parse("2026-01-15T02:00:00Z")); return session;
    }

    private static Fixture fixture(
            String databaseName,
            Set<String> roles,
            String standard,
            List<String> modules,
            ObjectMapper mapper) {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + databaseName + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                "sa",
                "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        GatewayOutboxRepository repository = new GatewayOutboxRepository(dataSource);
        GatewayIngressBuffer buffer = new GatewayIngressBuffer(repository, mapper, Clock.systemUTC());
        TerminalSession session = session(roles, standard, modules);
        TerminalSessionRegistry sessions = new TerminalSessionRegistry();
        sessions.claim(session);
        ProtocolModuleRegistry registry = new ProtocolModuleRegistry(
                new Jt808CoreModule(new LocationReportCodec()),
                buffer,
                mapper,
                sessions,
                Clock.fixed(Instant.parse("2026-01-15T02:00:00Z"), ZoneOffset.UTC));
        return new Fixture(repository, registry, session, mapper);
    }

    private static ObjectMapper runtimeAuditFailingMapper() {
        ObjectMapper delegate = new ObjectMapper().findAndRegisterModules();
        ObjectMapper mapper = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) throws JsonProcessingException {
                if (value instanceof CanonicalProtocolAudit) {
                    throw new IllegalStateException("synthetic protocol audit runtime failure");
                }
                return delegate.writeValueAsString(value);
            }
        };
        return mapper.findAndRegisterModules();
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

    private static Jt808Frame basicLocationFrame() {
        byte[] body = HexFormat.of().parseHex(
                "000000010000000200ba7f0e07e4f11c0028003c00001810151010100104000000640202007d");
        return new Jt808Frame(new Jt808MessageHeader(
                0x0200, body.length, body.length, 0, false, ProtocolVersion.JT808_2013, 0,
                "000000000000", 4, null, null), Unpooled.wrappedBuffer(body), (byte) 0);
    }

    private static Jt808Frame fileUploadCompletedFrame() {
        return new Jt808Frame(new Jt808MessageHeader(
                0x1206, 3, 3, 0, false, ProtocolVersion.JT808_2013, 0,
                "000000000000", 2, null, null),
                Unpooled.wrappedBuffer(new byte[] {0, 1, 0}), (byte) 0);
    }

    private static com.idavy.drtops.jtgateway.ingress.CanonicalPositionIngress position(int serial, String digest) {
        return new com.idavy.drtops.jtgateway.ingress.CanonicalPositionIngress(
                TERMINAL_ID,
                ONBOARD_SYSTEM_ID,
                VEHICLE_ID,
                "LOCATION_PRIMARY",
                "JT808_2013", serial,
                new java.math.BigDecimal("118.000000"), new java.math.BigDecimal("32.000000"), "WGS84",
                Instant.parse("2026-01-15T02:00:00Z"), Instant.parse("2026-01-15T02:00:01Z"),
                0L, 0L, new java.math.BigDecimal("60.0"), 90, 20, 8, digest);
    }

    private record Fixture(
            GatewayOutboxRepository repository,
            ProtocolModuleRegistry registry,
            TerminalSession session,
            ObjectMapper mapper) { }
}
