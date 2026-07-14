package com.idavy.drtops.domain.location;

import static org.assertj.core.api.Assertions.assertThat;

import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

@DataJpaTest(showSql = false, properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class VehicleLocationEventRepositoryTest {

    private static final String REQUEST_FINGERPRINT = "a".repeat(64);

    @Autowired
    VehicleLocationEventRepository repository;

    @Autowired
    VehicleRepository vehicleRepository;

    @Autowired
    TestEntityManager entityManager;

    @Test
    void findsEventsByIdempotencyVehicleTaskAndReportedTime() {
        UUID vehicleId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        OffsetDateTime firstReportedAt = OffsetDateTime.parse("2026-07-14T09:00:00+08:00");
        OffsetDateTime secondReportedAt = firstReportedAt.plusMinutes(5);

        VehicleLocationEvent first = locationEvent(vehicleId, taskId, idempotencyKey, firstReportedAt);
        VehicleLocationEvent second = locationEvent(vehicleId, taskId, UUID.randomUUID(), secondReportedAt);
        repository.save(first);
        repository.save(second);
        entityManager.flush();
        entityManager.clear();

        assertThat(repository.findByIdempotencyKey(idempotencyKey))
                .hasValueSatisfying(event -> {
                    assertThat(event.getId()).isEqualTo(first.getId());
                    assertThat(event.getRequestFingerprint()).isEqualTo(REQUEST_FINGERPRINT);
                });
        assertThat(repository.findByVehicleIdOrderByDriverReportedAtDesc(vehicleId))
                .extracting(VehicleLocationEvent::getId)
                .containsExactly(second.getId(), first.getId());
        assertThat(repository.findByVehicleTaskIdAndDriverReportedAtBetweenOrderByDriverReportedAtAsc(
                taskId, firstReportedAt, secondReportedAt))
                .extracting(VehicleLocationEvent::getId)
                .containsExactly(first.getId(), second.getId());
    }

    @Test
    void appliesSnapshotOnlyWhenReportedAtIsNotEarlierThanCurrentSnapshot() {
        UUID vehicleId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        OffsetDateTime reportedAt = OffsetDateTime.parse("2026-07-14T09:00:00+08:00");
        Vehicle vehicle = Vehicle.create(
                vehicleId, "沪A12345", "MINIBUS", 8, "AVAILABLE", "POINT(121.4737 31.2304)", "浦东车队", true);
        vehicleRepository.save(vehicle);
        entityManager.flush();
        entityManager.clear();

        vehicle = vehicleRepository.findByIdForLocationUpdate(vehicleId).orElseThrow();

        boolean applied = vehicle.applyLocationSnapshot(
                "POINT(121.4740 31.2307)",
                "上海市浦东新区世纪大道 100 号",
                LocationSource.MANUAL_DISPATCHER,
                "GCJ02",
                reportedAt,
                reportedAt.plusSeconds(5),
                eventId,
                taskId);
        boolean olderSnapshotApplied = vehicle.applyLocationSnapshot(
                "POINT(121.4750 31.2310)",
                "上海市浦东新区世纪大道 200 号",
                LocationSource.GPS_DEVICE,
                "GCJ02",
                reportedAt.minusSeconds(1),
                reportedAt.plusSeconds(6),
                UUID.randomUUID(),
                UUID.randomUUID());

        assertThat(applied).isTrue();
        assertThat(olderSnapshotApplied).isFalse();
        assertThat(GeographyPoint.fromWkt(vehicle.getCurrentLocation()).getX()).isEqualTo(121.4740);
        assertThat(GeographyPoint.fromWkt(vehicle.getCurrentLocation()).getY()).isEqualTo(31.2307);
        assertThat(vehicle.getCurrentLocationAddress()).isEqualTo("上海市浦东新区世纪大道 100 号");
        assertThat(vehicle.getCurrentLocationSource()).isEqualTo(LocationSource.MANUAL_DISPATCHER);
        assertThat(vehicle.getCurrentLocationReportedAt()).isEqualTo(reportedAt);
        assertThat(vehicle.getCurrentLocationEventId()).isEqualTo(eventId);
        assertThat(vehicle.getCurrentLocationTaskId()).isEqualTo(taskId);
    }

    private static VehicleLocationEvent locationEvent(
            UUID vehicleId, UUID taskId, UUID idempotencyKey, OffsetDateTime driverReportedAt) {
        return VehicleLocationEvent.record(
                vehicleId,
                taskId,
                null,
                null,
                LocationEventType.TASK_STARTED,
                LocationSource.MANUAL_DISPATCHER,
                "POINT(121.4737 31.2304)",
                new BigDecimal("121.4737000"),
                new BigDecimal("31.2304000"),
                "GCJ02",
                "上海市浦东新区世纪大道 100 号",
                driverReportedAt,
                driverReportedAt.plusSeconds(2),
                UUID.randomUUID(),
                "司机已到达",
                null,
                null,
                idempotencyKey,
                REQUEST_FINGERPRINT,
                true,
                false);
    }
}
