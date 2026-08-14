package com.idavy.drtops.domain.alarm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VehicleAlarmIngressServiceTest {
    private static final UUID DEFAULT_POSITION_KEY = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void writesOneFactAndOutboxPerAlarmWithoutCollapsingOnePositionManyAlarms() {
        InMemoryAlarmStore store = new InMemoryAlarmStore();
        store.position(DEFAULT_POSITION_KEY, UUID.randomUUID(), "GOOD");
        VehicleAlarmIngressService service = new VehicleAlarmIngressService(store);

        service.ingest(List.of(start("ADAS", 1, "FORWARD_COLLISION"), start("DMS", 2, "PHONE")));

        assertThat(store.facts()).hasSize(2);
        assertThat(store.outbox()).hasSize(2);
    }

    @Test
    void keepsAdasAndDmsFactsDistinctWhenTheirTypeCodesAndIdentifiersCoincide() {
        InMemoryAlarmStore store = new InMemoryAlarmStore();
        store.position(DEFAULT_POSITION_KEY, UUID.randomUUID(), "GOOD");
        VehicleAlarmIngressService service = new VehicleAlarmIngressService(store);

        service.ingest(List.of(
                start("ADAS", 1, "FORWARD_COLLISION"),
                start("DMS", 1, "FATIGUE")));

        assertThat(store.facts()).hasSize(2);
        assertThat(store.outbox()).hasSize(2);
    }

    @Test
    void linksEveryAlarmFromOnePositionToTheSameEventAndUsesItsQuarantinedQuality() {
        InMemoryAlarmStore store = new InMemoryAlarmStore();
        UUID positionKey = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID locationEventId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        store.position(positionKey, locationEventId, "QUARANTINED", "[\"POSITION_INVALID\"]");
        VehicleAlarmIngressService service = new VehicleAlarmIngressService(store);

        service.ingest(List.of(
                start("ADAS", 1, "FORWARD_COLLISION").atPosition(positionKey),
                start("DMS", 2, "PHONE").atPosition(positionKey)));

        assertThat(store.facts()).hasSize(2).allSatisfy(alarm -> {
            assertThat(alarm.getLocationEventId()).isEqualTo(locationEventId);
            assertThat(alarm.getLocationQualityStatus()).isEqualTo("QUARANTINED");
            assertThat(alarm.getLocationQualityReasons()).isEqualTo("[\"POSITION_INVALID\"]");
        });
    }

    @Test
    void preservesTheAlarmWithRejectedQualityWhenItsPositionHasNoTrustedEvent() {
        InMemoryAlarmStore store = new InMemoryAlarmStore();
        VehicleAlarmIngressService service = new VehicleAlarmIngressService(store);

        UUID rejectedPositionKey = UUID.fromString("55555555-5555-5555-5555-555555555555");
        store.rejectedPosition(rejectedPositionKey);
        service.ingest(List.of(start("ADAS", 1, "FORWARD_COLLISION").atPosition(rejectedPositionKey)));

        assertThat(store.facts()).singleElement().satisfies(alarm -> {
            assertThat(alarm.getLocationEventId()).isNull();
            assertThat(alarm.getLocationQualityStatus()).isEqualTo("REJECTED");
            assertThat(alarm.getLocationQualityReasons()).contains("INVALID_COORDINATE");
        });
        assertThat(store.outbox()).hasSize(1);
    }

    @Test
    void rejectsAClaimThatDoesNotMatchTheActiveTerminalVehicleBinding() {
        InMemoryAlarmStore store = new InMemoryAlarmStore();
        store.position(DEFAULT_POSITION_KEY, UUID.randomUUID(), "GOOD");
        store.rejectBindings();
        VehicleAlarmIngressService service = new VehicleAlarmIngressService(store);

        assertThatThrownBy(() -> service.ingest(List.of(start("ADAS", 1, "FORWARD_COLLISION"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("terminal vehicle binding mismatch");
        assertThat(store.facts()).isEmpty();
        assertThat(store.outbox()).isEmpty();
    }

    @Test
    void acceptsBufferedAlarmWhenTheClaimMatchedTheBindingAtGatewayReceiptTime() {
        InMemoryAlarmStore store = new InMemoryAlarmStore();
        store.position(DEFAULT_POSITION_KEY, UUID.randomUUID(), "GOOD");
        store.acceptHistoricalBindingUntil(Instant.parse("2026-01-15T02:00:10Z"));
        VehicleAlarmIngressService service = new VehicleAlarmIngressService(store);

        service.ingest(List.of(start("ADAS", 1, "FORWARD_COLLISION")));

        assertThat(store.facts()).hasSize(1);
        assertThat(store.outbox()).hasSize(1);
    }

    @Test
    void replaysTheSameBatchWithoutDuplicatingFactsOrOutbox() {
        InMemoryAlarmStore store = new InMemoryAlarmStore();
        store.position(DEFAULT_POSITION_KEY, UUID.randomUUID(), "GOOD");
        VehicleAlarmIngressService service = new VehicleAlarmIngressService(store);
        List<VehicleAlarmIngressService.AlarmFact> batch = List.of(start("ADAS", 1, "FORWARD_COLLISION"));

        service.ingest(batch);
        service.ingest(batch);

        assertThat(store.facts()).hasSize(1);
        assertThat(store.outbox()).hasSize(1);
    }

    @Test
    void endUpdatesOnlyTheMatchingStartAndWritesAnOutboxEvent() {
        InMemoryAlarmStore store = new InMemoryAlarmStore();
        store.position(DEFAULT_POSITION_KEY, UUID.randomUUID(), "GOOD");
        VehicleAlarmIngressService service = new VehicleAlarmIngressService(store);
        VehicleAlarmIngressService.AlarmFact start = start("ADAS", 1, "FORWARD_COLLISION");

        service.ingest(List.of(start));
        service.ingest(List.of(start.endAt(Instant.parse("2026-01-15T02:01:00Z"))));

        assertThat(store.facts()).hasSize(1);
        assertThat(store.facts().getFirst().getEndedAt()).isEqualTo(Instant.parse("2026-01-15T02:01:00Z"));
        assertThat(store.outbox()).hasSize(2);
    }

    @Test
    void endMatchesTheSourceAlarmIdWhenTheTerminalIdentifierEvidenceChanges() {
        InMemoryAlarmStore store = new InMemoryAlarmStore();
        store.position(DEFAULT_POSITION_KEY, UUID.randomUUID(), "GOOD");
        VehicleAlarmIngressService service = new VehicleAlarmIngressService(store);
        VehicleAlarmIngressService.AlarmFact start = start("ADAS", 1, "FORWARD_COLLISION");
        VehicleAlarmIngressService.AlarmFact end = withTerminalIdentifier(
                start.endAt(Instant.parse("2026-01-15T02:01:00Z")), "b".repeat(64));

        service.ingest(List.of(start));
        service.ingest(List.of(end));

        assertThat(store.facts()).singleElement().satisfies(alarm -> {
            assertThat(alarm.getTerminalAlarmId()).isEqualTo(0x00001001L);
            assertThat(alarm.getTerminalAlarmIdentifier()).isEqualTo("a".repeat(64));
            assertThat(alarm.getEndedAt()).isEqualTo(Instant.parse("2026-01-15T02:01:00Z"));
        });
        assertThat(store.outbox()).hasSize(2);
    }

    @Test
    void endCannotCloseAnOpenStartThatBelongsToAPreviousVehicleBinding() {
        InMemoryAlarmStore store = new InMemoryAlarmStore();
        store.position(DEFAULT_POSITION_KEY, UUID.randomUUID(), "GOOD");
        VehicleAlarmIngressService service = new VehicleAlarmIngressService(store);
        VehicleAlarmIngressService.AlarmFact oldVehicleStart = start("ADAS", 1, "FORWARD_COLLISION");

        service.ingest(List.of(oldVehicleStart));
        service.ingest(List.of(withVehicle(
                oldVehicleStart.endAt(Instant.parse("2026-01-15T02:01:00Z")),
                UUID.fromString("99999999-9999-9999-9999-999999999999"))));

        assertThat(store.facts()).singleElement().satisfies(alarm -> assertThat(alarm.getEndedAt()).isNull());
        assertThat(store.outbox()).hasSize(1);
    }

    @Test
    void rollsBackTheWholeBatchWhenAnyFactIsInvalid() {
        InMemoryAlarmStore store = new InMemoryAlarmStore();
        VehicleAlarmIngressService service = new VehicleAlarmIngressService(store);

        assertThatThrownBy(() -> service.ingest(List.of(start("ADAS", 1, "FORWARD_COLLISION"), invalid())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.facts()).isEmpty();
        assertThat(store.outbox()).isEmpty();
    }

    @Test
    void rejectsMissingRequiredFieldsAsAStableValidationFailureBeforeLocking() {
        InMemoryAlarmStore store = new InMemoryAlarmStore();
        VehicleAlarmIngressService service = new VehicleAlarmIngressService(store);
        VehicleAlarmIngressService.AlarmFact missingModule = new VehicleAlarmIngressService.AlarmFact(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"), "T/JSATL12-2017", null, 1,
                "FORWARD_COLLISION", 0x00001001L, "START", 1, "a".repeat(64), Instant.parse("2026-01-15T02:00:00Z"),
                Instant.parse("2026-01-15T02:00:01Z"), new BigDecimal("118.0000000"),
                new BigDecimal("32.0000000"), new BigDecimal("60.00"), UUID.randomUUID(),
                "UNASSESSED", "a".repeat(64));

        assertThatThrownBy(() -> service.ingest(List.of(missingModule)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid alarm fact");
        assertThat(store.facts()).isEmpty();
        assertThat(store.outbox()).isEmpty();
    }

    @Test
    void rejectsDatabaseBoundFieldViolationsBeforeAnyFactOrOutboxMutation() {
        InMemoryAlarmStore store = new InMemoryAlarmStore();
        store.position(DEFAULT_POSITION_KEY, UUID.randomUUID(), "GOOD");
        VehicleAlarmIngressService service = new VehicleAlarmIngressService(store);
        VehicleAlarmIngressService.AlarmFact invalidLongitude = new VehicleAlarmIngressService.AlarmFact(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"), "T/JSATL12-2017", "ADAS", 1,
                "FORWARD_COLLISION", 0x00001001L, "START", 1, "a".repeat(64), Instant.parse("2026-01-15T02:00:00Z"),
                Instant.parse("2026-01-15T02:00:01Z"), new BigDecimal("181.0000000"),
                new BigDecimal("32.0000000"), new BigDecimal("60.00"), DEFAULT_POSITION_KEY,
                "UNASSESSED", "a".repeat(64));

        assertThatThrownBy(() -> service.ingest(List.of(start("DMS", 2, "PHONE"), invalidLongitude)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid alarm fact");
        assertThat(store.facts()).isEmpty();
        assertThat(store.outbox()).isEmpty();
    }

    @Test
    void ignoresAnOutOfOrderEndWithoutCreatingAFactOrOutboxEvent() {
        InMemoryAlarmStore store = new InMemoryAlarmStore();
        store.position(DEFAULT_POSITION_KEY, UUID.randomUUID(), "GOOD");
        VehicleAlarmIngressService service = new VehicleAlarmIngressService(store);

        service.ingest(List.of(start("ADAS", 1, "FORWARD_COLLISION")
                .endAt(Instant.parse("2026-01-15T02:01:00Z"))));

        assertThat(store.facts()).isEmpty();
        assertThat(store.outbox()).isEmpty();
    }

    private static VehicleAlarmIngressService.AlarmFact start(String module, int typeCode, String type) {
        return new VehicleAlarmIngressService.AlarmFact(UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"), "T/JSATL12-2017", module, typeCode, type,
                0x00001001L, "START", 1, "a".repeat(64), Instant.parse("2026-01-15T02:00:00Z"), Instant.parse("2026-01-15T02:00:01Z"),
                new BigDecimal("118.0000000"), new BigDecimal("32.0000000"), new BigDecimal("60.00"),
                DEFAULT_POSITION_KEY, "UNASSESSED", "a".repeat(64));
    }

    private static VehicleAlarmIngressService.AlarmFact withTerminalIdentifier(
            VehicleAlarmIngressService.AlarmFact fact, String terminalAlarmIdentifier) {
        return new VehicleAlarmIngressService.AlarmFact(
                fact.terminalId(), fact.vehicleId(), fact.standard(), fact.module(), fact.typeCode(), fact.alarmType(),
                fact.terminalAlarmId(), fact.state(), fact.level(), terminalAlarmIdentifier, fact.occurredAt(),
                fact.gatewayReceivedAt(), fact.longitude(), fact.latitude(), fact.speedKph(),
                fact.positionIdempotencyKey(), fact.locationQualityStatus(), fact.payloadDigest());
    }

    private static VehicleAlarmIngressService.AlarmFact invalid() {
        return new VehicleAlarmIngressService.AlarmFact(UUID.randomUUID(), UUID.randomUUID(), "T/JSATL12-2017", "DSM", 1,
                "FATIGUE", 0x00002001L, "START", 1, "b".repeat(64), Instant.parse("2026-01-15T02:00:00Z"), Instant.parse("2026-01-15T02:00:01Z"),
                new BigDecimal("118"), new BigDecimal("32"), new BigDecimal("60"), UUID.randomUUID(),
                "UNASSESSED", "not-a-digest");
    }

    private static VehicleAlarmIngressService.AlarmFact withVehicle(
            VehicleAlarmIngressService.AlarmFact fact, UUID vehicleId) {
        return new VehicleAlarmIngressService.AlarmFact(
                fact.terminalId(), vehicleId, fact.standard(), fact.module(), fact.typeCode(), fact.alarmType(),
                fact.terminalAlarmId(), fact.state(), fact.level(), fact.terminalAlarmIdentifier(), fact.occurredAt(), fact.gatewayReceivedAt(),
                fact.longitude(), fact.latitude(), fact.speedKph(), fact.positionIdempotencyKey(),
                fact.locationQualityStatus(), fact.payloadDigest());
    }
}
