package com.idavy.drtops.domain.location;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.auth.JwtTokenService;
import com.idavy.drtops.auth.RoleCode;
import com.idavy.drtops.auth.UserAccount;
import com.idavy.drtops.auth.UserAccountRepository;
import com.idavy.drtops.domain.area.VirtualStop;
import com.idavy.drtops.domain.area.VirtualStopRepository;
import com.idavy.drtops.domain.audit.AuditLog;
import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import com.idavy.drtops.domain.task.TaskStop;
import com.idavy.drtops.domain.task.VehicleTask;
import com.idavy.drtops.domain.task.VehicleTaskRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:vehicle_location_api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "drt.auth.jwt-secret=vehicle-location-api-test-secret-1234567890"
})
@AutoConfigureMockMvc
@Import(VehicleLocationApiTest.LocationTestConfiguration.class)
class VehicleLocationApiTest {

    private static final UUID VEHICLE_ID = UUID.fromString("11111111-1111-1111-1111-111111111131");
    private static final UUID OTHER_VEHICLE_ID = UUID.fromString("11111111-1111-1111-1111-111111111132");
    private static final UUID SERVICE_AREA_ID = UUID.fromString("22222222-2222-2222-2222-222222222231");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired VehicleRepository vehicleRepository;
    @Autowired VehicleTaskRepository vehicleTaskRepository;
    @Autowired VirtualStopRepository virtualStopRepository;
    @Autowired VehicleLocationEventRepository eventRepository;
    @Autowired VehicleLocationCommandService commandService;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired UserAccountRepository userAccountRepository;
    @Autowired JwtTokenService jwtTokenService;

    private String dispatcherToken;
    private String adminToken;
    private String operatorToken;
    private UUID dispatcherId;

    @BeforeEach
    void setUp() {
        eventRepository.deleteAll();
        auditLogRepository.deleteAll();
        vehicleTaskRepository.deleteAll();
        virtualStopRepository.deleteAll();
        vehicleRepository.deleteAll();
        userAccountRepository.deleteAll();
        vehicleRepository.save(Vehicle.create(
                VEHICLE_ID, "沪A10001", "MINIBUS", 8, "AVAILABLE",
                "POINT(121.4700 31.2300)", "浦东车队", true));
        vehicleRepository.save(Vehicle.create(
                OTHER_VEHICLE_ID, "沪A10002", "MINIBUS", 8, "AVAILABLE",
                "POINT(121.4800 31.2400)", "浦东车队", true));
        UserAccount dispatcher = account("dispatcher01", RoleCode.DISPATCHER);
        dispatcherId = dispatcher.getId();
        dispatcherToken = jwtTokenService.issue(dispatcher).value();
        adminToken = token("admin01", RoleCode.SYSTEM_ADMIN);
        operatorToken = token("operator01", RoleCode.OPERATOR);
    }

    @Test
    void dispatcherReportsAndReadsLocationsButCannotCorrectOrExport() throws Exception {
        Map<String, Object> payload = reportPayload(UUID.randomUUID(), "2026-07-13T09:00:00+08:00");
        mockMvc.perform(post(reportPath()).header(HttpHeaders.AUTHORIZATION, bearer(dispatcherToken))
                        .contentType(MediaType.APPLICATION_JSON).content(json(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.event.longitude").value(121.4737))
                .andExpect(jsonPath("$.data.snapshotApplied").value(true));

        mockMvc.perform(get("/api/vehicles/locations/latest")
                        .header(HttpHeaders.AUTHORIZATION, bearer(dispatcherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].latestLocation.standardizedAddress")
                        .value("上海市浦东新区世纪大道 100 号"));

        payload = correctionPayload(UUID.randomUUID(), UUID.randomUUID());
        mockMvc.perform(post(reportPath()).header(HttpHeaders.AUTHORIZATION, bearer(dispatcherToken))
                        .contentType(MediaType.APPLICATION_JSON).content(json(payload)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/vehicle-locations/export.csv")
                        .header(HttpHeaders.AUTHORIZATION, bearer(dispatcherToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void replayKeepsSnapshotEventAndTimesAndDoesNotDuplicateAudit() throws Exception {
        UUID originalKey = UUID.randomUUID();
        Map<String, Object> originalPayload = reportPayload(originalKey, "2026-07-13T09:00:00+08:00");
        String original = mockMvc.perform(post(reportPath()).header(HttpHeaders.AUTHORIZATION, bearer(dispatcherToken))
                        .contentType(MediaType.APPLICATION_JSON).content(json(originalPayload)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID eventId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(original, "$.data.event.id"));
        OffsetDateTime recordedAt = eventRepository.findById(eventId).orElseThrow().getRecordedAt();
        Vehicle beforeReplay = vehicleRepository.findById(VEHICLE_ID).orElseThrow();
        UUID snapshotEventId = beforeReplay.getCurrentLocationEventId();
        OffsetDateTime snapshotReportedAt = beforeReplay.getCurrentLocationReportedAt();
        OffsetDateTime snapshotRecordedAt = beforeReplay.getCurrentLocationRecordedAt();
        long eventsBeforeReplay = eventRepository.count();
        long auditsBeforeReplay = auditLogRepository.count();

        String replay = mockMvc.perform(post(reportPath()).header(HttpHeaders.AUTHORIZATION, bearer(dispatcherToken))
                        .contentType(MediaType.APPLICATION_JSON).content(json(originalPayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(true))
                .andExpect(jsonPath("$.data.event.id").value(eventId.toString()))
                .andReturn().getResponse().getContentAsString();
        OffsetDateTime replayedRecordedAt = OffsetDateTime.parse(
                com.jayway.jsonpath.JsonPath.read(replay, "$.data.event.recordedAt"));

        Vehicle afterReplay = vehicleRepository.findById(VEHICLE_ID).orElseThrow();
        assertThat(eventRepository.count()).isEqualTo(eventsBeforeReplay);
        assertThat(auditLogRepository.count()).isEqualTo(auditsBeforeReplay);
        assertThat(afterReplay.getCurrentLocationEventId()).isEqualTo(snapshotEventId);
        assertThat(afterReplay.getCurrentLocationReportedAt()).isEqualTo(snapshotReportedAt);
        assertThat(afterReplay.getCurrentLocationRecordedAt()).isEqualTo(snapshotRecordedAt);
        assertThat(replayedRecordedAt.toInstant()).isEqualTo(recordedAt.toInstant());

        mockMvc.perform(post(reportPath()).header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(correctionPayload(UUID.randomUUID(), eventId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.event.eventType").value("MANUAL_CORRECTION"));
        assertThat(auditLogRepository.findAll()).anyMatch(log ->
                "VEHICLE_LOCATION_CORRECTED".equals(log.getAction()));
    }

    @Test
    void dateUsesShanghaiClosedOpenDayAndOldEventDoesNotReplaceSnapshot() throws Exception {
        report(dispatcherToken, reportPayload(UUID.randomUUID(), "2026-07-13T00:00:00+08:00"))
                .andExpect(status().isCreated());
        report(dispatcherToken, reportPayload(UUID.randomUUID(), "2026-07-13T23:59:59+08:00"))
                .andExpect(status().isCreated());
        report(dispatcherToken, reportPayload(UUID.randomUUID(), "2026-07-12T23:59:59+08:00"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.snapshotApplied").value(false))
                .andExpect(jsonPath("$.data.warnings[0]").value("HISTORICAL_EVENT_NOT_APPLIED_TO_SNAPSHOT"));
        report(dispatcherToken, reportPayload(UUID.randomUUID(), "2026-07-14T00:00:00+08:00"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/vehicles/" + VEHICLE_ID + "/location-events")
                        .header(HttpHeaders.AUTHORIZATION, bearer(dispatcherToken))
                        .param("date", "2026-07-13"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].driverReportedAt").value("2026-07-12T16:00:00Z"))
                .andExpect(jsonPath("$.data[1].driverReportedAt").value("2026-07-13T15:59:59Z"));
        assertThat(vehicleRepository.findById(VEHICLE_ID).orElseThrow().getCurrentLocationReportedAt())
                .isEqualTo(OffsetDateTime.parse("2026-07-14T00:00:00+08:00"));
    }

    @Test
    void validatesTaskStopAndVirtualStopAssociationsAndReturnsThemInView() throws Exception {
        TaskFixture task = createTask(VEHICLE_ID);
        Map<String, Object> valid = reportPayload(UUID.randomUUID(), "2026-07-13T10:00:00+08:00");
        valid.put("vehicleTaskId", task.taskId());
        valid.put("taskStopId", task.taskStopId());
        valid.put("virtualStopId", task.virtualStopId());
        report(dispatcherToken, valid)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.event.taskStopId").value(task.taskStopId().toString()))
                .andExpect(jsonPath("$.data.event.virtualStopId").value(task.virtualStopId().toString()));

        Map<String, Object> missingTask = reportPayload(UUID.randomUUID(), "2026-07-13T10:01:00+08:00");
        missingTask.put("vehicleTaskId", UUID.randomUUID());
        report(dispatcherToken, missingTask)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.message").value("车辆任务不存在"));

        TaskFixture otherVehicleTask = createTask(OTHER_VEHICLE_ID);
        Map<String, Object> wrongVehicle = reportPayload(UUID.randomUUID(), "2026-07-13T10:02:00+08:00");
        wrongVehicle.put("vehicleTaskId", otherVehicleTask.taskId());
        report(dispatcherToken, wrongVehicle)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.message").value("车辆任务不属于当前车辆"));

        TaskFixture otherTask = createTask(VEHICLE_ID);
        Map<String, Object> wrongTaskStop = reportPayload(UUID.randomUUID(), "2026-07-13T10:03:00+08:00");
        wrongTaskStop.put("vehicleTaskId", task.taskId());
        wrongTaskStop.put("taskStopId", otherTask.taskStopId());
        wrongTaskStop.put("virtualStopId", otherTask.virtualStopId());
        report(dispatcherToken, wrongTaskStop)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.message").value("任务节点不存在或不属于车辆任务"));

        Map<String, Object> missingVirtualStop = reportPayload(UUID.randomUUID(), "2026-07-13T10:04:00+08:00");
        missingVirtualStop.put("virtualStopId", UUID.randomUUID());
        report(dispatcherToken, missingVirtualStop)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.message").value("虚拟站点不存在"));

        Map<String, Object> conflictingVirtualStop = reportPayload(UUID.randomUUID(), "2026-07-13T10:05:00+08:00");
        conflictingVirtualStop.put("vehicleTaskId", task.taskId());
        conflictingVirtualStop.put("taskStopId", task.taskStopId());
        conflictingVirtualStop.put("virtualStopId", otherTask.virtualStopId());
        report(dispatcherToken, conflictingVirtualStop)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.message").value("虚拟站点与任务节点不一致"));
    }

    @Test
    void enforcesCorrectionFieldSemanticsAndAuditAction() throws Exception {
        String original = report(dispatcherToken, reportPayload(UUID.randomUUID(), "2026-07-13T11:00:00+08:00"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID originalId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(original, "$.data.event.id"));

        Map<String, Object> ordinaryWithReason = reportPayload(UUID.randomUUID(), "2026-07-13T11:01:00+08:00");
        ordinaryWithReason.put("correctionReason", "不应出现在普通事件");
        report(dispatcherToken, ordinaryWithReason)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.message").value("普通位置事件不能包含修正关联或原因"));

        Map<String, Object> ordinaryWithLink = reportPayload(UUID.randomUUID(), "2026-07-13T11:02:00+08:00");
        ordinaryWithLink.put("correctsEventId", originalId);
        report(adminToken, ordinaryWithLink)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.message").value("普通位置事件不能包含修正关联或原因"));

        Map<String, Object> correctionWithoutLink = reportPayload(UUID.randomUUID(), "2026-07-13T11:03:00+08:00");
        correctionWithoutLink.put("eventType", "MANUAL_CORRECTION");
        correctionWithoutLink.put("correctionReason", "缺少关联");
        report(adminToken, correctionWithoutLink)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.message").value("被修正的位置事件不能为空"));

        Map<String, Object> correctionWithoutReason = reportPayload(UUID.randomUUID(), "2026-07-13T11:04:00+08:00");
        correctionWithoutReason.put("eventType", "MANUAL_CORRECTION");
        correctionWithoutReason.put("correctsEventId", originalId);
        report(adminToken, correctionWithoutReason)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.message").value("修正原因不能为空"));
    }

    @Test
    void rejectsTextLongerThanDatabaseColumns() throws Exception {
        Map<String, Object> addressTooLong = reportPayload(UUID.randomUUID(), "2026-07-13T12:00:00+08:00");
        addressTooLong.put("standardizedAddress", "地".repeat(301));
        report(dispatcherToken, addressTooLong).andExpect(status().isBadRequest());

        Map<String, Object> noteTooLong = reportPayload(UUID.randomUUID(), "2026-07-13T12:01:00+08:00");
        noteTooLong.put("note", "备".repeat(501));
        report(dispatcherToken, noteTooLong).andExpect(status().isBadRequest());

        Map<String, Object> reasonTooLong = correctionPayload(UUID.randomUUID(), UUID.randomUUID());
        reasonTooLong.put("correctionReason", "修".repeat(501));
        report(adminToken, reasonTooLong).andExpect(status().isBadRequest());
    }

    @Test
    void exportsSafeUtf8BomCsvWithHeadersAndFilterAuditMetadata() throws Exception {
        String[] addresses = {"=cmd,\"quoted\"\r\nnext", "+cmd", "-cmd", "@cmd", "\tcmd", "\rcmd"};
        for (int index = 0; index < addresses.length; index++) {
            Map<String, Object> payload = reportPayload(
                    UUID.randomUUID(), "2026-07-13T0" + (index + 1) + ":00:00+08:00");
            payload.put("standardizedAddress", addresses[index]);
            report(dispatcherToken, payload).andExpect(status().isCreated());
        }
        long auditsBeforeExport = auditLogRepository.count();

        MvcResult result = mockMvc.perform(get("/api/vehicle-locations/export.csv")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .param("date", "2026-07-13")
                        .param("eventType", "TASK_STARTED"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=vehicle-locations.csv"))
                .andReturn();

        byte[] bytes = result.getResponse().getContentAsByteArray();
        assertThat(bytes).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        String csv = new String(bytes, StandardCharsets.UTF_8);
        assertThat(csv).contains("\"'=cmd,\"\"quoted\"\"\r\nnext\"");
        assertThat(csv).contains("\"'+cmd\"");
        assertThat(csv).contains("\"'-cmd\"");
        assertThat(csv).contains("\"'@cmd\"");
        assertThat(csv).contains("\"'\tcmd\"");
        assertThat(csv).contains("\"'\rcmd\"");
        assertThat(auditLogRepository.count()).isEqualTo(auditsBeforeExport + 1);
        AuditLog exportAudit = auditLogRepository.findAll().stream()
                .filter(log -> "VEHICLE_LOCATION_EXPORT".equals(log.getAction()))
                .findFirst().orElseThrow();
        JsonNode metadata = objectMapper.readTree(exportAudit.getMetadataJson());
        assertThat(metadata.path("date").asText()).isEqualTo("2026-07-13");
        assertThat(metadata.path("eventType").asText()).isEqualTo("TASK_STARTED");
        assertThat(metadata.path("recordCount").asInt()).isEqualTo(addresses.length);
    }

    @Test
    void deniesAllLocationEndpointsToUnprivilegedRole() throws Exception {
        Map<String, Object> payload = reportPayload(UUID.randomUUID(), "2026-07-13T13:00:00+08:00");
        mockMvc.perform(post(reportPath()).header(HttpHeaders.AUTHORIZATION, bearer(operatorToken))
                        .contentType(MediaType.APPLICATION_JSON).content(json(payload)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/vehicles/locations/latest").header(HttpHeaders.AUTHORIZATION, bearer(operatorToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/vehicles/" + VEHICLE_ID + "/location-events")
                        .header(HttpHeaders.AUTHORIZATION, bearer(operatorToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/vehicle-tasks/" + UUID.randomUUID() + "/location-events")
                        .header(HttpHeaders.AUTHORIZATION, bearer(operatorToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/vehicle-locations/export.csv").header(HttpHeaders.AUTHORIZATION, bearer(operatorToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void publicCommandServiceCannotBypassLocationPermission() {
        LocationReportRequest request = new LocationReportRequest(
                null, null, null, LocationEventType.TASK_STARTED,
                new BigDecimal("121.4737"), new BigDecimal("31.2304"),
                "上海市浦东新区世纪大道 100 号", OffsetDateTime.parse("2026-07-13T14:00:00+08:00"),
                null, null, null, UUID.randomUUID());

        assertThatThrownBy(() -> commandService.report(VEHICLE_ID, dispatcherId, request))
                .isInstanceOfAny(AccessDeniedException.class, AuthenticationCredentialsNotFoundException.class);
    }

    private org.springframework.test.web.servlet.ResultActions report(String token, Map<String, Object> payload)
            throws Exception {
        return mockMvc.perform(post(reportPath()).header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(json(payload)));
    }

    private TaskFixture createTask(UUID vehicleId) {
        UUID virtualStopId = UUID.randomUUID();
        virtualStopRepository.save(VirtualStop.create(
                virtualStopId, SERVICE_AREA_ID, "测试站点", "POINT(121.4737 31.2304)",
                100, true, true, "注意安全"));
        VehicleTask task = VehicleTask.pendingDeparture(
                vehicleId, UUID.randomUUID(), OffsetDateTime.parse("2026-07-13T09:00:00+08:00"), "MANUAL");
        TaskStop stop = TaskStop.planned(
                virtualStopId, null, 1, "BOARDING", OffsetDateTime.parse("2026-07-13T09:30:00+08:00"));
        task.addStop(stop);
        vehicleTaskRepository.saveAndFlush(task);
        return new TaskFixture(task.getId(), stop.getId(), virtualStopId);
    }

    private Map<String, Object> reportPayload(UUID idempotencyKey, String reportedAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", "TASK_STARTED");
        payload.put("longitude", new BigDecimal("121.4737"));
        payload.put("latitude", new BigDecimal("31.2304"));
        payload.put("standardizedAddress", "上海市浦东新区世纪大道 100 号");
        payload.put("driverReportedAt", reportedAt);
        payload.put("idempotencyKey", idempotencyKey);
        return payload;
    }

    private Map<String, Object> correctionPayload(UUID idempotencyKey, UUID correctsEventId) {
        Map<String, Object> payload = reportPayload(idempotencyKey, "2026-07-13T09:02:00+08:00");
        payload.put("eventType", "MANUAL_CORRECTION");
        payload.put("correctsEventId", correctsEventId);
        payload.put("correctionReason", "调度确认原位置有误");
        return payload;
    }

    private UserAccount account(String username, RoleCode role) {
        UserAccount account = UserAccount.create(username, username, "not-used-in-location-test");
        account.assignRoles(Set.of(role));
        return userAccountRepository.save(account);
    }

    private String token(String username, RoleCode role) {
        return jwtTokenService.issue(account(username, role)).value();
    }

    private String json(Map<String, Object> payload) throws Exception {
        return objectMapper.writeValueAsString(payload);
    }

    private String reportPath() { return "/api/vehicles/" + VEHICLE_ID + "/location-reports"; }
    private String bearer(String token) { return "Bearer " + token; }

    private record TaskFixture(UUID taskId, UUID taskStopId, UUID virtualStopId) { }

    @TestConfiguration
    static class LocationTestConfiguration {
        @Bean @Primary
        ServiceAreaLocationChecker serviceAreaLocationChecker() {
            return (longitude, latitude) -> longitude.compareTo(new BigDecimal("122.0000")) < 0;
        }

        @Bean @Primary
        IdempotencyKeyLock idempotencyKeyLock() {
            return idempotencyKey -> { };
        }
    }
}
