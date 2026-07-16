package com.idavy.drtops.domain.location;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@EnabledIfSystemProperty(named = "drt.integration.postgis", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=${drt.integration.postgis-url:jdbc:postgresql://127.0.0.1:15432/drt_ops}",
        "spring.datasource.username=drt_ops",
        "spring.datasource.password=drt_ops"
})
@Transactional
@WithMockUser(authorities = "LOCATION_REPORT")
class PostgisVehicleLocationAssociationIntegrationTest {

    private static final UUID VEHICLE_ID = UUID.fromString("33333333-3333-3333-3333-333333333331");
    private static final UUID OTHER_VEHICLE_ID = UUID.fromString("33333333-3333-3333-3333-333333333332");
    private static final UUID DRIVER_ID = UUID.fromString("44444444-4444-4444-4444-444444444441");
    private static final UUID VIRTUAL_STOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555551");
    private static final UUID OTHER_VIRTUAL_STOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555552");
    private static final UUID TASK_ID = UUID.fromString("77777777-7777-7777-7777-777777777731");
    private static final UUID OTHER_TASK_ID = UUID.fromString("77777777-7777-7777-7777-777777777732");
    private static final UUID TASK_STOP_ID = UUID.fromString("88888888-8888-8888-8888-888888888831");
    private static final UUID OTHER_TASK_STOP_ID = UUID.fromString("88888888-8888-8888-8888-888888888832");
    private static final UUID ACTOR_ID = UUID.fromString("99999999-9999-9999-9999-999999999931");

    @Autowired VehicleLocationCommandService commandService;
    @Autowired VehicleLocationEventRepository eventRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;

    @BeforeEach
    void setUpAssociations() {
        jdbcTemplate.update("""
                insert into user_accounts (
                  id, username, display_name, password_hash, enabled, must_change_password
                ) values (?, ?, '位置关联测试', 'not-used', true, false)
                """, ACTOR_ID, "location-association-" + UUID.randomUUID());
        insertTask(TASK_ID, VEHICLE_ID);
        insertTask(OTHER_TASK_ID, VEHICLE_ID);
        insertTaskStop(TASK_STOP_ID, TASK_ID, VIRTUAL_STOP_ID, 1);
        insertTaskStop(OTHER_TASK_STOP_ID, OTHER_TASK_ID, OTHER_VIRTUAL_STOP_ID, 1);
    }

    @Test
    void persistsValidTaskStopAndVirtualStopAssociation() {
        LocationReportResponse response = commandService.report(
                VEHICLE_ID, ACTOR_ID, request(TASK_ID, TASK_STOP_ID, VIRTUAL_STOP_ID));
        entityManager.flush();
        entityManager.clear();

        VehicleLocationEvent event = eventRepository.findById(response.event().id()).orElseThrow();
        assertThat(event.getVehicleTaskId()).isEqualTo(TASK_ID);
        assertThat(event.getTaskStopId()).isEqualTo(TASK_STOP_ID);
        assertThat(event.getVirtualStopId()).isEqualTo(VIRTUAL_STOP_ID);
    }

    @Test
    void rejectsTaskThatBelongsToAnotherVehicleBeforeForeignKeyHandling() {
        assertBadRequest(
                () -> commandService.report(
                        OTHER_VEHICLE_ID, ACTOR_ID, request(TASK_ID, TASK_STOP_ID, VIRTUAL_STOP_ID)),
                "车辆任务不属于当前车辆");
    }

    @Test
    void rejectsTaskStopThatBelongsToAnotherTaskEvenWhenAllForeignKeysExist() {
        assertBadRequest(
                () -> commandService.report(
                        VEHICLE_ID, ACTOR_ID, request(TASK_ID, OTHER_TASK_STOP_ID, OTHER_VIRTUAL_STOP_ID)),
                "任务节点不存在或不属于车辆任务");
    }

    @Test
    void rejectsVirtualStopThatConflictsWithTaskStopEvenWhenBothExist() {
        assertBadRequest(
                () -> commandService.report(
                        VEHICLE_ID, ACTOR_ID, request(TASK_ID, TASK_STOP_ID, OTHER_VIRTUAL_STOP_ID)),
                "虚拟站点与任务节点不一致");
    }

    private void insertTask(UUID taskId, UUID vehicleId) {
        jdbcTemplate.update("""
                insert into vehicle_tasks (
                  id, vehicle_id, driver_id, status, planned_start_at, source_type
                ) values (?, ?, ?, 'PENDING_DEPARTURE', ?, 'MANUAL')
                """, taskId, vehicleId, DRIVER_ID, OffsetDateTime.parse("2026-07-13T09:00:00+08:00"));
    }

    private void insertTaskStop(UUID stopId, UUID taskId, UUID virtualStopId, int sequence) {
        jdbcTemplate.update("""
                insert into task_stops (
                  id, vehicle_task_id, virtual_stop_id, sequence_number, stop_type, planned_arrival_at, status
                ) values (?, ?, ?, ?, 'BOARDING', ?, 'PLANNED')
                """, stopId, taskId, virtualStopId, sequence,
                OffsetDateTime.parse("2026-07-13T09:30:00+08:00"));
    }

    private LocationReportRequest request(UUID taskId, UUID taskStopId, UUID virtualStopId) {
        return new LocationReportRequest(
                taskId,
                taskStopId,
                virtualStopId,
                LocationEventType.TASK_STARTED,
                new BigDecimal("116.3120000"),
                new BigDecimal("39.9400000"),
                "北京市朝阳区望京北门",
                OffsetDateTime.parse("2026-07-13T10:00:00+08:00"),
                "真实 PostGIS 关联归属验证",
                null,
                null,
                UUID.randomUUID());
    }

    private void assertBadRequest(ThrowingCall call, String message) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getReason()).isEqualTo(message);
                });
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }
}
