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
import com.idavy.drtops.domain.audit.AuditLogRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:vehicle_alarm_attachment_api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class VehicleAlarmAttachmentApiTest {
    private static final UUID ACTOR_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String FILE_NAME = "00_64_6401_01_SYNTHETIC0001.jpg";
    private static final String DIGEST = "b".repeat(64);

    @Autowired MockMvc mockMvc;
    @Autowired VehicleAlarmRepository alarms;
    @Autowired VehicleAlarmAttachmentRepository attachments;
    @Autowired VehicleAlarmAttachmentTransferRepository transfers;
    @Autowired AuditLogRepository auditLogs;

    @BeforeEach
    void reset() {
        auditLogs.deleteAll();
        transfers.deleteAll();
        attachments.deleteAll();
        alarms.deleteAll();
    }

    @Test
    void listsAttachmentsForAttachmentReadersWithoutLeakingStorageFacts() throws Exception {
        VehicleAlarm alarm = saveAlarm();
        VehicleAlarmAttachment attachment = attachments.saveAndFlush(VehicleAlarmAttachment.register(
                alarm.getId(), "IMAGE", "64", "jpg", FILE_NAME, 12345L, DIGEST,
                VehicleAlarmAttachment.Status.WAITING_MEDIA_SERVICE, Instant.parse("2026-08-15T02:00:00Z")));

        mockMvc.perform(get("/api/vehicle-alarms/{publicId}/attachments", alarm.getPublicId())
                        .with(user(ACTOR_ID.toString()).authorities(authorities(RoleCode.DISPATCHER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].attachmentId").value(attachment.getId().toString()))
                .andExpect(jsonPath("$.data[0].status").value("WAITING_MEDIA_SERVICE"))
                .andExpect(jsonPath("$.data[0]", not(hasKey("id"))))
                .andExpect(jsonPath("$.data[0]", not(hasKey("vehicleAlarmId"))))
                .andExpect(jsonPath("$.data[0]", not(hasKey("payloadDigest"))))
                .andExpect(jsonPath("$.data[0]", not(hasKey("externalMediaReference"))));

        for (RoleCode role : new RoleCode[]{RoleCode.OPERATOR, RoleCode.AUDITOR}) {
            mockMvc.perform(get("/api/vehicle-alarms/{publicId}/attachments", alarm.getPublicId())
                            .with(user(ACTOR_ID.toString()).authorities(authorities(role))))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void answersUploadRequestsWithTheApprovedIdentifierUnavailableContract() throws Exception {
        VehicleAlarm alarm = saveAlarm();
        VehicleAlarmAttachment attachment = attachments.saveAndFlush(VehicleAlarmAttachment.register(
                alarm.getId(), "IMAGE", "64", "jpg", FILE_NAME, 12345L, DIGEST,
                VehicleAlarmAttachment.Status.WAITING_MEDIA_SERVICE, Instant.parse("2026-08-15T02:00:00Z")));

        mockMvc.perform(post("/api/vehicle-alarms/{publicId}/attachments/{attachmentId}/requests",
                        alarm.getPublicId(), attachment.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(true))
                        .with(user(ACTOR_ID.toString()).authorities(authorities(RoleCode.DISPATCHER))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.data.errorCode").value("ALARM_IDENTIFIER_UNAVAILABLE"));

        mockMvc.perform(post("/api/vehicle-alarms/{publicId}/attachments/{attachmentId}/requests",
                        alarm.getPublicId(), attachment.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(false))
                        .with(user(ACTOR_ID.toString()).authorities(authorities(RoleCode.DISPATCHER))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/vehicle-alarms/{publicId}/attachments/{attachmentId}/requests",
                        alarm.getPublicId(), attachment.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(true))
                        .with(user(ACTOR_ID.toString()).authorities(authorities(RoleCode.OPERATOR))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/vehicle-alarms/{publicId}/attachments/{attachmentId}/requests",
                        UUID.randomUUID(), attachment.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(true))
                        .with(user(ACTOR_ID.toString()).authorities(authorities(RoleCode.DISPATCHER))))
                .andExpect(status().isNotFound());

        assertThat(attachments.findById(attachment.getId()).orElseThrow().getStatus())
                .isEqualTo(VehicleAlarmAttachment.Status.WAITING_MEDIA_SERVICE);
        assertThat(transfers.count()).isZero();
    }

    @Test
    void conflictsOnActiveTransfersAndReportsMediaOutageOnViewUrls() throws Exception {
        VehicleAlarm alarm = saveAlarm();
        VehicleAlarmAttachment uploading = attachments.saveAndFlush(VehicleAlarmAttachment.register(
                alarm.getId(), "IMAGE", "64", "jpg", FILE_NAME, 12345L, DIGEST,
                VehicleAlarmAttachment.Status.UPLOADING, Instant.parse("2026-08-15T02:00:00Z")));
        VehicleAlarmAttachment available = attachments.saveAndFlush(VehicleAlarmAttachment.register(
                alarm.getId(), "IMAGE", "64", "jpg", "00_64_6401_02_SYNTHETIC0002.jpg", 12345L, DIGEST,
                VehicleAlarmAttachment.Status.AVAILABLE, Instant.parse("2026-08-15T02:01:00Z")));

        mockMvc.perform(post("/api/vehicle-alarms/{publicId}/attachments/{attachmentId}/requests",
                        alarm.getPublicId(), uploading.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(true))
                        .with(user(ACTOR_ID.toString()).authorities(authorities(RoleCode.DISPATCHER))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("ATTACHMENT_TRANSFER_ACTIVE"));

        mockMvc.perform(post("/api/vehicle-alarms/{publicId}/attachments/{attachmentId}/view-url",
                        alarm.getPublicId(), available.getId())
                        .with(user(ACTOR_ID.toString()).authorities(authorities(RoleCode.DISPATCHER))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.errorCode").value("MEDIA_SERVICE_UNAVAILABLE"));

        mockMvc.perform(post("/api/vehicle-alarms/{publicId}/attachments/{attachmentId}/view-url",
                        alarm.getPublicId(), uploading.getId())
                        .with(user(ACTOR_ID.toString()).authorities(authorities(RoleCode.DISPATCHER))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("ATTACHMENT_NOT_AVAILABLE"));

        mockMvc.perform(post("/api/vehicle-alarms/{publicId}/attachments/{attachmentId}/view-url",
                        alarm.getPublicId(), available.getId())
                        .with(user(ACTOR_ID.toString()).authorities(authorities(RoleCode.OPERATOR))))
                .andExpect(status().isForbidden());
    }

    private static String requestBody(boolean confirmed) {
        return """
                {"reason":"调度需要核对现场证据","confirmed":%s}
                """.formatted(confirmed);
    }

    private static SimpleGrantedAuthority[] authorities(RoleCode role) {
        java.util.List<SimpleGrantedAuthority> permissions = Permission.permissionsFor(Set.of(role)).stream()
                .map(Permission::name)
                .map(SimpleGrantedAuthority::new)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        permissions.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
        return permissions.toArray(SimpleGrantedAuthority[]::new);
    }

    private VehicleAlarm saveAlarm() {
        VehicleAlarmIngressService.AlarmFact fact = new VehicleAlarmIngressService.AlarmFact(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "T/JSATL12-2017", "ADAS", 1,
                "FORWARD_COLLISION", 4097L, "START", 1, "00000001",
                Instant.parse("2026-01-15T02:00:00Z"), Instant.parse("2026-01-15T02:00:01Z"),
                new BigDecimal("118.0000000"), new BigDecimal("32.0000000"), new BigDecimal("60.00"),
                UUID.randomUUID(), "UNASSESSED", "a".repeat(64));
        return alarms.saveAndFlush(VehicleAlarm.start(fact, UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", ""),
                new AlarmStore.LocationReference(
                        UUID.randomUUID(), fact.onboardSystemId(), fact.occurredAt(), "GOOD", "[]")));
    }
}
