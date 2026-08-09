package com.idavy.drtops.domain.location;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfSystemProperty(named = "drt.integration.capacity", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=${drt.integration.postgis-url:jdbc:postgresql://127.0.0.1:15434/drt_ops_capacity}",
        "spring.datasource.username=drt_ops",
        "spring.datasource.password=drt_ops",
        "spring.jpa.open-in-view=false"
})
class VehicleLocationCapacityIntegrationTest {

    private static final int VEHICLE_COUNT = 4;
    private static final int EVENTS_PER_VEHICLE = 2_500;
    private static final int EXPECTED_EVENT_COUNT = VEHICLE_COUNT * EVENTS_PER_VEHICLE;
    private static final Duration INSERT_LIMIT = Duration.ofMinutes(10);
    private static final Duration HISTORY_LIMIT = Duration.ofSeconds(3);
    private static final Duration EXPORT_LIMIT = Duration.ofSeconds(10);
    private static final Duration SNAPSHOT_LIMIT = Duration.ofSeconds(1);
    private static final UUID ACTOR_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final List<UUID> VEHICLE_IDS = List.of(
            UUID.fromString("33333333-3333-3333-3333-333333333331"),
            UUID.fromString("33333333-3333-3333-3333-333333333332"),
            UUID.fromString("33333333-3333-3333-3333-333333333333"),
            UUID.fromString("33333333-3333-3333-3333-333333333334"));

    @Autowired
    private VehicleLocationRecorder recorder;

    @Autowired
    private VehicleLocationSnapshotService snapshotService;

    @Autowired
    private VehicleLocationQueryService queryService;

    @Autowired
    private VehicleLocationExportService exportService;

    @Autowired
    private VehicleLocationEventRepository eventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactions;

    @BeforeEach
    void prepareIsolatedCapacityFixtures() {
        transactions = new TransactionTemplate(transactionManager);
        assertThat(eventRepository.count()).isZero();
        jdbcTemplate.update("""
                insert into user_accounts (
                  id, username, display_name, password_hash, enabled, must_change_password
                ) values (?, 'p4-capacity-actor', 'P4 capacity actor', 'not-used', true, false)
                on conflict (id) do nothing
                """, ACTOR_ID);
        for (int index = 2; index < VEHICLE_COUNT; index++) {
            jdbcTemplate.update("""
                    insert into vehicles (
                      id, plate_number, vehicle_type, capacity, current_status, current_location,
                      fleet_name, dispatchable
                    ) values (?, ?, 'Capacity test vehicle', 12, 'IDLE',
                      ST_GeogFromText('SRID=4326;POINT(116.3180000 39.9290000)'),
                      'P4 isolated capacity fleet', true)
                    on conflict (id) do nothing
                    """, VEHICLE_IDS.get(index), "P4-CAP-" + (index + 1));
        }
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                ACTOR_ID,
                null,
                "LOCATION_READ",
                "LOCATION_EXPORT"));
    }

    @Test
    void acceptsPilotLocationVolumeWithinOperationalThresholds() {
        OffsetDateTime baseReportedAt = OffsetDateTime.now().minusHours(2);
        List<UUID> latestEventIds = new ArrayList<>(VEHICLE_COUNT);

        Duration insertDuration = measure(() -> {
            for (int vehicleIndex = 0; vehicleIndex < VEHICLE_COUNT; vehicleIndex++) {
                UUID vehicleId = VEHICLE_IDS.get(vehicleIndex);
                int capacityVehicleIndex = vehicleIndex;
                UUID[] latestEventId = new UUID[1];
                for (int batchStart = 0; batchStart < EVENTS_PER_VEHICLE; batchStart += 100) {
                    int start = batchStart;
                    transactions.executeWithoutResult(status -> {
                        for (int eventIndex = start;
                                eventIndex < Math.min(start + 100, EVENTS_PER_VEHICLE);
                                eventIndex++) {
                            LocationReportResult result = recorder.append(command(
                                    vehicleId, capacityVehicleIndex, eventIndex, baseReportedAt.plusSeconds(eventIndex)));
                            snapshotService.apply(result.event());
                            latestEventId[0] = result.event().getId();
                        }
                    });
                }
                latestEventIds.add(latestEventId[0]);
            }
        });

        Duration historyDuration = measure(() -> {
            List<VehicleLocationView> history =
                    queryService.history(VEHICLE_IDS.getFirst(), null, null, null, null, null);
            assertThat(history).hasSize(EVENTS_PER_VEHICLE);
        });

        byte[][] csv = new byte[1][];
        Duration exportDuration = measure(() -> csv[0] =
                exportService.export(ACTOR_ID, null, null, null, null, null));

        Duration snapshotDuration = measure(() -> {
            List<VehicleLocationQueryService.VehicleLocationSnapshotItem> snapshots = queryService.latest().stream()
                    .filter(item -> VEHICLE_IDS.contains(item.vehicleId()))
                    .toList();
            assertThat(snapshots).hasSize(VEHICLE_COUNT);
        });

        assertThat(eventRepository.count()).isEqualTo(EXPECTED_EVENT_COUNT);
        for (int index = 0; index < VEHICLE_COUNT; index++) {
            UUID vehicleId = VEHICLE_IDS.get(index);
            assertThat(eventRepository.findByVehicleIdOrderByDriverReportedAtDesc(vehicleId))
                    .hasSize(EVENTS_PER_VEHICLE);
            assertThat(jdbcTemplate.queryForObject(
                    "select current_location_event_id from vehicles where id = ?",
                    UUID.class,
                    vehicleId))
                    .isEqualTo(latestEventIds.get(index));
        }
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from (
                  select idempotency_key from vehicle_location_events
                  group by idempotency_key having count(*) > 1
                ) duplicates
                """, Integer.class))
                .isZero();
        assertThat(new String(csv[0], StandardCharsets.UTF_8).lines().count())
                .isEqualTo(EXPECTED_EVENT_COUNT + 1L);

        assertThat(insertDuration).isLessThanOrEqualTo(INSERT_LIMIT);
        assertThat(historyDuration).isLessThanOrEqualTo(HISTORY_LIMIT);
        assertThat(exportDuration).isLessThanOrEqualTo(EXPORT_LIMIT);
        assertThat(snapshotDuration).isLessThanOrEqualTo(SNAPSHOT_LIMIT);
        assertThatCode(() -> jdbcTemplate.queryForObject(
                "select ST_AsText(current_location::geometry) from vehicles where id = ?",
                String.class,
                VEHICLE_IDS.getFirst())).doesNotThrowAnyException();

        System.out.printf(
                "P4_CAPACITY_RESULT events=%d vehicles=%d perVehicle=%d insertMs=%d historyMs=%d exportMs=%d snapshotMs=%d%n",
                EXPECTED_EVENT_COUNT,
                VEHICLE_COUNT,
                EVENTS_PER_VEHICLE,
                insertDuration.toMillis(),
                historyDuration.toMillis(),
                exportDuration.toMillis(),
                snapshotDuration.toMillis());
    }

    private static LocationReportCommand command(
            UUID vehicleId, int vehicleIndex, int eventIndex, OffsetDateTime reportedAt) {
        String keyMaterial = "p4-capacity-" + vehicleIndex + '-' + eventIndex;
        return new LocationReportCommand(
                LocationReportScope.INDEPENDENT_REPORT,
                vehicleId,
                null,
                null,
                null,
                LocationEventType.TASK_STARTED,
                new BigDecimal("116.3180000").add(BigDecimal.valueOf(vehicleIndex, 5)),
                new BigDecimal("39.9290000").add(BigDecimal.valueOf(eventIndex % 100, 7)),
                "P4 isolated capacity point",
                reportedAt,
                ACTOR_ID,
                "P4 capacity validation",
                null,
                null,
                UUID.nameUUIDFromBytes(keyMaterial.getBytes(StandardCharsets.UTF_8)));
    }

    private static Duration measure(Runnable action) {
        long startedAt = System.nanoTime();
        action.run();
        return Duration.ofNanos(System.nanoTime() - startedAt);
    }
}
