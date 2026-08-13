package com.idavy.drtops.domain.location;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import com.idavy.drtops.domain.terminal.JtGatewayAuditEventRepository;
import com.idavy.drtops.domain.terminal.JtTerminal;
import com.idavy.drtops.domain.terminal.JtTerminalRepository;
import com.idavy.drtops.domain.terminal.JtTerminalVehicleBinding;
import com.idavy.drtops.domain.terminal.JtTerminalVehicleBindingRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:gps_ingress;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"})
@AutoConfigureMockMvc
@Import(GpsLocationIngressIntegrationTest.LocationCheckerConfiguration.class)
class GpsLocationIngressIntegrationTest {
    private static final String CREDENTIAL = "gps-ingress-test-credential";
    private static final UUID VEHICLE_ID = UUID.fromString("aa000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_VEHICLE_ID = UUID.fromString("aa000000-0000-0000-0000-000000000002");
    private static final UUID TERMINAL_ID = UUID.fromString("bb000000-0000-0000-0000-000000000001");
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired VehicleRepository vehicleRepository;
    @Autowired VehicleLocationEventRepository eventRepository;
    @Autowired JtTerminalRepository terminalRepository;
    @Autowired JtTerminalVehicleBindingRepository bindingRepository;
    @Autowired JtGatewayAuditEventRepository gatewayAuditRepository;

    @DynamicPropertySource
    static void credential(DynamicPropertyRegistry registry) {
        registry.add("jt.gateway.service-credentials.current.version", () -> "1");
        registry.add("jt.gateway.service-credentials.current.hash", () -> sha256(CREDENTIAL));
    }

    @BeforeEach
    void setUp() {
        gatewayAuditRepository.deleteAll(); eventRepository.deleteAll(); bindingRepository.deleteAll(); terminalRepository.deleteAll(); vehicleRepository.deleteAll();
        vehicleRepository.save(Vehicle.create(VEHICLE_ID, "甘G001", "Microbus", 8, "IDLE", "POINT(105.2421 35.2103)", "测试", true));
        vehicleRepository.save(Vehicle.create(OTHER_VEHICLE_ID, "甘G002", "Microbus", 8, "IDLE", "POINT(105.2421 35.2103)", "测试", true));
        JtTerminal terminal = terminalRepository.save(JtTerminal.preset(TERMINAL_ID, "GPS-PHONE", "GPS-001", "MFG", "M", "JT808_2019", "WGS84", UUID.randomUUID()));
        bindingRepository.save(JtTerminalVehicleBinding.bind(terminal, VEHICLE_ID, "测试绑定", UUID.randomUUID()));
    }

    @Test
    void persistsGoodAndQuarantinedHistoryButOnlyGoodUpdatesSnapshotAndGpsHasNoRecorder() throws Exception {
        UUID goodKey = UUID.randomUUID();
        UUID quarantinedKey = UUID.randomUUID();
        postIngress(List.of(envelope(goodKey, VEHICLE_ID, 0x02, "105.2384988", "35.2109657", Instant.parse("2026-08-12T08:59:50Z")),
                        envelope(quarantinedKey, VEHICLE_ID, 0, "105.2384988", "35.2109657", Instant.parse("2026-08-12T08:59:51Z"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data[1].status").value("ACCEPTED"));

        assertThat(eventRepository.findAll()).hasSize(2);
        assertThat(eventRepository.findByIdempotencyKey(goodKey).orElseThrow().getRecordedBy()).isNull();
        assertThat(eventRepository.findByIdempotencyKey(goodKey).orElseThrow().getQualityStatus()).isEqualTo(LocationQualityStatus.GOOD);
        assertThat(eventRepository.findByIdempotencyKey(quarantinedKey).orElseThrow().isSnapshotApplied()).isFalse();
        assertThat(vehicleRepository.findById(VEHICLE_ID).orElseThrow().getCurrentLocationEventId())
                .isEqualTo(eventRepository.findByIdempotencyKey(goodKey).orElseThrow().getId());
    }

    @Test
    void rejectsInvalidCoordinatesIntoGatewayAuditOnlyAndReplaysWithoutDuplication() throws Exception {
        UUID rejectedKey = UUID.randomUUID();
        postIngress(List.of(envelope(rejectedKey, VEHICLE_ID, 0x02, "0", "35.2103", Instant.parse("2026-08-12T08:59:50Z"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.data[0].reasonCodes[0]").value("INVALID_COORDINATE"));
        assertThat(eventRepository.findAll()).isEmpty();
        assertThat(gatewayAuditRepository.findAll()).singleElement().satisfies(audit -> assertThat(audit.getReasonCode()).isEqualTo("INVALID_COORDINATE"));

        UUID acceptedKey = UUID.randomUUID();
        GatewayIngressEnvelope accepted = envelope(acceptedKey, VEHICLE_ID, 0x02, "105.2384988", "35.2109657", Instant.parse("2026-08-12T08:59:50Z"));
        postIngress(List.of(accepted, accepted)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data[1].status").value("REPLAYED"));
        assertThat(eventRepository.findAll()).hasSize(1);
    }

    @Test
    void rejectsVehicleClaimThatDoesNotMatchCurrentTerminalBindingAndRejectsInvalidBatchShapes() throws Exception {
        postIngress(List.of(envelope(UUID.randomUUID(), OTHER_VEHICLE_ID, 0x02, "105.2384988", "35.2109657", Instant.parse("2026-08-12T08:59:50Z"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.data[0].reasonCodes[0]").value("TERMINAL_BINDING_MISMATCH"));
        internalPost("[]").andExpect(status().isBadRequest());
        internalPost("{}").andExpect(status().isBadRequest());
        internalPost("{]").andExpect(status().isBadRequest());
        GatewayIngressEnvelope envelope = envelope(UUID.randomUUID(), VEHICLE_ID, 0x02, "105.2384988", "35.2109657", Instant.parse("2026-08-12T08:59:50Z"));
        postIngress(java.util.Collections.nCopies(51, envelope)).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUnsupportedEnvelopeSchemaAndKindPerItem() throws Exception {
        GatewayIngressEnvelope unsupportedSchema = new GatewayIngressEnvelope(2, UUID.randomUUID(), "POSITION", Instant.parse("2026-08-12T09:00:00Z"), "{}");
        GatewayIngressEnvelope unsupportedKind = new GatewayIngressEnvelope(1, UUID.randomUUID(), "ALARM", Instant.parse("2026-08-12T09:00:00Z"), "{}");
        postIngress(List.of(unsupportedSchema, unsupportedKind)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.data[0].reasonCodes[0]").value("UNSUPPORTED_ENVELOPE"))
                .andExpect(jsonPath("$.data[1].status").value("REJECTED"))
                .andExpect(jsonPath("$.data[1].reasonCodes[0]").value("UNSUPPORTED_ENVELOPE"));
    }

    @Test
    void treatsOuterGatewayTimestampAsAuthoritativeAndRejectsMissingTerminalWithoutRollingBackNeighbor() throws Exception {
        UUID acceptedKey = UUID.randomUUID();
        UUID rejectedKey = UUID.randomUUID();
        GatewayIngressEnvelope payloadTimestamp = envelope(acceptedKey, VEHICLE_ID, 0x02, "105.2384988", "35.2109657", Instant.parse("2026-08-12T08:57:59Z"));
        GatewayIngressEnvelope accepted = new GatewayIngressEnvelope(1, acceptedKey, "POSITION", Instant.parse("2026-08-12T08:58:00Z"), payloadTimestamp.payloadJson());
        GatewayIngressEnvelope missingTerminal = new GatewayIngressEnvelope(1, rejectedKey, "POSITION", Instant.parse("2026-08-12T09:00:00Z"),
                accepted.payloadJson().replace(TERMINAL_ID.toString(), ""));
        postIngress(List.of(accepted, missingTerminal)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data[0].reasonCodes").isEmpty())
                .andExpect(jsonPath("$.data[1].status").value("REJECTED"))
                .andExpect(jsonPath("$.data[1].reasonCodes[0]").value("TERMINAL_BINDING_MISMATCH"));
        assertThat(eventRepository.findByIdempotencyKey(acceptedKey)).isPresent();
        assertThat(eventRepository.findByIdempotencyKey(rejectedKey)).isEmpty();
    }

    @Test
    void retainsAllRejectedReasonsAndMarksTheThirdConsecutiveQuarantineWithoutApplyingSnapshot() throws Exception {
        UUID rejectedKey = UUID.randomUUID();
        GatewayIngressEnvelope rejected = envelope(rejectedKey, VEHICLE_ID, 0, "0", "35.2103", Instant.parse("2026-08-12T08:59:50Z"));
        postIngress(List.of(rejected)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.data[0].reasonCodes").value(org.hamcrest.Matchers.hasItems("INVALID_COORDINATE", "POSITION_INVALID")));

        UUID first = UUID.randomUUID(), second = UUID.randomUUID(), third = UUID.randomUUID();
        postIngress(List.of(
                envelope(first, VEHICLE_ID, 0, "105.2384988", "35.2109657", Instant.parse("2026-08-12T08:59:50Z")),
                envelope(second, VEHICLE_ID, 0, "105.2384988", "35.2109657", Instant.parse("2026-08-12T08:59:51Z")),
                envelope(third, VEHICLE_ID, 0, "105.2384988", "35.2109657", Instant.parse("2026-08-12T08:59:52Z"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[2].reasonCodes").value(org.hamcrest.Matchers.hasItem("CONSECUTIVE_QUARANTINE")));
        assertThat(eventRepository.findByIdempotencyKey(third).orElseThrow().isSnapshotApplied()).isFalse();
        assertThat(vehicleRepository.findById(VEHICLE_ID).orElseThrow().getCurrentLocationEventId()).isNull();
    }

    private org.springframework.test.web.servlet.ResultActions postIngress(List<GatewayIngressEnvelope> batch) throws Exception { return internalPost(objectMapper.writeValueAsString(batch)); }
    private org.springframework.test.web.servlet.ResultActions internalPost(String body) throws Exception {
        return mockMvc.perform(post("/internal/jt-gateway/ingress").header("Authorization", "Bearer " + CREDENTIAL)
                .header("X-Service-Credential-Version", "1").contentType(MediaType.APPLICATION_JSON).content(body));
    }
    private GatewayIngressEnvelope envelope(UUID idempotencyKey, UUID vehicleId, long statusBits, String lon, String lat, Instant locatedAt) throws Exception {
        CanonicalPositionIngress payload = new CanonicalPositionIngress(TERMINAL_ID, vehicleId, "JT808_2019", 7,
                new BigDecimal(lon), new BigDecimal(lat), "WGS84", locatedAt, Instant.parse("2026-08-12T09:00:00Z"),
                0L, statusBits, new BigDecimal("50"), 90, 10, 8, "a".repeat(64));
        return new GatewayIngressEnvelope(1, idempotencyKey, "POSITION", payload.gatewayReceivedAt(), objectMapper.writeValueAsString(payload));
    }
    private static String sha256(String text) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); } catch (Exception exception) { throw new IllegalStateException(exception); } }

    @TestConfiguration
    static class LocationCheckerConfiguration {
        @Bean @Primary ServiceAreaLocationChecker gpsIngressAreaChecker() {
            return (longitude, latitude) -> true;
        }
    }
}
