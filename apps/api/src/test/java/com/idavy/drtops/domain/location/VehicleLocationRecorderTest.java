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

        LocationReportResult result = recorder.append(command(UUID.randomUUID()));
        snapshotService.apply(result.event());

        assertThat(result.event().isSnapshotApplied()).isFalse();
        assertThat(result.warnings()).containsExactly(LocationWarning.HISTORICAL_EVENT_NOT_APPLIED_TO_SNAPSHOT);
        assertThat(vehicle.getCurrentLocationEventId()).isEqualTo(currentEventId);
        assertThat(eventRepository.findById(result.event().getId())).isPresent();
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
        recorder.append(command(idempotencyKey));

        LocationReportCommand changed = command(
                idempotencyKey,
                LocationEventType.TASK_STARTED,
                REPORTED_AT,
                INSIDE_LONGITUDE,
                "不同备注",
                null,
                null);

        assertThatThrownBy(() -> recorder.append(changed))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
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
}
