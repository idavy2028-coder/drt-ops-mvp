package com.idavy.drtops.domain.alarm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idavy.drtops.auth.Permission;
import com.idavy.drtops.auth.RoleCode;
import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:vehicle_alarm_api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class VehicleAlarmApiTest {

    private static final UUID ACTOR_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VEHICLE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    VehicleAlarmRepository alarms;

    @Autowired
    VehicleAlarmActionRepository actions;

    @Autowired
    VehicleAlarmOutboxRepository outbox;

    @Autowired
    VehicleRepository vehicles;

    @BeforeEach
    void reset() {
        actions.deleteAll();
        outbox.deleteAll();
        alarms.deleteAll();
        vehicles.deleteAll();
        vehicles.saveAndFlush(Vehicle.create(VEHICLE_ID, "甘G·A1001", "小型客车", 8,
                "IN_SERVICE", "POINT(118 32)", "通渭试点车队", true));
        alarms.saveAndFlush(alarm("ADAS", "FORWARD_COLLISION", 1,
                Instant.parse("2026-08-14T10:00:00Z")));
        alarms.saveAndFlush(alarm("DMS", "FATIGUE", 2,
                Instant.parse("2026-08-14T09:00:00Z")));
    }

    @Test
    void listsFilteredAlarmReadModelsWithOnlyPublicIdentityAndSafeFields() throws Exception {
        mockMvc.perform(get("/api/vehicle-alarms?module=ADAS")
                        .with(user(ACTOR_ID.toString()).authorities(authorities(RoleCode.DISPATCHER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].publicId").isString())
                .andExpect(jsonPath("$.data[0].module").value("ADAS"))
                .andExpect(jsonPath("$.data[0]", not(hasKey("id"))))
                .andExpect(jsonPath("$.data[0]", not(hasKey("terminalId"))))
                .andExpect(jsonPath("$.data[0]", not(hasKey("terminalAlarmIdentifier"))))
                .andExpect(jsonPath("$.data[0]", not(hasKey("payloadDigest"))))
                .andExpect(jsonPath("$.data[0]", not(hasKey("deduplicationKey"))));
    }

    @Test
    void exposesOnlyTheApprovedAlarmDisplayFieldsForTheAuthorizedDispatchConsole() throws Exception {
        mockMvc.perform(get("/api/vehicle-alarms?module=ADAS")
                        .with(user(ACTOR_ID.toString()).authorities(authorities(RoleCode.DISPATCHER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].plateNumber").value("甘G·A1001"))
                .andExpect(jsonPath("$.data[0].longitude").value(118.0))
                .andExpect(jsonPath("$.data[0].latitude").value(32.0))
                .andExpect(jsonPath("$.data[0].speedKph").value(60.0))
                .andExpect(jsonPath("$.data[0]", not(hasKey("id"))))
                .andExpect(jsonPath("$.data[0]", not(hasKey("terminalId"))))
                .andExpect(jsonPath("$.data[0]", not(hasKey("payloadDigest"))))
                .andExpect(jsonPath("$.data[0]", not(hasKey("terminalAlarmIdentifier"))));
    }

    @Test
    void doesNotTreatTakeOverOfATerminalAlarmAsTheAdministratorOnlyReopenAction() throws Exception {
        UUID publicId = publicIdFor("ADAS");
        long acknowledgedVersion = action(publicId, "ACKNOWLEDGE", 0, RoleCode.DISPATCHER, true);
        long resolvedVersion = action(publicId, "RESOLVE", acknowledgedVersion, RoleCode.DISPATCHER, true);

        mockMvc.perform(post("/api/vehicle-alarms/{publicId}/actions", publicId)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(actionBody("TAKE_OVER", resolvedVersion, true))
                        .with(user(ACTOR_ID.toString()).authorities(authorities(RoleCode.SYSTEM_ADMIN))))
                .andExpect(status().isConflict());
    }

    @Test
    void appliesConfirmedActionsAndMapsExpectedClientFailures() throws Exception {
        UUID publicId = publicIdFor("ADAS");
        long acknowledgedVersion = action(publicId, "ACKNOWLEDGE", 0, RoleCode.DISPATCHER, true);

        mockMvc.perform(post("/api/vehicle-alarms/{publicId}/actions", publicId)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(actionBody("TAKE_OVER", acknowledgedVersion, false))
                        .with(user(ACTOR_ID.toString()).authorities(authorities(RoleCode.DISPATCHER))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/vehicle-alarms/{publicId}/actions", UUID.randomUUID())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(actionBody("ACKNOWLEDGE", 0, true))
                        .with(user(ACTOR_ID.toString()).authorities(authorities(RoleCode.DISPATCHER))))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/vehicle-alarms/{publicId}/actions", publicId)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(actionBody("TAKE_OVER", 0, true))
                        .with(user(ACTOR_ID.toString()).authorities(authorities(RoleCode.DISPATCHER))))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/vehicle-alarms/{publicId}/actions", publicId)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(actionBody("TAKE_OVER", acknowledgedVersion, true))
                        .with(user(ACTOR_ID.toString()).authorities(authorities(RoleCode.OPERATOR))))
                .andExpect(status().isForbidden());
    }

    @Test
    void grantsAllFourAlarmPermissionsOnlyToAdministratorsAndDispatchers() {
        Set<Permission> administrator = Permission.permissionsFor(Set.of(RoleCode.SYSTEM_ADMIN));
        Set<Permission> dispatcher = Permission.permissionsFor(Set.of(RoleCode.DISPATCHER));
        Set<Permission> operator = Permission.permissionsFor(Set.of(RoleCode.OPERATOR));
        Set<Permission> auditor = Permission.permissionsFor(Set.of(RoleCode.AUDITOR));

        assertThat(administrator).contains(Permission.VEHICLE_ALARM_READ, Permission.VEHICLE_ALARM_HANDLE,
                Permission.VEHICLE_ALARM_ATTACHMENT_REQUEST, Permission.VEHICLE_ALARM_ATTACHMENT_READ);
        assertThat(dispatcher).contains(Permission.VEHICLE_ALARM_READ, Permission.VEHICLE_ALARM_HANDLE,
                Permission.VEHICLE_ALARM_ATTACHMENT_REQUEST, Permission.VEHICLE_ALARM_ATTACHMENT_READ);
        assertThat(operator).contains(Permission.VEHICLE_ALARM_READ)
                .doesNotContain(Permission.VEHICLE_ALARM_HANDLE, Permission.VEHICLE_ALARM_ATTACHMENT_REQUEST,
                        Permission.VEHICLE_ALARM_ATTACHMENT_READ);
        assertThat(auditor).contains(Permission.VEHICLE_ALARM_READ)
                .doesNotContain(Permission.VEHICLE_ALARM_HANDLE, Permission.VEHICLE_ALARM_ATTACHMENT_REQUEST,
                        Permission.VEHICLE_ALARM_ATTACHMENT_READ);
    }

    @Test
    void permitsEveryReadRoleToListAndGetDetailsButRejectsAUserWithoutReadPermission() throws Exception {
        UUID publicId = publicIdFor("ADAS");
        for (RoleCode role : RoleCode.values()) {
            mockMvc.perform(get("/api/vehicle-alarms").with(user(ACTOR_ID.toString()).authorities(authorities(role))))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/vehicle-alarms/{publicId}", publicId)
                            .with(user(ACTOR_ID.toString()).authorities(authorities(role))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.publicId").value(publicId.toString()))
                    .andExpect(jsonPath("$.data", not(hasKey("id"))));
        }
        mockMvc.perform(get("/api/vehicle-alarms")
                        .with(user(ACTOR_ID.toString()).authorities(new SimpleGrantedAuthority("ORDER_READ"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/vehicle-alarms/{publicId}", publicId)
                        .with(user(ACTOR_ID.toString()).authorities(new SimpleGrantedAuthority("ORDER_READ"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void mapsAllFiveActionsAndAllowsOnlyTheSystemAdministratorToReopen() throws Exception {
        UUID publicId = publicIdFor("ADAS");
        long acknowledgedVersion = action(publicId, "ACKNOWLEDGE", 0, RoleCode.DISPATCHER, true);
        long processingVersion = action(publicId, "TAKE_OVER", acknowledgedVersion, RoleCode.DISPATCHER, true);
        long resolvedVersion = action(publicId, "RESOLVE", processingVersion, RoleCode.DISPATCHER, true);

        mockMvc.perform(post("/api/vehicle-alarms/{publicId}/actions", publicId)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(actionBody("REOPEN", resolvedVersion, true))
                        .with(user(ACTOR_ID.toString()).authorities(authorities(RoleCode.DISPATCHER))))
                .andExpect(status().isForbidden());
        action(publicId, "REOPEN", resolvedVersion, RoleCode.SYSTEM_ADMIN, true);

        UUID falsePositive = publicIdFor("DMS");
        action(falsePositive, "MARK_FALSE_POSITIVE", 0, RoleCode.DISPATCHER, true);
        assertThat(actions.findAll()).filteredOn(record -> record.getVehicleAlarmId().equals(
                alarms.findByPublicId(falsePositive).orElseThrow().getId()))
                .singleElement().satisfies(record -> assertThat(record.getActionType()).isEqualTo("MARK_FALSE_POSITIVE"));
    }

    private static SimpleGrantedAuthority[] authorities(RoleCode role) {
        java.util.List<SimpleGrantedAuthority> permissions = Permission.permissionsFor(Set.of(role)).stream()
                .map(Permission::name)
                .map(SimpleGrantedAuthority::new)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        permissions.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
        return permissions.toArray(SimpleGrantedAuthority[]::new);
    }

    private long action(UUID publicId, String action, long version, RoleCode role, boolean confirmed) throws Exception {
        String response = mockMvc.perform(post("/api/vehicle-alarms/{publicId}/actions", publicId)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(actionBody(action, version, confirmed))
                        .with(user(ACTOR_ID.toString()).authorities(authorities(role))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publicId").value(publicId.toString()))
                .andExpect(jsonPath("$.data", not(hasKey("id"))))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.version")).longValue();
    }

    private UUID publicIdFor(String module) {
        return alarms.findAll().stream().filter(alarm -> module.equals(alarm.getModule()))
                .findFirst().orElseThrow().getPublicId();
    }

    private static String actionBody(String action, long version, boolean confirmed) {
        return """
                {"action":"%s","expectedVersion":%d,"reason":"操作依据已核实","confirmed":%s}
                """.formatted(action, version, confirmed);
    }

    private static VehicleAlarm alarm(String module, String type, int typeCode, Instant occurredAt) {
        VehicleAlarmIngressService.AlarmFact fact = new VehicleAlarmIngressService.AlarmFact(
                UUID.randomUUID(), UUID.randomUUID(), VEHICLE_ID,
                "T/JSATL12-2017", module, typeCode, type,
                typeCode, "START", 1, "ALARM-" + typeCode, occurredAt, occurredAt.plusSeconds(1),
                new BigDecimal("118.0000000"), new BigDecimal("32.0000000"), new BigDecimal("60.00"),
                UUID.randomUUID(), "UNASSESSED", "a".repeat(64));
        return VehicleAlarm.start(fact, UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID().toString().replace("-", ""),
                new AlarmStore.LocationReference(
                        UUID.randomUUID(), fact.onboardSystemId(), fact.occurredAt(), "GOOD", "[]"));
    }
}
