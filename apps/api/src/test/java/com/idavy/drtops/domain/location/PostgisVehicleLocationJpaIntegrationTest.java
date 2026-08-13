package com.idavy.drtops.domain.location;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@EnabledIf("integrationEnvironmentAvailable")
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.open-in-view=false"
})
@Transactional
class PostgisVehicleLocationJpaIntegrationTest {

    private static PostgreSQLContainer<?> postgres;

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

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GpsLocationIngressService ingressService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID eventId;
    private UUID recordedBy;
    private String originalVehicleLocation;

    @DynamicPropertySource
    static void postgisProperties(DynamicPropertyRegistry registry) {
        PostgreSQLContainer<?> container = postgres();
        container.start();
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", container::getUsername);
        registry.add("spring.datasource.password", container::getPassword);
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

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
        assertThat(reloadedEvent.getCoordinateTransformVersion()).isEqualTo("LEGACY_NONE");
        assertThat(reloadedEvent.getQualityStatus()).isEqualTo(LocationQualityStatus.GOOD);
        assertThat(reloadedEvent.getQualityReasons()).isEqualTo("[]");
        assertThat(reloadedVehicle.getCurrentLocationQualityStatus()).isEqualTo(LocationQualityStatus.GOOD);
        assertThat(reloadedVehicle.getCurrentLocationQualityReasons()).isEqualTo("[]");
    }

    @Test
    void persistsNewVehicleWithNonNullLocationQualityDefaultsThroughJpa() {
        UUID vehicleId = UUID.randomUUID();
        Vehicle vehicle = Vehicle.create(vehicleId, "POSTGIS-" + vehicleId.toString().substring(0, 8), "Microbus", 8,
                "IDLE", "POINT(121.4737 31.2304)", "PostGIS JPA test", true);

        vehicleRepository.saveAndFlush(vehicle);
        entityManager.clear();

        Vehicle reloaded = vehicleRepository.findById(vehicleId).orElseThrow();
        assertThat(reloaded.getCurrentLocationQualityStatus()).isEqualTo(LocationQualityStatus.GOOD);
        assertThat(reloaded.getCurrentLocationQualityReasons()).isEqualTo("[]");
    }

    @Test
    void atomicallyReplaysConcurrentGpsIngressWithOneReceiptAndOneEvent() throws Exception {
        UUID terminalId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        commitTerminalBinding(terminalId);
        CanonicalPositionIngress payload = new CanonicalPositionIngress(
                terminalId, VEHICLE_ID, "JT808_2019", 7,
                new BigDecimal("105.2384988"), new BigDecimal("35.2109657"), "WGS84",
                Instant.parse("2026-08-12T08:59:50Z"), Instant.parse("2026-08-12T09:00:00Z"),
                0L, 0L, new BigDecimal("50"), 90, 10, 8, "a".repeat(64));
        GatewayIngressEnvelope envelope = new GatewayIngressEnvelope(
                1, idempotencyKey, "POSITION", payload.gatewayReceivedAt(), objectMapper.writeValueAsString(payload));

        CountDownLatch start = new CountDownLatch(1);
        List<GpsLocationIngressService.Result> results;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<GpsLocationIngressService.Result> first = executor.submit(() -> ingestAfterStart(envelope, start));
            Future<GpsLocationIngressService.Result> second = executor.submit(() -> ingestAfterStart(envelope, start));
            start.countDown();
            results = List.of(first.get(), second.get());
        }

        assertThat(results).extracting(GpsLocationIngressService.Result::status)
                .containsExactlyInAnyOrder("ACCEPTED", "REPLAYED");
        assertThat(results).allSatisfy(result -> assertThat(result.reasonCodes()).contains("POSITION_INVALID"));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from jt_gateway_ingress_receipts where idempotency_key = ?", Integer.class,
                idempotencyKey)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from vehicle_location_events where idempotency_key = ?", Integer.class,
                idempotencyKey)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from jt_gateway_audit_events where terminal_id = ?", Integer.class,
                terminalId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select final_status from jt_gateway_ingress_receipts where idempotency_key = ?", String.class,
                idempotencyKey)).isEqualTo("ACCEPTED");
        assertThat(jdbcTemplate.queryForObject(
                "select jsonb_typeof(reason_codes) from jt_gateway_ingress_receipts where idempotency_key = ?", String.class,
                idempotencyKey)).isEqualTo("array");
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

    private GpsLocationIngressService.Result ingestAfterStart(
            GatewayIngressEnvelope envelope, CountDownLatch start) throws InterruptedException {
        start.await();
        return ingressService.ingest(List.of(envelope)).getFirst();
    }

    private void commitTerminalBinding(UUID terminalId) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.executeWithoutResult(status -> {
            jdbcTemplate.update("""
                    insert into jt_terminals (
                      id, terminal_phone, terminal_code, manufacturer_id, model, protocol_version,
                      source_coordinate_system, active_safety_modules, jt1078_enabled, status,
                      auth_token_hash, auth_token_version, created_at, updated_at
                    ) values (?, ?, ?, 'MFG', 'MODEL', 'JT808_2019', 'WGS84', '[]'::jsonb, false,
                      'PENDING', repeat('a', 64), 1, now(), now())
                    """, terminalId, "GPS" + terminalId.toString().substring(0, 8), "GPS-" + terminalId);
            jdbcTemplate.update("""
                    insert into jt_terminal_vehicle_bindings (
                      id, terminal_id, vehicle_id, valid_from, status, binding_reason, created_at, updated_at
                    ) values (?, ?, ?, now(), 'ACTIVE', 'GPS concurrency test', now(), now())
                    """, UUID.randomUUID(), terminalId, VEHICLE_ID);
        });
    }

    static boolean integrationEnvironmentAvailable() {
        return Boolean.getBoolean("drt.integration.postgis") && dockerIsAvailable();
    }

    private static boolean dockerIsAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static synchronized PostgreSQLContainer<?> postgres() {
        if (postgres == null) {
            postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.5")
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("drt_ops")
                    .withUsername("drt_ops")
                    .withPassword("drt_ops");
        }
        return postgres;
    }
}
