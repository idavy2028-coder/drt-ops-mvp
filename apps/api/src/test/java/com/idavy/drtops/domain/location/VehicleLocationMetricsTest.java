package com.idavy.drtops.domain.location;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import com.idavy.drtops.domain.task.TaskStop;
import com.idavy.drtops.domain.task.VehicleTask;
import com.idavy.drtops.domain.task.VehicleTaskRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
class VehicleLocationMetricsTest {

    private static final Instant NOW = Instant.parse("2026-07-14T02:00:00Z");
    private static final OffsetDateTime REPORTED_AT = OffsetDateTime.parse("2026-07-14T09:20:00+08:00");
    private static final BigDecimal INSIDE_LONGITUDE = new BigDecimal("121.4737000");
    private static final BigDecimal OUTSIDE_LONGITUDE = new BigDecimal("122.1000000");
    private static final BigDecimal LATITUDE = new BigDecimal("31.2304000");

    @Autowired
    private VehicleLocationEventRepository eventRepository;

    @Autowired
    private VehicleRepository vehicleRepository;

    @Autowired
    private VehicleTaskRepository vehicleTaskRepository;

    @Autowired
    private EntityManager entityManager;

    private SimpleMeterRegistry registry;
    private VehicleLocationMetrics metrics;
    private VehicleLocationRecorder recorder;
    private VehicleLocationSnapshotService snapshotService;
    private VehicleLocationQueryService queryService;
    private UUID vehicleId;

    @BeforeEach
    void setUp() {
        vehicleId = UUID.randomUUID();
        vehicleRepository.save(Vehicle.create(
                vehicleId,
                "沪M" + vehicleId.toString().substring(0, 5),
                "MINIBUS",
                8,
                "AVAILABLE",
                "POINT(121.4700 31.2300)",
                "浦东车队",
                true));
        registry = new SimpleMeterRegistry();
        metrics = new VehicleLocationMetrics(registry, eventRepository, vehicleRepository, vehicleTaskRepository);
        ServiceAreaLocationChecker checker = (longitude, latitude) ->
                longitude.compareTo(new BigDecimal("122.0000000")) < 0;
        recorder = new VehicleLocationRecorder(
                eventRepository,
                vehicleRepository,
                idempotencyKey -> { },
                checker,
                Clock.fixed(NOW, ZoneOffset.UTC),
                metrics);
        snapshotService = new VehicleLocationSnapshotService(vehicleRepository);
        queryService = new VehicleLocationQueryService(eventRepository, vehicleRepository, metrics);
    }

    @Test
    void recordsReportDelayReplayOutsideCorrectionAndQueryMetrics() {
        LocationReportCommand normal = command(UUID.randomUUID(), LocationEventType.TASK_STARTED,
                REPORTED_AT, INSIDE_LONGITUDE, null, null);
        LocationReportResult normalResult = recorder.append(normal);
        snapshotService.apply(normalResult.event());
        recorder.append(normal);

        LocationReportCommand outside = command(UUID.randomUUID(), LocationEventType.PICKUP_ARRIVED,
                REPORTED_AT.plusSeconds(5), OUTSIDE_LONGITUDE, null, null);
        recorder.append(outside);

        LocationReportCommand correction = command(UUID.randomUUID(), LocationEventType.MANUAL_CORRECTION,
                REPORTED_AT.plusSeconds(10), INSIDE_LONGITUDE, "调度员核实原位置偏移", normal.idempotencyKey());
        UUID originalEventId = eventRepository.findByIdempotencyKey(normal.idempotencyKey()).orElseThrow().getId();
        recorder.append(new LocationReportCommand(
                correction.scope(), correction.vehicleId(), correction.vehicleTaskId(), correction.taskStopId(),
                correction.virtualStopId(), correction.eventType(), correction.longitude(), correction.latitude(),
                correction.standardizedAddress(), correction.driverReportedAt(), correction.recordedBy(),
                correction.note(), correction.correctionReason(), originalEventId, correction.idempotencyKey()));

        assertThatThrownBy(() -> recorder.append(command(UUID.randomUUID(), LocationEventType.TASK_STARTED,
                OffsetDateTime.ofInstant(NOW.plusSeconds(1), ZoneOffset.UTC), INSIDE_LONGITUDE, null, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        queryService.history(vehicleId, null, null, null, null, null);
        queryService.latest();
        entityManager.flush();

        assertThat(counter("drt.vehicle.location.report.total", "result", "success")).isEqualTo(3.0);
        assertThat(counter("drt.vehicle.location.report.total", "result", "replay")).isEqualTo(1.0);
        assertThat(counter("drt.vehicle.location.report.total", "result", "failure")).isEqualTo(1.0);
        assertThat(registry.find("drt.vehicle.location.report.total")
                .tag("source", "MANUAL_DISPATCHER")
                .counters()).hasSize(3);
        assertThat(timer("drt.vehicle.location.recording.delay").count()).isEqualTo(3);
        assertThat(timer("drt.vehicle.location.recording.delay").totalTime(TimeUnit.SECONDS)).isGreaterThan(0);
        assertThat(counter("drt.vehicle.location.outside_area.total")).isEqualTo(1.0);
        assertThat(counter("drt.vehicle.location.correction.total")).isEqualTo(1.0);
        assertThat(timer("drt.vehicle.location.query.duration").count()).isEqualTo(2);
        assertThat(gauge("drt.vehicle.location.stale.count")).isEqualTo(1.0);
    }

    @Test
    void updatesMissingTaskNodesFromTaskHistoryQuery() {
        VehicleTask task = VehicleTask.pendingDeparture(
                vehicleId,
                UUID.randomUUID(),
                REPORTED_AT.plusMinutes(1),
                "METRICS_TEST");
        task.addStop(TaskStop.planned(UUID.randomUUID(), UUID.randomUUID(), 1, "BOARDING", REPORTED_AT.plusMinutes(5)));
        task.addStop(TaskStop.planned(UUID.randomUUID(), UUID.randomUUID(), 2, "ALIGHTING", REPORTED_AT.plusMinutes(15)));
        vehicleTaskRepository.save(task);

        recorder.append(commandForTask(
                UUID.randomUUID(),
                task.getId(),
                null,
                null,
                LocationEventType.TASK_STARTED,
                REPORTED_AT,
                INSIDE_LONGITUDE));
        task.startExecution();
        vehicleTaskRepository.save(task);
        entityManager.flush();

        queryService.taskHistory(task.getId(), null, null, null);

        assertThat(gauge("drt.vehicle.location.missing_task_nodes")).isEqualTo(5.0);
    }

    private double counter(String name, String tagName, String tagValue) {
        return registry.find(name).tag(tagName, tagValue).counter().count();
    }

    private double counter(String name) {
        return registry.find(name).counter().count();
    }

    private io.micrometer.core.instrument.Timer timer(String name) {
        return registry.find(name).timer();
    }

    private double gauge(String name) {
        return registry.find(name).gauge().value();
    }

    private LocationReportCommand command(
            UUID idempotencyKey,
            LocationEventType eventType,
            OffsetDateTime reportedAt,
            BigDecimal longitude,
            String correctionReason,
            UUID correctsEventId) {
        return new LocationReportCommand(
                LocationReportScope.INDEPENDENT_REPORT,
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
                "指标测试位置",
                correctionReason,
                correctsEventId,
                idempotencyKey);
    }

    private LocationReportCommand commandForTask(
            UUID idempotencyKey,
            UUID taskId,
            UUID taskStopId,
            UUID virtualStopId,
            LocationEventType eventType,
            OffsetDateTime reportedAt,
            BigDecimal longitude) {
        return new LocationReportCommand(
                LocationReportScope.TASK_ACTION_START,
                vehicleId,
                taskId,
                taskStopId,
                virtualStopId,
                eventType,
                longitude,
                LATITUDE,
                "上海市浦东新区世纪大道 100 号",
                reportedAt,
                UUID.randomUUID(),
                "指标测试任务节点",
                null,
                null,
                idempotencyKey);
    }
}
