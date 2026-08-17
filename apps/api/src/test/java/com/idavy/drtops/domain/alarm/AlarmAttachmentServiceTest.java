package com.idavy.drtops.domain.alarm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idavy.drtops.domain.alarm.AlarmAttachmentService.AttachmentMediaCallback;
import com.idavy.drtops.domain.alarm.AlarmAttachmentService.MediaCallbackOutcome;
import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.integration.media.MediaServiceUnavailableException;
import java.math.BigDecimal;
import java.time.Instant;
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
        "spring.datasource.url=jdbc:h2:mem:alarm_attachments;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import(AlarmAttachmentServiceTest.AuthorizationConfiguration.class)
class AlarmAttachmentServiceTest {
    private static final UUID ACTOR_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DENIED_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String DIGEST = "b".repeat(64);
    private static final String FILE_NAME = "00_64_6401_01_SYNTHETIC0001.jpg";

    @Autowired AlarmAttachmentService service;
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
    void listsAttachmentsWithOnlySanitizedFieldsForAuthorizedReader() {
        VehicleAlarm alarm = saveAlarm();
        VehicleAlarmAttachment attachment = attachments.saveAndFlush(VehicleAlarmAttachment.register(
                alarm.getId(), "IMAGE", "64", "jpg", FILE_NAME, 12345L, DIGEST,
                VehicleAlarmAttachment.Status.WAITING_MEDIA_SERVICE, Instant.parse("2026-08-15T02:00:00Z")));

        assertThat(service.listAttachments(ACTOR_ID, alarm.getPublicId()))
                .singleElement().satisfies(view -> {
                    assertThat(view.attachmentId()).isEqualTo(attachment.getId());
                    assertThat(view.attachmentType()).isEqualTo("IMAGE");
                    assertThat(view.channel()).isEqualTo("64");
                    assertThat(view.mediaFormat()).isEqualTo("jpg");
                    assertThat(view.fileName()).isEqualTo(FILE_NAME);
                    assertThat(view.sizeBytes()).isEqualTo(12345L);
                    assertThat(view.status()).isEqualTo("WAITING_MEDIA_SERVICE");
                });
        assertThatThrownBy(() -> service.listAttachments(DENIED_ID, alarm.getPublicId()))
                .isInstanceOf(VehicleAlarmAuthorizationException.class);
    }

    @Test
    void failsUploadRequestsWithAlarmIdentifierUnavailableWithoutCreatingTransfers() {
        VehicleAlarm alarm = saveAlarm();
        VehicleAlarmAttachment waiting = attachments.saveAndFlush(VehicleAlarmAttachment.register(
                alarm.getId(), "IMAGE", "64", "jpg", FILE_NAME, 12345L, null,
                VehicleAlarmAttachment.Status.WAITING_MEDIA_SERVICE, Instant.parse("2026-08-15T02:00:00Z")));
        VehicleAlarmAttachment failed = attachments.saveAndFlush(VehicleAlarmAttachment.register(
                alarm.getId(), "VIDEO", "65", "h264", "02_65_6502_01_SYNTHETIC0002.h264", null, null,
                VehicleAlarmAttachment.Status.FAILED, Instant.parse("2026-08-15T02:01:00Z")));

        for (VehicleAlarmAttachment attachment : new VehicleAlarmAttachment[]{waiting, failed}) {
            assertThatThrownBy(() -> service.requestUpload(
                    ACTOR_ID, alarm.getPublicId(), attachment.getId(), "调度需要核对现场证据"))
                    .isInstanceOf(AlarmAttachmentRequestException.class)
                    .satisfies(exception -> assertThat(
                            ((AlarmAttachmentRequestException) exception).errorCode())
                            .isEqualTo("ALARM_IDENTIFIER_UNAVAILABLE"));
        }

        assertThat(attachments.findById(waiting.getId()).orElseThrow().getStatus())
                .isEqualTo(VehicleAlarmAttachment.Status.WAITING_MEDIA_SERVICE);
        assertThat(attachments.findById(failed.getId()).orElseThrow().getStatus())
                .isEqualTo(VehicleAlarmAttachment.Status.FAILED);
        assertThat(transfers.count()).isZero();
        assertThat(auditLogs.findAll()).hasSize(2).allSatisfy(audit -> {
            assertThat(audit.getEntityType()).isEqualTo("VEHICLE_ALARM_ATTACHMENT");
            assertThat(audit.getAction()).isEqualTo("VEHICLE_ALARM_ATTACHMENT_REQUEST");
            assertThat(audit.getActorId()).isEqualTo(ACTOR_ID.toString());
            assertThat(audit.getReason()).isEqualTo("调度需要核对现场证据");
            assertThat(audit.getMetadataJson()).contains("ALARM_IDENTIFIER_UNAVAILABLE");
        });
    }

    @Test
    void rejectsUploadRequestsForActiveTransfersAndTerminalStates() {
        VehicleAlarm alarm = saveAlarm();
        VehicleAlarmAttachment requested = seedAttachment(alarm, VehicleAlarmAttachment.Status.REQUESTED);
        VehicleAlarmAttachment uploading = seedAttachment(alarm, VehicleAlarmAttachment.Status.UPLOADING);
        VehicleAlarmAttachment available = seedAttachment(alarm, VehicleAlarmAttachment.Status.AVAILABLE);
        VehicleAlarmAttachment expired = seedAttachment(alarm, VehicleAlarmAttachment.Status.EXPIRED);

        assertConflict(alarm, requested, "ATTACHMENT_TRANSFER_ACTIVE");
        assertConflict(alarm, uploading, "ATTACHMENT_TRANSFER_ACTIVE");
        assertConflict(alarm, available, "ATTACHMENT_ALREADY_AVAILABLE");
        assertConflict(alarm, expired, "ATTACHMENT_EXPIRED");
        assertThat(auditLogs.count()).isZero();
        assertThat(transfers.count()).isZero();
    }

    @Test
    void rejectsUploadRequestsWithoutPermissionReasonOrMatchingAlarm() {
        VehicleAlarm alarm = saveAlarm();
        VehicleAlarm other = saveAlarm();
        VehicleAlarmAttachment attachment = seedAttachment(alarm, VehicleAlarmAttachment.Status.WAITING_MEDIA_SERVICE);

        assertThatThrownBy(() -> service.requestUpload(
                DENIED_ID, alarm.getPublicId(), attachment.getId(), "越权请求"))
                .isInstanceOf(VehicleAlarmAuthorizationException.class);
        assertThatThrownBy(() -> service.requestUpload(
                ACTOR_ID, alarm.getPublicId(), attachment.getId(), " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.requestUpload(
                ACTOR_ID, alarm.getPublicId(), attachment.getId(), "x".repeat(301)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.requestUpload(
                ACTOR_ID, UUID.randomUUID(), attachment.getId(), "报警不存在"))
                .isInstanceOf(VehicleAlarmNotFoundException.class);
        assertThatThrownBy(() -> service.requestUpload(
                ACTOR_ID, other.getPublicId(), attachment.getId(), "跨报警引用"))
                .isInstanceOf(AlarmAttachmentNotFoundException.class);
        assertThat(auditLogs.count()).isZero();
    }

    @Test
    void confirmsMatchingMediaCallbackAndMarksAttachmentAvailable() {
        VehicleAlarm alarm = saveAlarm();
        VehicleAlarmAttachment attachment = attachments.saveAndFlush(VehicleAlarmAttachment.register(
                alarm.getId(), "IMAGE", "64", "jpg", FILE_NAME, 12345L, DIGEST,
                VehicleAlarmAttachment.Status.UPLOADING, Instant.parse("2026-08-15T02:00:00Z")));
        VehicleAlarmAttachmentTransfer transfer = transfers.saveAndFlush(uploadingTransfer(attachment, "ref-1"));

        MediaCallbackOutcome outcome = service.handleMediaCallback(new AttachmentMediaCallback(
                "ref-1", FILE_NAME, "IMAGE", 12345L, DIGEST, AttachmentMediaCallback.Result.SUCCESS));

        assertThat(outcome).isEqualTo(MediaCallbackOutcome.CONFIRMED);
        VehicleAlarmAttachment stored = attachments.findById(attachment.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(VehicleAlarmAttachment.Status.AVAILABLE);
        assertThat(stored.getPayloadDigest()).isEqualTo(DIGEST);
        assertThat(stored.getSizeBytes()).isEqualTo(12345L);
        assertThat(stored.getExternalMediaReference()).isEqualTo("ref-1");
        assertThat(transfers.findById(transfer.getId()).orElseThrow().getStatus())
                .isEqualTo(VehicleAlarmAttachmentTransfer.Status.AVAILABLE);
        assertThat(auditLogs.findAll()).singleElement().satisfies(audit -> {
            assertThat(audit.getEntityType()).isEqualTo("VEHICLE_ALARM_ATTACHMENT");
            assertThat(audit.getEntityId()).isEqualTo(attachment.getId());
            assertThat(audit.getAction()).isEqualTo("VEHICLE_ALARM_ATTACHMENT_AVAILABLE");
        });
    }

    @Test
    void keepsRepeatedMediaCallbacksIdempotent() {
        VehicleAlarm alarm = saveAlarm();
        VehicleAlarmAttachment attachment = attachments.saveAndFlush(VehicleAlarmAttachment.register(
                alarm.getId(), "IMAGE", "64", "jpg", FILE_NAME, 12345L, DIGEST,
                VehicleAlarmAttachment.Status.UPLOADING, Instant.parse("2026-08-15T02:00:00Z")));
        transfers.saveAndFlush(uploadingTransfer(attachment, "ref-dup"));
        AttachmentMediaCallback callback = new AttachmentMediaCallback(
                "ref-dup", FILE_NAME, "IMAGE", 12345L, DIGEST, AttachmentMediaCallback.Result.SUCCESS);

        assertThat(service.handleMediaCallback(callback)).isEqualTo(MediaCallbackOutcome.CONFIRMED);
        assertThat(service.handleMediaCallback(callback)).isEqualTo(MediaCallbackOutcome.DUPLICATE);

        assertThat(attachments.findById(attachment.getId()).orElseThrow().getStatus())
                .isEqualTo(VehicleAlarmAttachment.Status.AVAILABLE);
        assertThat(auditLogs.findAll()).hasSize(1);
    }

    @Test
    void rejectsCallbacksForUnknownReferencesAndUnexpectedStates() {
        VehicleAlarm alarm = saveAlarm();
        VehicleAlarmAttachment waiting = seedAttachment(alarm, VehicleAlarmAttachment.Status.WAITING_MEDIA_SERVICE);
        transfers.saveAndFlush(uploadingTransfer(waiting, "ref-waiting"));

        assertThat(service.handleMediaCallback(new AttachmentMediaCallback(
                "ref-unknown", FILE_NAME, "IMAGE", 12345L, DIGEST, AttachmentMediaCallback.Result.SUCCESS)))
                .isEqualTo(MediaCallbackOutcome.UNKNOWN_REFERENCE);
        assertThat(service.handleMediaCallback(new AttachmentMediaCallback(
                "ref-waiting", FILE_NAME, "IMAGE", 12345L, DIGEST, AttachmentMediaCallback.Result.SUCCESS)))
                .isEqualTo(MediaCallbackOutcome.UNEXPECTED_STATE);
        assertThat(attachments.findById(waiting.getId()).orElseThrow().getStatus())
                .isEqualTo(VehicleAlarmAttachment.Status.WAITING_MEDIA_SERVICE);
        assertThat(auditLogs.count()).isZero();
    }

    @Test
    void failsAttachmentsOnDigestSizeAndFilenameMismatch() {
        assertMismatchFails("ref-digest", FILE_NAME, 12345L, "c".repeat(64), "DIGEST_MISMATCH");
        assertMismatchFails("ref-size", FILE_NAME, 54321L, DIGEST, "SIZE_MISMATCH");
        assertMismatchFails("ref-name", "02_65_6502_01_OTHER.h264", 12345L, DIGEST, "FILENAME_MISMATCH");
    }

    @Test
    void enforcesMediaTypeAndSizeWhitelistOnCallbacks() {
        assertMismatchFails("ref-type", FILE_NAME, 12345L, DIGEST, "MEDIA_TYPE_NOT_ALLOWED", "EXECUTABLE");
        assertMismatchFails("ref-oversize", FILE_NAME, 65L * 1024 * 1024, DIGEST, "SIZE_NOT_ALLOWED", "IMAGE");
        assertMismatchFails("ref-digest-format", FILE_NAME, 12345L, "not-a-hex-digest", "DIGEST_MISMATCH", "IMAGE");
    }

    @Test
    void marksMediaReportedFailuresWithoutAcceptingPayloadFacts() {
        VehicleAlarm alarm = saveAlarm();
        VehicleAlarmAttachment attachment = attachments.saveAndFlush(VehicleAlarmAttachment.register(
                alarm.getId(), "IMAGE", "64", "jpg", FILE_NAME, 12345L, DIGEST,
                VehicleAlarmAttachment.Status.UPLOADING, Instant.parse("2026-08-15T02:00:00Z")));
        VehicleAlarmAttachmentTransfer transfer = transfers.saveAndFlush(uploadingTransfer(attachment, "ref-fail"));

        MediaCallbackOutcome outcome = service.handleMediaCallback(new AttachmentMediaCallback(
                "ref-fail", FILE_NAME, "IMAGE", 12345L, DIGEST, AttachmentMediaCallback.Result.FAILED));

        assertThat(outcome).isEqualTo(MediaCallbackOutcome.CONFIRMED);
        assertThat(attachments.findById(attachment.getId()).orElseThrow().getStatus())
                .isEqualTo(VehicleAlarmAttachment.Status.FAILED);
        VehicleAlarmAttachmentTransfer storedTransfer = transfers.findById(transfer.getId()).orElseThrow();
        assertThat(storedTransfer.getStatus()).isEqualTo(VehicleAlarmAttachmentTransfer.Status.FAILED);
        assertThat(storedTransfer.getErrorCode()).isEqualTo("MEDIA_UPLOAD_FAILED");
        assertThat(auditLogs.findAll()).singleElement().satisfies(audit ->
                assertThat(audit.getAction()).isEqualTo("VEHICLE_ALARM_ATTACHMENT_FAILED"));
    }

    @Test
    void issuesViewUrlsOnlyForAvailableAttachmentsAndAuditsEveryAttempt() {
        VehicleAlarm alarm = saveAlarm();
        VehicleAlarmAttachment available = attachments.saveAndFlush(VehicleAlarmAttachment.register(
                alarm.getId(), "IMAGE", "64", "jpg", FILE_NAME, 12345L, DIGEST,
                VehicleAlarmAttachment.Status.AVAILABLE, Instant.parse("2026-08-15T02:00:00Z")));
        VehicleAlarmAttachment waiting = seedAttachment(alarm, VehicleAlarmAttachment.Status.WAITING_MEDIA_SERVICE);

        assertThatThrownBy(() -> service.issueViewUrl(DENIED_ID, alarm.getPublicId(), available.getId()))
                .isInstanceOf(VehicleAlarmAuthorizationException.class);
        assertThatThrownBy(() -> service.issueViewUrl(ACTOR_ID, alarm.getPublicId(), waiting.getId()))
                .isInstanceOf(AlarmAttachmentConflictException.class)
                .satisfies(exception -> assertThat(
                        ((AlarmAttachmentConflictException) exception).errorCode())
                        .isEqualTo("ATTACHMENT_NOT_AVAILABLE"));
        assertThatThrownBy(() -> service.issueViewUrl(ACTOR_ID, alarm.getPublicId(), available.getId()))
                .isInstanceOf(MediaServiceUnavailableException.class);

        assertThat(auditLogs.findAll()).singleElement().satisfies(audit -> {
            assertThat(audit.getEntityType()).isEqualTo("VEHICLE_ALARM_ATTACHMENT");
            assertThat(audit.getEntityId()).isEqualTo(available.getId());
            assertThat(audit.getAction()).isEqualTo("VEHICLE_ALARM_ATTACHMENT_VIEW");
            assertThat(audit.getMetadataJson()).contains("MEDIA_SERVICE_UNAVAILABLE");
        });
    }

    @Test
    void sanitizesFileNamesBeforePersistingOrComparing() {
        assertThat(AlarmAttachmentService.sanitizeFileName("../etc/passwd.jpg")).isEqualTo("passwd.jpg");
        assertThat(AlarmAttachmentService.sanitizeFileName("..\\..\\windows\\system32\\driver.h264"))
                .isEqualTo("driver.h264");
        assertThat(AlarmAttachmentService.sanitizeFileName("  spaced name .jpg ")).isEqualTo("spaced_name_.jpg");
        assertThat(AlarmAttachmentService.sanitizeFileName("normal_name-01.jpg")).isEqualTo("normal_name-01.jpg");
        assertThat(AlarmAttachmentService.sanitizeFileName("a".repeat(200) + ".jpg")).hasSize(100);
        assertThatThrownBy(() -> AlarmAttachmentService.sanitizeFileName("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AlarmAttachmentService.sanitizeFileName(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertConflict(VehicleAlarm alarm, VehicleAlarmAttachment attachment, String errorCode) {
        assertThatThrownBy(() -> service.requestUpload(
                ACTOR_ID, alarm.getPublicId(), attachment.getId(), "重复请求"))
                .isInstanceOf(AlarmAttachmentConflictException.class)
                .satisfies(exception -> assertThat(
                        ((AlarmAttachmentConflictException) exception).errorCode()).isEqualTo(errorCode));
    }

    private void assertMismatchFails(
            String reference, String fileName, long sizeBytes, String digest, String errorCode) {
        assertMismatchFails(reference, fileName, sizeBytes, digest, errorCode, "IMAGE");
    }

    private void assertMismatchFails(
            String reference, String fileName, long sizeBytes, String digest, String errorCode, String fileType) {
        VehicleAlarm alarm = saveAlarm();
        VehicleAlarmAttachment attachment = attachments.saveAndFlush(VehicleAlarmAttachment.register(
                alarm.getId(), "IMAGE", "64", "jpg", FILE_NAME, 12345L, DIGEST,
                VehicleAlarmAttachment.Status.UPLOADING, Instant.parse("2026-08-15T02:00:00Z")));
        VehicleAlarmAttachmentTransfer transfer = transfers.saveAndFlush(uploadingTransfer(attachment, reference));

        MediaCallbackOutcome outcome = service.handleMediaCallback(new AttachmentMediaCallback(
                reference, fileName, fileType, sizeBytes, digest, AttachmentMediaCallback.Result.SUCCESS));

        assertThat(outcome).isEqualTo(MediaCallbackOutcome.CONFIRMED);
        assertThat(attachments.findById(attachment.getId()).orElseThrow().getStatus())
                .isEqualTo(VehicleAlarmAttachment.Status.FAILED);
        VehicleAlarmAttachmentTransfer storedTransfer = transfers.findById(transfer.getId()).orElseThrow();
        assertThat(storedTransfer.getStatus()).isEqualTo(VehicleAlarmAttachmentTransfer.Status.FAILED);
        assertThat(storedTransfer.getErrorCode()).isEqualTo(errorCode);
    }

    private VehicleAlarmAttachmentTransfer uploadingTransfer(VehicleAlarmAttachment attachment, String reference) {
        VehicleAlarmAttachmentTransfer transfer = VehicleAlarmAttachmentTransfer.requested(
                attachment.getId(), "0x9208", 1, reference, Instant.parse("2026-08-15T02:00:10Z"));
        transfer.markUploading(7);
        return transfer;
    }

    private VehicleAlarmAttachment seedAttachment(VehicleAlarm alarm, VehicleAlarmAttachment.Status status) {
        return attachments.saveAndFlush(VehicleAlarmAttachment.register(
                alarm.getId(), "IMAGE", "64", "jpg", FILE_NAME, 12345L, DIGEST, status,
                Instant.parse("2026-08-15T02:00:00Z")));
    }

    private VehicleAlarm saveAlarm() {
        VehicleAlarmIngressService.AlarmFact fact = new VehicleAlarmIngressService.AlarmFact(
                UUID.randomUUID(), UUID.randomUUID(), "T/JSATL12-2017", "ADAS", 1,
                "FORWARD_COLLISION", 4097L, "START", 1, "00000001",
                Instant.parse("2026-01-15T02:00:00Z"), Instant.parse("2026-01-15T02:00:01Z"),
                new BigDecimal("118.0000000"), new BigDecimal("32.0000000"), new BigDecimal("60.00"),
                UUID.randomUUID(), "UNASSESSED", "a".repeat(64));
        return alarms.saveAndFlush(VehicleAlarm.start(fact, UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", ""),
                new AlarmStore.LocationReference(UUID.randomUUID(), "GOOD", "[]")));
    }

    @TestConfiguration
    static class AuthorizationConfiguration {
        @Bean
        @Primary
        VehicleAlarmAuthorization vehicleAlarmAuthorization() {
            return new VehicleAlarmAuthorization() {
                @Override
                public boolean mayHandle(UUID actorId) {
                    return false;
                }

                @Override
                public boolean mayReopen(UUID actorId) {
                    return false;
                }

                @Override
                public boolean mayReadAttachment(UUID actorId) {
                    return ACTOR_ID.equals(actorId);
                }

                @Override
                public boolean mayRequestAttachment(UUID actorId) {
                    return ACTOR_ID.equals(actorId);
                }
            };
        }
    }
}
