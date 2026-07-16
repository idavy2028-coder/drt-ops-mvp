package com.idavy.drtops.domain.location;

import static org.assertj.core.api.Assertions.assertThat;

import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.annotation.Transactional;

@EnabledIfSystemProperty(named = "drt.integration.postgis", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=${drt.integration.postgis-url:jdbc:postgresql://127.0.0.1:15432/drt_ops}",
        "spring.datasource.username=drt_ops",
        "spring.datasource.password=drt_ops"
})
@Transactional
class PostgisVehicleLocationJpaIntegrationTest {

    private static final UUID VEHICLE_ID = UUID.fromString("33333333-3333-3333-3333-333333333331");
    private static final String EVENT_LOCATION = "POINT(121.4737 31.2304)";
    private static final String SNAPSHOT_LOCATION = "POINT(121.4740 31.2307)";

    @Autowired
    private VehicleLocationEventRepository eventRepository;

    @Autowired
    private VehicleRepository vehicleRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID eventId;
    private UUID recordedBy;
    private String originalVehicleLocation;

    @Test
    void persistsAndReadsEventAndVehicleSnapshotThroughJpa() {
        recordedBy = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into user_accounts (
                  id, username, display_name, password_hash, enabled, must_change_password
                ) values (?, ?, 'PostGIS JPA test', 'not-used', true, false)
                """, recordedBy, "postgis-jpa-" + recordedBy);
        originalVehicleLocation = readVehicleLocationFromDatabase();

        OffsetDateTime reportedAt = OffsetDateTime.parse("2026-07-14T09:00:00+08:00");
        VehicleLocationEvent event = VehicleLocationEvent.record(
                VEHICLE_ID,
                null,
                null,
                null,
                LocationEventType.TASK_STARTED,
                LocationSource.MANUAL_DISPATCHER,
                EVENT_LOCATION,
                new BigDecimal("121.4737000"),
                new BigDecimal("31.2304000"),
                "GCJ02",
                "上海市浦东新区世纪大道 100 号",
                reportedAt,
                reportedAt.plusSeconds(2),
                recordedBy,
                "真实 PostGIS JPA 映射验证",
                null,
                null,
                UUID.randomUUID(),
                "a".repeat(64),
                true,
                false);
        eventId = event.getId();

        eventRepository.saveAndFlush(event);

        Vehicle vehicle = vehicleRepository.findByIdForLocationUpdate(VEHICLE_ID).orElseThrow();
        assertThat(vehicle.applyLocationSnapshot(
                SNAPSHOT_LOCATION,
                "上海市浦东新区世纪大道 101 号",
                LocationSource.MANUAL_DISPATCHER,
                "GCJ02",
                reportedAt,
                reportedAt.plusSeconds(2),
                eventId,
                null)).isTrue();
        vehicleRepository.flush();
        entityManager.clear();

        VehicleLocationEvent reloadedEvent = eventRepository.findById(eventId).orElseThrow();
        Vehicle reloadedVehicle = vehicleRepository.findById(VEHICLE_ID).orElseThrow();
        assertThat(GeographyPoint.fromWkt(reloadedEvent.getLocation()).getX()).isEqualTo(121.4737);
        assertThat(GeographyPoint.fromWkt(reloadedEvent.getLocation()).getY()).isEqualTo(31.2304);
        assertThat(GeographyPoint.fromWkt(reloadedVehicle.getCurrentLocation()).getX()).isEqualTo(121.4740);
        assertThat(GeographyPoint.fromWkt(reloadedVehicle.getCurrentLocation()).getY()).isEqualTo(31.2307);
        assertThat(reloadedVehicle.getCurrentLocationEventId()).isEqualTo(eventId);
    }

    @AfterTransaction
    void rollsBackPostgisWrites() {
        if (eventId == null) {
            return;
        }
        assertThat(eventRepository.findById(eventId)).isEmpty();
        assertThat(readVehicleLocationFromDatabase()).isEqualTo(originalVehicleLocation);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from user_accounts where id = ?", Integer.class, recordedBy)).isZero();
    }

    private String readVehicleLocationFromDatabase() {
        return jdbcTemplate.queryForObject(
                "select ST_AsText(current_location::geometry) from vehicles where id = ?",
                String.class,
                VEHICLE_ID);
    }
}
