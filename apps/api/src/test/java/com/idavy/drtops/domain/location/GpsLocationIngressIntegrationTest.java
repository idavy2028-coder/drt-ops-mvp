package com.idavy.drtops.domain.location;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.time.OffsetDateTime;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
    @Autowired JtGatewayIngressReceiptRepository receiptRepository;
    @Autowired VehicleLocationSnapshotService snapshotService;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired TestServiceAreaLocationChecker locationChecker;

    @DynamicPropertySource
    static void credential(DynamicPropertyRegistry registry) {
        registry.add("jt.gateway.service-credentials.current.version", () -> "1");
        registry.add("jt.gateway.service-credentials.current.hash", () -> sha256(CREDENTIAL));
    }

    @BeforeEach
    void setUp() {
        locationChecker.reset();
        receiptRepository.deleteAll(); gatewayAuditRepository.deleteAll(); eventRepository.deleteAll(); bindingRepository.deleteAll(); terminalRepository.deleteAll(); vehicleRepository.deleteAll();
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
        assertThat(eventRepository.findByIdempotencyKey(goodKey).orElseThrow().getQualityReasons()).isEqualTo("[]");
        assertThat(eventRepository.findByIdempotencyKey(quarantinedKey).orElseThrow().isSnapshotApplied()).isFalse();
        Vehicle vehicle = vehicleRepository.findById(VEHICLE_ID).orElseThrow();
        assertThat(vehicle.getCurrentLocationEventId()).isEqualTo(eventRepository.findByIdempotencyKey(goodKey).orElseThrow().getId());
        assertThat(vehicle.getCurrentLocationQualityReasons()).isEqualTo("[]");
    }

    @Test
    void manualSnapshotAfterGpsClearsEveryGpsOnlyFieldAndRestoresGoodQuality() throws Exception {
        Instant locatedAt = Instant.parse("2026-08-12T08:59:50Z");
        CanonicalPositionIngress warningGps = copy(payload(VEHICLE_ID, locatedAt), locatedAt,
                new BigDecimal("120"), 90, "a".repeat(64));
        UUID gpsKey = UUID.randomUUID();
        postIngress(List.of(envelope(gpsKey, warningGps))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data[0].reasonCodes[0]").value("SPEED_WARNING"));

        Vehicle gpsSnapshot = vehicleRepository.findById(VEHICLE_ID).orElseThrow();
        assertThat(gpsSnapshot.getCurrentLocationQualityStatus()).isEqualTo(LocationQualityStatus.WARNING);
        assertThat(gpsSnapshot.getCurrentLocationTerminalId()).isEqualTo(TERMINAL_ID);
        assertThat(gpsSnapshot.getCurrentLocationGatewayReceivedAt()).isNotNull();
        assertThat(gpsSnapshot.getCurrentLocationSpeedKph()).isEqualByComparingTo("120");
        assertThat(gpsSnapshot.getCurrentLocationDirectionDegrees()).isEqualTo(90);

        OffsetDateTime manualReportedAt = OffsetDateTime.parse("2026-08-12T09:01:00Z");
        VehicleLocationEvent manual = VehicleLocationEvent.record(
                VEHICLE_ID, null, null, null, LocationEventType.MANUAL_REPORT, LocationSource.MANUAL_DISPATCHER,
                "POINT(105.2422 35.2104)", new BigDecimal("105.2422"), new BigDecimal("35.2104"), "GCJ02",
                "人工复核位置", manualReportedAt, manualReportedAt.plusSeconds(1), UUID.randomUUID(),
                "人工复核覆盖 GPS", null, null, UUID.randomUUID(), "b".repeat(64), true, false);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            eventRepository.save(manual);
            snapshotService.apply(manual);
        });

        Vehicle manualSnapshot = vehicleRepository.findById(VEHICLE_ID).orElseThrow();
        assertThat(manualSnapshot.getCurrentLocationSource()).isEqualTo(LocationSource.MANUAL_DISPATCHER);
        assertThat(manualSnapshot.getCurrentLocationTerminalId()).isNull();
        assertThat(manualSnapshot.getCurrentLocationGatewayReceivedAt()).isNull();
        assertThat(manualSnapshot.getCurrentLocationSpeedKph()).isNull();
        assertThat(manualSnapshot.getCurrentLocationDirectionDegrees()).isNull();
        assertThat(manualSnapshot.getCurrentLocationQualityStatus()).isEqualTo(LocationQualityStatus.GOOD);
        assertThat(manualSnapshot.getCurrentLocationQualityReasons()).isEqualTo("[]");
        assertThat(manualSnapshot.isCurrentLocationStale()).isFalse();
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
        postIngress(List.of(accepted)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("ACCEPTED"));
        postIngress(List.of(accepted)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("REPLAYED"));
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
    void replaysRejectedIngressAcrossRequestsWithoutWritingAnotherAudit() throws Exception {
        UUID idempotencyKey = UUID.randomUUID();
        GatewayIngressEnvelope rejected = new GatewayIngressEnvelope(1, idempotencyKey, "ALARM", Instant.parse("2026-08-12T09:00:00Z"), "{}");

        postIngress(List.of(rejected)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.data[0].reasonCodes[0]").value("UNSUPPORTED_ENVELOPE"));
        postIngress(List.of(rejected)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("REPLAYED"))
                .andExpect(jsonPath("$.data[0].reasonCodes[0]").value("UNSUPPORTED_ENVELOPE"));

        assertThat(gatewayAuditRepository.findAll()).singleElement()
                .satisfies(audit -> assertThat(audit.getReasonCode()).isEqualTo("UNSUPPORTED_ENVELOPE"));
    }

    @Test
    void replaysTheSameKeyInsideOneBatchWithoutDuplicatingTheEvent() throws Exception {
        UUID idempotencyKey = UUID.randomUUID();
        GatewayIngressEnvelope accepted = envelope(
                idempotencyKey, VEHICLE_ID, 0x02, "105.2384988", "35.2109657",
                Instant.parse("2026-08-12T08:59:50Z"));

        postIngress(List.of(accepted, accepted)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data[1].status").value("REPLAYED"))
                .andExpect(jsonPath("$.data[1].reasonCodes").isEmpty());

        assertThat(eventRepository.findAll()).hasSize(1);
        assertThat(receiptRepository.findAll()).singleElement().satisfies(receipt -> {
            assertThat(receipt.getFinalStatus()).isEqualTo("ACCEPTED");
            assertThat(receipt.getReasonCodes()).isEmpty();
        });
    }

    @Test
    void rollsBackTheClaimAndSideEffectsWhenItemProcessingFailsSoTheSameKeyCanRetry() throws Exception {
        UUID idempotencyKey = UUID.randomUUID();
        GatewayIngressEnvelope accepted = envelope(
                idempotencyKey, VEHICLE_ID, 0x02, "105.2384988", "35.2109657",
                Instant.parse("2026-08-12T08:59:50Z"));
        locationChecker.failNextCheck();

        assertThatThrownBy(() -> postIngress(List.of(accepted)))
                .hasStackTraceContaining("forced service area failure");
        assertThat(receiptRepository.findById(idempotencyKey)).isEmpty();
        assertThat(eventRepository.findByIdempotencyKey(idempotencyKey)).isEmpty();
        assertThat(gatewayAuditRepository.findAll()).isEmpty();

        postIngress(List.of(accepted)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("ACCEPTED"));
        assertThat(receiptRepository.findById(idempotencyKey)).isPresent();
        assertThat(eventRepository.findByIdempotencyKey(idempotencyKey)).isPresent();
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

    @Test
    void retainsEveryIndependentReasonWhenAnInvalidCoordinateIsAlsoStaleAndNotLocated() throws Exception {
        UUID idempotencyKey = UUID.randomUUID();
        GatewayIngressEnvelope rejected = envelopeAt(idempotencyKey, VEHICLE_ID, 0, "181", "35.2103",
                Instant.parse("2026-08-12T08:57:59Z"), Instant.parse("2026-08-12T09:00:00Z"));

        postIngress(List.of(rejected)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.data[0].reasonCodes").value(org.hamcrest.Matchers.hasItems(
                        "INVALID_COORDINATE", "POSITION_INVALID", "RECEIVE_DELAY_EXCEEDED")));

        assertThat(eventRepository.findAll()).isEmpty();
        assertThat(receiptRepository.findById(idempotencyKey).orElseThrow().getReasonCodes())
                .containsExactlyInAnyOrder("INVALID_COORDINATE", "POSITION_INVALID", "RECEIVE_DELAY_EXCEEDED");
        assertThat(gatewayAuditRepository.findAll()).singleElement()
                .satisfies(audit -> assertThat(audit.getReasonCode()).isEqualTo("INVALID_COORDINATE"));
    }

    @Test
    void auditsAndReceiptsUnsupportedAndMalformedPayloadRejectionsWithoutIdentity() throws Exception {
        UUID schemaKey = UUID.randomUUID();
        UUID kindKey = UUID.randomUUID();
        UUID payloadKey = UUID.randomUUID();
        GatewayIngressEnvelope unsupportedSchema = new GatewayIngressEnvelope(
                2, schemaKey, "POSITION", Instant.parse("2026-08-12T09:00:00Z"), "{}");
        GatewayIngressEnvelope unsupportedKind = new GatewayIngressEnvelope(
                1, kindKey, "ALARM", Instant.parse("2026-08-12T09:00:00Z"), "{}");
        GatewayIngressEnvelope malformedPayload = new GatewayIngressEnvelope(
                1, payloadKey, "POSITION", Instant.parse("2026-08-12T09:00:00Z"), "{not-json");

        postIngress(List.of(unsupportedSchema, unsupportedKind, malformedPayload)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].reasonCodes[0]").value("UNSUPPORTED_ENVELOPE"))
                .andExpect(jsonPath("$.data[1].reasonCodes[0]").value("UNSUPPORTED_ENVELOPE"))
                .andExpect(jsonPath("$.data[2].reasonCodes[0]").value("INVALID_PAYLOAD"));

        assertThat(gatewayAuditRepository.findAll()).hasSize(3).allSatisfy(audit -> {
            assertThat(audit.getTerminalId()).isNull();
            assertThat(audit.getVehicleId()).isNull();
            assertThat(audit.getEventType()).isEqualTo(com.idavy.drtops.domain.terminal.JtGatewayAuditEvent.EventType.PROTOCOL_REJECTED);
        });
        assertThat(receiptRepository.findById(schemaKey).orElseThrow().getReasonCodes())
                .containsExactly("UNSUPPORTED_ENVELOPE");
        assertThat(receiptRepository.findById(kindKey).orElseThrow().getReasonCodes())
                .containsExactly("UNSUPPORTED_ENVELOPE");
        assertThat(receiptRepository.findById(payloadKey).orElseThrow().getReasonCodes())
                .containsExactly("INVALID_PAYLOAD");
        assertThat(receiptRepository.findAll()).allSatisfy(receipt -> {
            assertThat(receipt.getFinalStatus()).isEqualTo("REJECTED");
            assertThat(receipt.getReasonCodes()).isNotEmpty();
        });
    }

    @Test
    void countsConsecutiveQuarantinesByTrustedGatewayOrderAndLetsANormalPointBreakTheRun() throws Exception {
        UUID earlyDelay = UUID.randomUUID();
        UUID secondEarlyDelay = UUID.randomUUID();
        UUID normal = UUID.randomUUID();
        postIngress(List.of(
                envelopeAt(earlyDelay, VEHICLE_ID, 0x02, "105.2384988", "35.2109657",
                        Instant.parse("2026-08-12T08:57:59Z"), Instant.parse("2026-08-12T09:00:00Z")),
                envelopeAt(secondEarlyDelay, VEHICLE_ID, 0x02, "105.2384988", "35.2109657",
                        Instant.parse("2026-08-12T08:58:00Z"), Instant.parse("2026-08-12T09:00:01Z")),
                envelopeAt(normal, VEHICLE_ID, 0x02, "105.2384988", "35.2109657",
                        Instant.parse("2026-08-12T09:00:02Z"), Instant.parse("2026-08-12T09:00:02Z"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[2].reasonCodes").isEmpty());

        UUID delayAfterNormal = UUID.randomUUID();
        UUID futureAfterNormal = UUID.randomUUID();
        UUID thirdAfterNormal = UUID.randomUUID();
        postIngress(List.of(
                envelopeAt(delayAfterNormal, VEHICLE_ID, 0x02, "105.2384988", "35.2109657",
                        Instant.parse("2026-08-12T08:58:02Z"), Instant.parse("2026-08-12T09:00:03Z")),
                envelopeAt(futureAfterNormal, VEHICLE_ID, 0x02, "105.2384988", "35.2109657",
                        Instant.parse("2026-08-12T09:02:05Z"), Instant.parse("2026-08-12T09:00:04Z")),
                envelopeAt(thirdAfterNormal, VEHICLE_ID, 0x02, "105.2384988", "35.2109657",
                        Instant.parse("2026-08-12T08:58:04Z"), Instant.parse("2026-08-12T09:00:05Z"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].reasonCodes").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem("CONSECUTIVE_QUARANTINE"))))
                .andExpect(jsonPath("$.data[1].reasonCodes").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem("CONSECUTIVE_QUARANTINE"))))
                .andExpect(jsonPath("$.data[2].reasonCodes").value(org.hamcrest.Matchers.hasItem(
                        "CONSECUTIVE_QUARANTINE")));

        assertThat(eventRepository.findByIdempotencyKey(thirdAfterNormal).orElseThrow().isSnapshotApplied()).isFalse();
        assertThat(vehicleRepository.findById(VEHICLE_ID).orElseThrow().getCurrentLocationEventId())
                .isEqualTo(eventRepository.findByIdempotencyKey(normal).orElseThrow().getId());
        assertThatRepositoryUsesStableGatewayOrder();
    }

    @Test
    void rejectsEachSemanticallyInvalidItemWithoutRollingBackItsValidBatchNeighbor() throws Exception {
        GatewayIngressEnvelope valid = envelope(UUID.randomUUID(), VEHICLE_ID, 0x02, "105.2384988", "35.2109657", Instant.parse("2026-08-12T08:59:50Z"));
        CanonicalPositionIngress base = payload(VEHICLE_ID, Instant.parse("2026-08-12T08:59:50Z"));
        GatewayIngressEnvelope missingTerminalTime = envelope(UUID.randomUUID(), copy(base, null, base.speedKph(), base.directionDegrees(), base.payloadDigest()));
        GatewayIngressEnvelope missingGatewayTime = new GatewayIngressEnvelope(1, UUID.randomUUID(), "POSITION", null,
                objectMapper.writeValueAsString(base));
        GatewayIngressEnvelope negativeSpeed = envelope(UUID.randomUUID(), copy(base, base.terminalLocatedAt(), new BigDecimal("-0.1"), base.directionDegrees(), base.payloadDigest()));
        GatewayIngressEnvelope invalidDirection = envelope(UUID.randomUUID(), copy(base, base.terminalLocatedAt(), base.speedKph(), 360, base.payloadDigest()));
        GatewayIngressEnvelope invalidDigest = envelope(UUID.randomUUID(), copy(base, base.terminalLocatedAt(), base.speedKph(), base.directionDegrees(), "invalid"));
        GatewayIngressEnvelope missingProtocol = envelope(UUID.randomUUID(), new CanonicalPositionIngress(
                base.terminalId(), base.vehicleId(), null, base.messageSerialNo(), base.rawLongitude(), base.rawLatitude(),
                base.rawCoordinateSystem(), base.terminalLocatedAt(), base.gatewayReceivedAt(), base.alarmBits(),
                base.statusBits(), base.speedKph(), base.directionDegrees(), base.altitudeMeters(),
                base.satelliteCount(), base.payloadDigest()));
        GatewayIngressEnvelope missingAltitude = envelope(UUID.randomUUID(), new CanonicalPositionIngress(
                base.terminalId(), base.vehicleId(), base.protocolVersion(), base.messageSerialNo(), base.rawLongitude(),
                base.rawLatitude(), base.rawCoordinateSystem(), base.terminalLocatedAt(), base.gatewayReceivedAt(),
                base.alarmBits(), base.statusBits(), base.speedKph(), base.directionDegrees(), null,
                base.satelliteCount(), base.payloadDigest()));

        postIngress(java.util.Arrays.asList(valid, null, missingTerminalTime, missingGatewayTime, negativeSpeed,
                        invalidDirection, invalidDigest, missingProtocol, missingAltitude))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data[1].status").value("REJECTED"))
                .andExpect(jsonPath("$.data[2].status").value("REJECTED"))
                .andExpect(jsonPath("$.data[3].status").value("REJECTED"))
                .andExpect(jsonPath("$.data[4].status").value("REJECTED"))
                .andExpect(jsonPath("$.data[5].status").value("REJECTED"))
                .andExpect(jsonPath("$.data[6].status").value("REJECTED"))
                .andExpect(jsonPath("$.data[7].status").value("REJECTED"))
                .andExpect(jsonPath("$.data[8].status").value("REJECTED"));
        assertThat(eventRepository.findAll()).hasSize(1);
        assertThat(gatewayAuditRepository.findAll()).hasSize(8);
    }

    private org.springframework.test.web.servlet.ResultActions postIngress(List<GatewayIngressEnvelope> batch) throws Exception { return internalPost(objectMapper.writeValueAsString(batch)); }
    private org.springframework.test.web.servlet.ResultActions internalPost(String body) throws Exception {
        return mockMvc.perform(post("/internal/jt-gateway/ingress").header("Authorization", "Bearer " + CREDENTIAL)
                .header("X-Service-Credential-Version", "1").contentType(MediaType.APPLICATION_JSON).content(body));
    }
    private GatewayIngressEnvelope envelope(UUID idempotencyKey, UUID vehicleId, long statusBits, String lon, String lat, Instant locatedAt) throws Exception {
        CanonicalPositionIngress payload = payload(vehicleId, locatedAt, statusBits, new BigDecimal(lon), new BigDecimal(lat));
        return new GatewayIngressEnvelope(1, idempotencyKey, "POSITION", payload.gatewayReceivedAt(), objectMapper.writeValueAsString(payload));
    }
    private GatewayIngressEnvelope envelopeAt(UUID idempotencyKey, UUID vehicleId, long statusBits, String lon,
            String lat, Instant locatedAt, Instant gatewayReceivedAt) throws Exception {
        CanonicalPositionIngress payload = payload(vehicleId, locatedAt, statusBits, new BigDecimal(lon), new BigDecimal(lat));
        return new GatewayIngressEnvelope(
                1, idempotencyKey, "POSITION", gatewayReceivedAt, objectMapper.writeValueAsString(payload));
    }
    private GatewayIngressEnvelope envelope(UUID idempotencyKey, CanonicalPositionIngress payload) throws Exception {
        return new GatewayIngressEnvelope(1, idempotencyKey, "POSITION", payload.gatewayReceivedAt(), objectMapper.writeValueAsString(payload));
    }
    private CanonicalPositionIngress payload(UUID vehicleId, Instant locatedAt) {
        return payload(vehicleId, locatedAt, 0x02, new BigDecimal("105.2384988"), new BigDecimal("35.2109657"));
    }
    private CanonicalPositionIngress payload(UUID vehicleId, Instant locatedAt, long statusBits, BigDecimal longitude, BigDecimal latitude) {
        return new CanonicalPositionIngress(TERMINAL_ID, vehicleId, "JT808_2019", 7, longitude, latitude, "WGS84", locatedAt,
                Instant.parse("2026-08-12T09:00:00Z"), 0L, statusBits, new BigDecimal("50"), 90, 10, 8, "a".repeat(64));
    }
    private CanonicalPositionIngress copy(CanonicalPositionIngress source, Instant terminalLocatedAt, BigDecimal speedKph,
            Integer directionDegrees, String payloadDigest) {
        return new CanonicalPositionIngress(source.terminalId(), source.vehicleId(), source.protocolVersion(), source.messageSerialNo(),
                source.rawLongitude(), source.rawLatitude(), source.rawCoordinateSystem(), terminalLocatedAt,
                source.gatewayReceivedAt(), source.alarmBits(), source.statusBits(), speedKph, directionDegrees,
                source.altitudeMeters(), source.satelliteCount(), payloadDigest);
    }
    private static String sha256(String text) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); } catch (Exception exception) { throw new IllegalStateException(exception); } }

    private static void assertThatRepositoryUsesStableGatewayOrder() {
        assertThat(java.util.Arrays.stream(VehicleLocationEventRepository.class.getMethods())
                .map(java.lang.reflect.Method::getName))
                .contains("findTop3ByVehicleIdAndGatewayReceivedAtIsNotNullOrderByGatewayReceivedAtDescIdDesc");
    }

    @TestConfiguration
    static class LocationCheckerConfiguration {
        @Bean @Primary TestServiceAreaLocationChecker gpsIngressAreaChecker() {
            return new TestServiceAreaLocationChecker();
        }
    }

    static final class TestServiceAreaLocationChecker implements ServiceAreaLocationChecker {
        private boolean failNext;

        @Override
        public boolean isInsideEnabledArea(BigDecimal longitude, BigDecimal latitude) {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("forced service area failure");
            }
            return true;
        }

        void failNextCheck() {
            failNext = true;
        }

        void reset() {
            failNext = false;
        }
    }
}
