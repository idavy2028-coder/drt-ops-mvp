package com.idavy.drtops.domain.location;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@DataJpaTest(showSql = false, properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class VehicleLocationRecorderTest {

    private static final Instant NOW = Instant.parse("2026-07-14T02:00:00Z");
    private static final OffsetDateTime REPORTED_AT = OffsetDateTime.parse("2026-07-14T09:00:00+08:00");
    private static final BigDecimal INSIDE_LONGITUDE = new BigDecimal("121.4737000");
    private static final BigDecimal LATITUDE = new BigDecimal("31.2304000");

    @Autowired
    private VehicleLocationEventRepository eventRepository;

    @Autowired
    private VehicleRepository vehicleRepository;

    @Autowired
    private EntityManager entityManager;

    private VehicleLocationRecorder recorder;
    private VehicleLocationSnapshotService snapshotService;
    private UUID vehicleId;

    @BeforeEach
    void setUp() {
        vehicleId = UUID.randomUUID();
        vehicleRepository.save(Vehicle.create(
                vehicleId,
                "沪A" + vehicleId.toString().substring(0, 5),
                "MINIBUS",
                8,
                "AVAILABLE",
                "POINT(121.4700 31.2300)",
                "浦东车队",
                true));
        ServiceAreaLocationChecker checker = (longitude, latitude) ->
                longitude.compareTo(new BigDecimal("122.0000000")) < 0;
        recorder = new VehicleLocationRecorder(
                eventRepository,
                vehicleRepository,
                idempotencyKey -> { },
                checker,
                Clock.fixed(NOW, ZoneOffset.UTC));
        snapshotService = new VehicleLocationSnapshotService(vehicleRepository);
    }

    @Test
    void appendsNewEventAndAppliesVehicleSnapshotSeparately() {
        LocationReportResult result = recorder.append(command(UUID.randomUUID()));

        assertThat(result.replayed()).isFalse();
        assertThat(result.event().isSnapshotApplied()).isTrue();
        assertThat(result.warnings()).isEmpty();

        snapshotService.apply(result.event());
        entityManager.flush();
        entityManager.clear();

        Vehicle vehicle = vehicleRepository.findById(vehicleId).orElseThrow();
        assertThat(vehicle.getCurrentLocation()).isEqualTo("POINT(121.4737 31.2304)");
        assertThat(vehicle.getCurrentLocationAddress()).isEqualTo("上海市浦东新区世纪大道 100 号");
        assertThat(vehicle.getCurrentLocationSource()).isEqualTo(LocationSource.MANUAL_DISPATCHER);
        assertThat(vehicle.getCurrentLocationCoordinateSystem()).isEqualTo("GCJ02");
        assertThat(vehicle.getCurrentLocationEventId()).isEqualTo(result.event().getId());
    }

    @Test
    void keepsOlderEventWithoutApplyingItToSnapshot() {
        Vehicle vehicle = vehicleRepository.findByIdForLocationUpdate(vehicleId).orElseThrow();
        UUID currentEventId = UUID.randomUUID();
        vehicle.applyLocationSnapshot(
                "POINT(121.4800 31.2400)",
                "上海市浦东新区世纪大道 200 号",
                LocationSource.MANUAL_DISPATCHER,
                "GCJ02",
                REPORTED_AT.plusMinutes(1),
                REPORTED_AT.plusMinutes(1).plusSeconds(2),
                currentEventId,
                null);

        LocationReportCommand historical = command(UUID.randomUUID());
        LocationReportResult result = recorder.append(historical);
        snapshotService.apply(result.event());

        assertThat(result.event().isSnapshotApplied()).isFalse();
        assertThat(result.warnings()).containsExactly(LocationWarning.HISTORICAL_EVENT_NOT_APPLIED_TO_SNAPSHOT);
        assertThat(vehicle.getCurrentLocationEventId()).isEqualTo(currentEventId);
        assertThat(eventRepository.findById(result.event().getId())).isPresent();

        LocationReportResult replay = recorder.append(historical);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.warnings())
                .containsExactly(LocationWarning.HISTORICAL_EVENT_NOT_APPLIED_TO_SNAPSHOT);
    }

    @Test
    void returnsOriginalEventForRepeatedIdempotencyKey() {
        UUID idempotencyKey = UUID.randomUUID();
        LocationReportCommand command = command(idempotencyKey);

        LocationReportResult first = recorder.append(command);
        LocationReportResult replay = recorder.append(command);

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.event().getId()).isEqualTo(first.event().getId());
        assertThat(recorder.findReplay(idempotencyKey, first.event().getRequestFingerprint()))
                .hasValueSatisfying(result -> assertThat(result.replayed()).isTrue());
    }

    @Test
    void rejectsDifferentFingerprintForRepeatedIdempotencyKey() {
        UUID idempotencyKey = UUID.randomUUID();
        LocationReportCommand original = command(idempotencyKey);
        recorder.append(original);

        LocationReportCommand changed = commandWithNote(original, "不同备注");

        assertThatThrownBy(() -> recorder.append(changed))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void replaysEquivalentDecimalsAndReportedInstant() {
        UUID idempotencyKey = UUID.randomUUID();
        LocationReportCommand original = command(idempotencyKey);
        LocationReportResult first = recorder.append(original);
        LocationReportCommand equivalent = new LocationReportCommand(
                original.vehicleId(),
                original.vehicleTaskId(),
                original.taskStopId(),
                original.virtualStopId(),
                original.eventType(),
                new BigDecimal("121.4737"),
                new BigDecimal("31.2304"),
                original.standardizedAddress(),
                original.driverReportedAt().withOffsetSameInstant(ZoneOffset.UTC),
                original.recordedBy(),
                original.note(),
                original.correctionReason(),
                original.correctsEventId(),
                original.idempotencyKey());

        LocationReportResult replay = recorder.append(equivalent);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.event().getId()).isEqualTo(first.event().getId());
    }

    @Test
    void rejectsFutureDriverReportedAt() {
        LocationReportCommand future = command(
                UUID.randomUUID(),
                LocationEventType.TASK_STARTED,
                OffsetDateTime.ofInstant(NOW.plusSeconds(1), ZoneOffset.UTC),
                INSIDE_LONGITUDE,
                "未来反馈",
                null,
                null);

        assertThatThrownBy(() -> recorder.append(future))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void persistsOutsideServiceAreaEventAndReturnsWarning() {
        LocationReportCommand outside = command(
                UUID.randomUUID(),
                LocationEventType.TASK_STARTED,
                REPORTED_AT,
                new BigDecimal("122.1000000"),
                "服务区外反馈",
                null,
                null);

        LocationReportResult result = recorder.append(outside);

        assertThat(result.event().isOutsideServiceArea()).isTrue();
        assertThat(result.warnings()).containsExactly(LocationWarning.OUTSIDE_SERVICE_AREA);
        assertThat(eventRepository.findById(result.event().getId())).isPresent();

        LocationReportResult replay = recorder.append(outside);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.warnings()).containsExactly(LocationWarning.OUTSIDE_SERVICE_AREA);
    }

    @Test
    void rejectsCorrectionWithoutReason() {
        LocationReportResult original = recorder.append(command(UUID.randomUUID()));
        LocationReportCommand correction = command(
                UUID.randomUUID(),
                LocationEventType.MANUAL_CORRECTION,
                REPORTED_AT.plusMinutes(1),
                INSIDE_LONGITUDE,
                "修正位置",
                " ",
                original.event().getId());

        assertThatThrownBy(() -> recorder.append(correction))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void rejectsCorrectionWhenOriginalEventDoesNotExist() {
        LocationReportCommand correction = command(
                UUID.randomUUID(),
                LocationEventType.MANUAL_CORRECTION,
                REPORTED_AT,
                INSIDE_LONGITUDE,
                "修正位置",
                "调度员确认原位置有误",
                UUID.randomUUID());

        assertThatThrownBy(() -> recorder.append(correction))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void rejectsCorrectionForAnotherVehicleEvent() {
        LocationReportResult original = recorder.append(command(UUID.randomUUID()));
        UUID anotherVehicleId = UUID.randomUUID();
        vehicleRepository.save(Vehicle.create(
                anotherVehicleId,
                "沪B" + anotherVehicleId.toString().substring(0, 5),
                "MINIBUS",
                8,
                "AVAILABLE",
                "POINT(121.4800 31.2400)",
                "浦东车队",
                true));
        LocationReportCommand correction = correctionCommand(
                anotherVehicleId, original.event().getId(), UUID.randomUUID());

        assertThatThrownBy(() -> recorder.append(correction))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void acceptsCorrectionForSameVehicleEvent() {
        LocationReportResult original = recorder.append(command(UUID.randomUUID()));

        LocationReportResult correction = recorder.append(correctionCommand(
                vehicleId, original.event().getId(), UUID.randomUUID()));

        assertThat(correction.event().getCorrectsEventId()).isEqualTo(original.event().getId());
        assertThat(correction.event().getVehicleId()).isEqualTo(vehicleId);
    }

    private LocationReportCommand command(UUID idempotencyKey) {
        return command(
                idempotencyKey,
                LocationEventType.TASK_STARTED,
                REPORTED_AT,
                INSIDE_LONGITUDE,
                "司机已到达",
                null,
                null);
    }

    private LocationReportCommand command(
            UUID idempotencyKey,
            LocationEventType eventType,
            OffsetDateTime reportedAt,
            BigDecimal longitude,
            String note,
            String correctionReason,
            UUID correctsEventId) {
        return new LocationReportCommand(
                vehicleId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                eventType,
                longitude,
                LATITUDE,
                "上海市浦东新区世纪大道 100 号",
                reportedAt,
                UUID.randomUUID(),
                note,
                correctionReason,
                correctsEventId,
                idempotencyKey);
    }

    private LocationReportCommand correctionCommand(
            UUID correctionVehicleId, UUID correctsEventId, UUID idempotencyKey) {
        LocationReportCommand command = command(
                idempotencyKey,
                LocationEventType.MANUAL_CORRECTION,
                REPORTED_AT.plusMinutes(1),
                INSIDE_LONGITUDE,
                "修正位置",
                "调度员确认原位置有误",
                correctsEventId);
        return new LocationReportCommand(
                correctionVehicleId,
                command.vehicleTaskId(),
                command.taskStopId(),
                command.virtualStopId(),
                command.eventType(),
                command.longitude(),
                command.latitude(),
                command.standardizedAddress(),
                command.driverReportedAt(),
                command.recordedBy(),
                command.note(),
                command.correctionReason(),
                command.correctsEventId(),
                command.idempotencyKey());
    }

    private static LocationReportCommand commandWithNote(LocationReportCommand command, String note) {
        return new LocationReportCommand(
                command.vehicleId(),
                command.vehicleTaskId(),
                command.taskStopId(),
                command.virtualStopId(),
                command.eventType(),
                command.longitude(),
                command.latitude(),
                command.standardizedAddress(),
                command.driverReportedAt(),
                command.recordedBy(),
                note,
                command.correctionReason(),
                command.correctsEventId(),
                command.idempotencyKey());
    }
}
