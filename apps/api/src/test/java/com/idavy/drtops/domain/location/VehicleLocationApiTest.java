package com.idavy.drtops.domain.location;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idavy.drtops.auth.JwtTokenService;
import com.idavy.drtops.auth.RoleCode;
import com.idavy.drtops.auth.UserAccount;
import com.idavy.drtops.auth.UserAccountRepository;
import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
import org.springframework.test.web.servlet.MockMvc;

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

    @Autowired MockMvc mockMvc;
    @Autowired VehicleRepository vehicleRepository;
    @Autowired VehicleLocationEventRepository eventRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired UserAccountRepository userAccountRepository;
    @Autowired JwtTokenService jwtTokenService;

    private String dispatcherToken;
    private String adminToken;
    private String operatorToken;

    @BeforeEach
    void setUp() {
        eventRepository.deleteAll();
        auditLogRepository.deleteAll();
        vehicleRepository.deleteAll();
        userAccountRepository.deleteAll();
        vehicleRepository.save(Vehicle.create(
                VEHICLE_ID, "沪A10001", "MINIBUS", 8, "AVAILABLE",
                "POINT(121.4700 31.2300)", "浦东车队", true));
        dispatcherToken = token("dispatcher01", RoleCode.DISPATCHER);
        adminToken = token("admin01", RoleCode.SYSTEM_ADMIN);
        operatorToken = token("operator01", RoleCode.OPERATOR);
    }

    @Test
    void dispatcherReportsAndReadsLocationsButCannotCorrectOrExport() throws Exception {
        UUID idempotencyKey = UUID.randomUUID();
        mockMvc.perform(post(reportPath())
                        .header(HttpHeaders.AUTHORIZATION, bearer(dispatcherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(report(idempotencyKey, "2026-07-13T09:00:00+08:00", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.event.longitude").value(121.4737))
                .andExpect(jsonPath("$.data.snapshotApplied").value(true));

        mockMvc.perform(get("/api/vehicles/locations/latest")
                        .header(HttpHeaders.AUTHORIZATION, bearer(dispatcherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].latestLocation.standardizedAddress").value("上海市浦东新区世纪大道 100 号"));
        mockMvc.perform(post(reportPath())
                        .header(HttpHeaders.AUTHORIZATION, bearer(dispatcherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(report(UUID.randomUUID(), "2026-07-13T09:01:00+08:00", UUID.randomUUID())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/vehicle-locations/export.csv")
                        .header(HttpHeaders.AUTHORIZATION, bearer(dispatcherToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void systemAdminCorrectsAndReplayDoesNotDuplicateSnapshotOrAudit() throws Exception {
        UUID originalKey = UUID.randomUUID();
        String original = mockMvc.perform(post(reportPath())
                        .header(HttpHeaders.AUTHORIZATION, bearer(dispatcherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(report(originalKey, "2026-07-13T09:00:00+08:00", null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String eventId = com.jayway.jsonpath.JsonPath.read(original, "$.data.event.id");
        long snapshotsBeforeReplay = eventRepository.count();
        long auditsBeforeReplay = auditLogRepository.count();

        mockMvc.perform(post(reportPath())
                        .header(HttpHeaders.AUTHORIZATION, bearer(dispatcherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(report(originalKey, "2026-07-13T09:00:00+08:00", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(true));
        org.assertj.core.api.Assertions.assertThat(eventRepository.count()).isEqualTo(snapshotsBeforeReplay);
        org.assertj.core.api.Assertions.assertThat(auditLogRepository.count()).isEqualTo(auditsBeforeReplay);

        mockMvc.perform(post(reportPath())
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(report(UUID.randomUUID(), "2026-07-13T09:02:00+08:00", UUID.fromString(eventId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.event.eventType").value("MANUAL_CORRECTION"));
    }

    @Test
    void exposesFilteredHistoryTaskChainShanghaiDayAndWarnings() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID firstKey = UUID.randomUUID();
        mockMvc.perform(post(reportPath())
                        .header(HttpHeaders.AUTHORIZATION, bearer(dispatcherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(report(firstKey, "2026-07-13T00:30:00+08:00", null, taskId, "TASK_STARTED", "121.4737")))
                .andExpect(status().isCreated());
        mockMvc.perform(post(reportPath())
                        .header(HttpHeaders.AUTHORIZATION, bearer(dispatcherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(report(UUID.randomUUID(), "2026-07-13T01:00:00+08:00", null, taskId, "PICKUP_ARRIVED", "123.0000")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.warnings[0]").value("OUTSIDE_SERVICE_AREA"));

        mockMvc.perform(get("/api/vehicles/" + VEHICLE_ID + "/location-events")
                        .header(HttpHeaders.AUTHORIZATION, bearer(dispatcherToken))
                        .param("from", "2026-07-13T00:00:00+08:00")
                        .param("to", "2026-07-14T00:00:00+08:00")
                        .param("taskId", taskId.toString())
                        .param("eventType", "TASK_STARTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].driverReportedAt").value("2026-07-12T16:30:00Z"));
        mockMvc.perform(get("/api/vehicle-tasks/" + taskId + "/location-events")
                        .header(HttpHeaders.AUTHORIZATION, bearer(dispatcherToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void exportsUtf8BomCsvAndWritesOneExportAudit() throws Exception {
        mockMvc.perform(post(reportPath())
                        .header(HttpHeaders.AUTHORIZATION, bearer(dispatcherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(report(UUID.randomUUID(), "2026-07-13T09:00:00+08:00", null)))
                .andExpect(status().isCreated());
        long auditsBeforeExport = auditLogRepository.count();

        mockMvc.perform(get("/api/vehicle-locations/export.csv")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .param("from", "2026-07-13T00:00:00+08:00")
                        .param("to", "2026-07-14T00:00:00+08:00"))
                .andExpect(status().isOk())
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentType())
                        .startsWith("text/csv"))
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsByteArray())
                        .startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF));
        org.assertj.core.api.Assertions.assertThat(auditLogRepository.count()).isEqualTo(auditsBeforeExport + 1);
        org.assertj.core.api.Assertions.assertThat(auditLogRepository.findAll()).anyMatch(log ->
                "VEHICLE_LOCATION_EXPORT".equals(log.getAction()) && log.getMetadataJson().contains("recordCount"));
    }

    @Test
    void deniesLocationEndpointsToUnprivilegedOperator() throws Exception {
        mockMvc.perform(post(reportPath())
                        .header(HttpHeaders.AUTHORIZATION, bearer(operatorToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(report(UUID.randomUUID(), "2026-07-13T09:00:00+08:00", null)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/vehicles/locations/latest")
                        .header(HttpHeaders.AUTHORIZATION, bearer(operatorToken)))
                .andExpect(status().isForbidden());
    }

    private String token(String username, RoleCode role) {
        UserAccount account = UserAccount.create(username, username, "not-used-in-location-test");
        account.assignRoles(Set.of(role));
        return jwtTokenService.issue(userAccountRepository.save(account)).value();
    }

    private String reportPath() { return "/api/vehicles/" + VEHICLE_ID + "/location-reports"; }
    private String bearer(String token) { return "Bearer " + token; }

    private String report(UUID idempotencyKey, String reportedAt, UUID correctsEventId) {
        return report(idempotencyKey, reportedAt, correctsEventId, null, "TASK_STARTED", "121.4737");
    }

    private String report(UUID idempotencyKey, String reportedAt, UUID correctsEventId, UUID taskId, String eventType, String longitude) {
        String correction = correctsEventId == null ? "" : "\"correctsEventId\":\"" + correctsEventId + "\",\"correctionReason\":\"调度确认原位置有误\",";
        String actualType = correctsEventId == null ? eventType : "MANUAL_CORRECTION";
        String task = taskId == null ? "" : "\"vehicleTaskId\":\"" + taskId + "\",";
        return "{" + task + correction
                + "\"eventType\":\"" + actualType + "\",\"longitude\":" + longitude
                + ",\"latitude\":31.2304,\"standardizedAddress\":\"上海市浦东新区世纪大道 100 号\","
                + "\"driverReportedAt\":\"" + reportedAt + "\",\"idempotencyKey\":\"" + idempotencyKey + "\"}";
    }

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
