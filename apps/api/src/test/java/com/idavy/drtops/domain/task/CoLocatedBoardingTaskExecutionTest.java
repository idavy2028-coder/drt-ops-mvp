package com.idavy.drtops.domain.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.idavy.drtops.domain.audit.AuditLog;
import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.domain.fleet.Driver;
import com.idavy.drtops.domain.fleet.DriverRepository;
import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import com.idavy.drtops.domain.location.IdempotencyKeyLock;
import com.idavy.drtops.domain.location.ServiceAreaLocationChecker;
import com.idavy.drtops.domain.location.VehicleLocationEventRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:co_located_boarding;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import(CoLocatedBoardingTaskExecutionTest.LocationTestConfiguration.class)
class CoLocatedBoardingTaskExecutionTest {

    private static final UUID VEHICLE_ID = UUID.fromString("33333333-3333-3333-3333-333333333371");
    private static final UUID DRIVER_ID = UUID.fromString("44444444-4444-4444-4444-444444444471");
    private static final UUID ACTOR_ID = UUID.fromString("77777777-7777-7777-7777-777777777771");
    private static final OffsetDateTime REPORTED_AT = OffsetDateTime.parse("2026-08-03T09:50:00+08:00");
    private static final BigDecimal LONGITUDE = new BigDecimal("105.2582240");
    private static final BigDecimal LATITUDE = new BigDecimal("35.1976360");

    @Autowired TaskExecutionService service;
    @Autowired VehicleTaskRepository vehicleTaskRepository;
    @Autowired VehicleRepository vehicleRepository;
    @Autowired DriverRepository driverRepository;
    @Autowired VehicleLocationEventRepository eventRepository;
    @Autowired AuditLogRepository auditLogRepository;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        eventRepository.deleteAll();
        vehicleTaskRepository.deleteAll();
        vehicleRepository.deleteAll();
        driverRepository.deleteAll();

        vehicleRepository.save(Vehicle.create(
                VEHICLE_ID,
                "甘J00856D",
                "MINIBUS",
                8,
                "IN_SERVICE",
                "POINT(105.2582240 35.1976360)",
                "同站上车测试车队",
                true));
        driverRepository.save(Driver.create(
                DRIVER_ID,
                "同站上车测试司机",
                "13900007001",
                "QUALIFIED",
                REPORTED_AT.minusHours(4),
                REPORTED_AT.plusHours(8),
                "BUSY",
                "同站上车测试车队"));
    }

    @Test
    void oneArrivalMarksAdjacentBoardingStopsAtSameVirtualStop() {
        UUID sharedStopId = UUID.randomUUID();
        VehicleTask task = inProgressTask();
        TaskStop firstBoarding = boarding(sharedStopId, 1);
        TaskStop secondBoarding = boarding(sharedStopId, 2);
        TaskStop alighting = alighting(UUID.randomUUID(), 3);
        task.addStop(firstBoarding);
        task.addStop(secondBoarding);
        task.addStop(alighting);
        task = vehicleTaskRepository.save(task);

        service.arrive(ACTOR_ID, task.getId(), firstBoarding.getId(), request(sharedStopId));

        List<TaskStop> stops = reload(task.getId()).getStops();
        assertThat(stops)
                .extracting(TaskStop::getStatus)
                .containsExactly("ARRIVED", "ARRIVED", "PLANNED");
        assertThat(stops.subList(0, 2))
                .extracting(TaskStop::getActualArrivalAt)
                .containsOnly(REPORTED_AT);

        UUID eventId = eventRepository.findAll().getFirst().getId();
        assertThat(auditLogRepository.findByEntityId(task.getId()))
                .extracting(AuditLog::getAction, AuditLog::getReason, AuditLog::getMetadataJson)
                .containsExactly(
                        tuple("TASK_STOP_ARRIVED", firstBoarding.getId().toString(), metadata(eventId)),
                        tuple("TASK_STOP_ARRIVED", secondBoarding.getId().toString(), metadata(eventId)));
    }

    @Test
    void arrivalDoesNotAdvanceBoardingStopAtDifferentVirtualStop() {
        UUID firstStopId = UUID.randomUUID();
        VehicleTask task = inProgressTask();
        TaskStop firstBoarding = boarding(firstStopId, 1);
        task.addStop(firstBoarding);
        task.addStop(boarding(UUID.randomUUID(), 2));
        task = vehicleTaskRepository.save(task);

        service.arrive(ACTOR_ID, task.getId(), firstBoarding.getId(), request(firstStopId));

        assertThat(reload(task.getId()).getStops())
                .extracting(TaskStop::getStatus)
                .containsExactly("ARRIVED", "PLANNED");
        assertThat(auditLogRepository.findByEntityId(task.getId())).hasSize(1);
    }

    @Test
    void arrivalDoesNotCrossNonBoardingStopToReachSameVirtualStop() {
        UUID sharedStopId = UUID.randomUUID();
        VehicleTask task = inProgressTask();
        TaskStop firstBoarding = boarding(sharedStopId, 1);
        task.addStop(firstBoarding);
        task.addStop(alighting(UUID.randomUUID(), 2));
        task.addStop(boarding(sharedStopId, 3));
        task = vehicleTaskRepository.save(task);

        service.arrive(ACTOR_ID, task.getId(), firstBoarding.getId(), request(sharedStopId));

        assertThat(reload(task.getId()).getStops())
                .extracting(TaskStop::getStatus)
                .containsExactly("ARRIVED", "PLANNED", "PLANNED");
        assertThat(auditLogRepository.findByEntityId(task.getId())).hasSize(1);
    }

    @Test
    void replayedSharedArrivalDoesNotDuplicateEventsOrAudits() {
        UUID sharedStopId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        VehicleTask task = inProgressTask();
        TaskStop firstBoarding = boarding(sharedStopId, 1);
        task.addStop(firstBoarding);
        task.addStop(boarding(sharedStopId, 2));
        task = vehicleTaskRepository.save(task);
        TaskLocationReportRequest request = request(sharedStopId, idempotencyKey);

        service.arrive(ACTOR_ID, task.getId(), firstBoarding.getId(), request);
        service.arrive(ACTOR_ID, task.getId(), firstBoarding.getId(), request);

        assertThat(eventRepository.findAll()).hasSize(1);
        assertThat(auditLogRepository.findByEntityId(task.getId()))
                .extracting(AuditLog::getAction)
                .containsExactly("TASK_STOP_ARRIVED", "TASK_STOP_ARRIVED");
        assertThat(reload(task.getId()).getStops())
                .extracting(TaskStop::getStatus)
                .containsExactly("ARRIVED", "ARRIVED");
    }

    @Test
    void sharedArrivalStillRequiresAndAuditsBoardingPerOrder() {
        UUID sharedStopId = UUID.randomUUID();
        VehicleTask task = inProgressTask();
        TaskStop firstBoarding = boarding(sharedStopId, 1);
        TaskStop secondBoarding = boarding(sharedStopId, 2);
        task.addStop(firstBoarding);
        task.addStop(secondBoarding);
        task = vehicleTaskRepository.save(task);

        service.arrive(ACTOR_ID, task.getId(), firstBoarding.getId(), request(sharedStopId));
        service.board(ACTOR_ID, task.getId(), firstBoarding.getId(), request(sharedStopId));
        service.board(ACTOR_ID, task.getId(), secondBoarding.getId(), request(sharedStopId));

        assertThat(reload(task.getId()).getStops())
                .extracting(TaskStop::getStatus)
                .containsExactly("BOARDED", "BOARDED");
        assertThat(auditLogRepository.findByEntityId(task.getId()))
                .filteredOn(log -> "PASSENGER_BOARDED".equals(log.getAction()))
                .extracting(AuditLog::getReason)
                .containsExactly(firstBoarding.getId().toString(), secondBoarding.getId().toString());
    }

    private VehicleTask reload(UUID taskId) {
        return vehicleTaskRepository.findWithStopsById(taskId).orElseThrow();
    }

    private static VehicleTask inProgressTask() {
        VehicleTask task = VehicleTask.pendingDeparture(
                VEHICLE_ID, DRIVER_ID, REPORTED_AT.minusMinutes(10), "REALTIME_INSERTION");
        task.startExecution();
        return task;
    }

    private static TaskStop boarding(UUID virtualStopId, int sequence) {
        return TaskStop.planned(virtualStopId, UUID.randomUUID(), sequence, "BOARDING", REPORTED_AT);
    }

    private static TaskStop alighting(UUID virtualStopId, int sequence) {
        return TaskStop.planned(virtualStopId, UUID.randomUUID(), sequence, "ALIGHTING", REPORTED_AT.plusMinutes(20));
    }

    private static TaskLocationReportRequest request(UUID virtualStopId) {
        return request(virtualStopId, UUID.randomUUID());
    }

    private static TaskLocationReportRequest request(UUID virtualStopId, UUID idempotencyKey) {
        return new TaskLocationReportRequest(
                LONGITUDE,
                LATITUDE,
                "高铁站",
                REPORTED_AT,
                virtualStopId,
                "同站上车验收",
                idempotencyKey);
    }

    private static String metadata(UUID eventId) {
        return "{\"locationEventId\":\"" + eventId + "\"}";
    }

    @TestConfiguration
    static class LocationTestConfiguration {

        @Bean
        @Primary
        IdempotencyKeyLock idempotencyKeyLock() {
            return idempotencyKey -> { };
        }

        @Bean
        @Primary
        ServiceAreaLocationChecker serviceAreaLocationChecker() {
            return (longitude, latitude) -> true;
        }
    }
}
