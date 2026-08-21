package com.idavy.drtops.domain.alarm;

import com.idavy.drtops.domain.audit.AuditLog;
import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.integration.media.AlarmAttachmentMediaPort;
import com.idavy.drtops.integration.media.MediaServiceUnavailableException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alarm attachment state machine. Approved ruling (2026-08-17): the raw 16-byte terminal alarm
 * identifier is not retained (Task 10 stores only its SHA-256 digest), so upload requests that would
 * require the 0x9208 downlink fail explicitly with ALARM_IDENTIFIER_UNAVAILABLE and never create a
 * transfer. One-time upload targets and long-lived URLs are never persisted, logged or published.
 */
@Service
public class AlarmAttachmentService {
    public static final String ERROR_ALARM_IDENTIFIER_UNAVAILABLE = "ALARM_IDENTIFIER_UNAVAILABLE";
    static final Set<String> ALLOWED_MEDIA_TYPES = Set.of("IMAGE", "AUDIO", "VIDEO", "TEXT", "OTHER");
    static final long MAX_ATTACHMENT_SIZE_BYTES = 64L * 1024 * 1024;
    private static final Duration VIEW_URL_TTL = Duration.ofMinutes(5);
    private static final int MAX_FILE_NAME_LENGTH = 100;

    private final VehicleAlarmRepository alarms;
    private final VehicleAlarmAttachmentRepository attachments;
    private final VehicleAlarmAttachmentTransferRepository transfers;
    private final AuditLogRepository auditLogs;
    private final VehicleAlarmAuthorization authorization;
    private final AlarmAttachmentMediaPort mediaPort;

    public AlarmAttachmentService(
            VehicleAlarmRepository alarms,
            VehicleAlarmAttachmentRepository attachments,
            VehicleAlarmAttachmentTransferRepository transfers,
            AuditLogRepository auditLogs,
            VehicleAlarmAuthorization authorization,
            AlarmAttachmentMediaPort mediaPort) {
        this.alarms = Objects.requireNonNull(alarms);
        this.attachments = Objects.requireNonNull(attachments);
        this.transfers = Objects.requireNonNull(transfers);
        this.auditLogs = Objects.requireNonNull(auditLogs);
        this.authorization = Objects.requireNonNull(authorization);
        this.mediaPort = Objects.requireNonNull(mediaPort);
    }

    public List<AttachmentView> listAttachments(UUID actorId, UUID alarmPublicId) {
        if (!authorization.mayReadAttachment(actorId)) {
            throw new VehicleAlarmAuthorizationException("vehicle alarm attachment read is forbidden");
        }
        VehicleAlarm alarm = readAlarm(alarmPublicId);
        return attachments.findByVehicleAlarmIdOrderByCreatedAtAsc(alarm.getId()).stream()
                .map(AlarmAttachmentService::view)
                .toList();
    }

    /**
     * Deliberately not transactional: the audit of a rejected request must survive the failure.
     * No transfer row is created while the raw terminal alarm identifier is unavailable.
     */
    public AttachmentView requestUpload(UUID actorId, UUID alarmPublicId, UUID attachmentId, String reason) {
        if (!authorization.mayRequestAttachment(actorId)) {
            throw new VehicleAlarmAuthorizationException("vehicle alarm attachment request is forbidden");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        if (reason.length() > 300) {
            throw new IllegalArgumentException("reason is too long");
        }
        VehicleAlarm alarm = readAlarm(alarmPublicId);
        VehicleAlarmAttachment attachment = readAttachment(alarm, attachmentId);
        switch (attachment.getStatus()) {
            case REQUESTED, UPLOADING ->
                    throw new AlarmAttachmentConflictException("ATTACHMENT_TRANSFER_ACTIVE");
            case AVAILABLE -> throw new AlarmAttachmentConflictException("ATTACHMENT_ALREADY_AVAILABLE");
            case EXPIRED -> throw new AlarmAttachmentConflictException("ATTACHMENT_EXPIRED");
            case WAITING_MEDIA_SERVICE, FAILED -> { }
        }
        // Ruling: the raw 16-byte terminal alarm identifier required by the 0x9208 downlink is not
        // retained, so no upload can be dispatched yet. Record the attempt, keep the state.
        audit(attachment.getId(), "VEHICLE_ALARM_ATTACHMENT_REQUEST", "USER", actorId.toString(), reason,
                "{\"outcome\":\"REJECTED\",\"errorCode\":\"" + ERROR_ALARM_IDENTIFIER_UNAVAILABLE + "\"}");
        throw new AlarmAttachmentRequestException(ERROR_ALARM_IDENTIFIER_UNAVAILABLE);
    }

    /** Applies a signature-verified media service callback; idempotent for repeated notifications. */
    @Transactional
    public MediaCallbackOutcome handleMediaCallback(AttachmentMediaCallback callback) {
        Objects.requireNonNull(callback, "callback");
        if (callback.externalMediaReference() == null || callback.externalMediaReference().isBlank()) {
            return MediaCallbackOutcome.UNKNOWN_REFERENCE;
        }
        var found = transfers.findByExternalTargetReference(callback.externalMediaReference());
        if (found.isEmpty()) {
            return MediaCallbackOutcome.UNKNOWN_REFERENCE;
        }
        VehicleAlarmAttachmentTransfer transfer = found.get();
        VehicleAlarmAttachment attachment = attachments.findById(transfer.getVehicleAlarmAttachmentId())
                .orElse(null);
        if (attachment == null) {
            return MediaCallbackOutcome.UNKNOWN_REFERENCE;
        }
        if (transfer.getStatus() == VehicleAlarmAttachmentTransfer.Status.AVAILABLE
                || transfer.getStatus() == VehicleAlarmAttachmentTransfer.Status.FAILED
                || transfer.getStatus() == VehicleAlarmAttachmentTransfer.Status.EXPIRED) {
            return MediaCallbackOutcome.DUPLICATE;
        }
        if (attachment.getStatus() != VehicleAlarmAttachment.Status.UPLOADING) {
            return MediaCallbackOutcome.UNEXPECTED_STATE;
        }
        if (callback.result() == AttachmentMediaCallback.Result.FAILED) {
            failTransfer(attachment, transfer, "MEDIA_UPLOAD_FAILED");
            return MediaCallbackOutcome.CONFIRMED;
        }
        String failureCode = validateCallbackFacts(attachment, callback);
        if (failureCode != null) {
            failTransfer(attachment, transfer, failureCode);
            return MediaCallbackOutcome.CONFIRMED;
        }
        if (attachment.getSanitizedFilename() == null) {
            attachment.confirmSanitizedFilename(sanitizeFileName(callback.fileName()));
        }
        attachment.markAvailable(callback.sha256(), callback.sizeBytes(), callback.externalMediaReference());
        transfer.markAvailable();
        audit(attachment.getId(), "VEHICLE_ALARM_ATTACHMENT_AVAILABLE", "MEDIA_SERVICE",
                callback.externalMediaReference(), null, "{}");
        return MediaCallbackOutcome.CONFIRMED;
    }

    /** Not transactional: a rejected view attempt must still be audited. */
    public AttachmentViewUrl issueViewUrl(UUID actorId, UUID alarmPublicId, UUID attachmentId) {
        if (!authorization.mayReadAttachment(actorId)) {
            throw new VehicleAlarmAuthorizationException("vehicle alarm attachment read is forbidden");
        }
        VehicleAlarm alarm = readAlarm(alarmPublicId);
        VehicleAlarmAttachment attachment = readAttachment(alarm, attachmentId);
        if (attachment.getStatus() != VehicleAlarmAttachment.Status.AVAILABLE) {
            throw new AlarmAttachmentConflictException("ATTACHMENT_NOT_AVAILABLE");
        }
        try {
            AlarmAttachmentMediaPort.ViewUrl viewUrl = mediaPort.issueShortLivedViewUrl(
                    attachment.getExternalMediaReference(), VIEW_URL_TTL);
            audit(attachment.getId(), "VEHICLE_ALARM_ATTACHMENT_VIEW", "USER", actorId.toString(), null,
                    "{\"outcome\":\"ISSUED\"}");
            return new AttachmentViewUrl(viewUrl.url(), viewUrl.expiresAt());
        } catch (MediaServiceUnavailableException unavailable) {
            audit(attachment.getId(), "VEHICLE_ALARM_ATTACHMENT_VIEW", "USER", actorId.toString(), null,
                    "{\"outcome\":\"REJECTED\",\"errorCode\":\"MEDIA_SERVICE_UNAVAILABLE\"}");
            throw unavailable;
        }
    }

    /** Keeps the last path segment, replaces unsafe characters and caps the length. */
    public static String sanitizeFileName(String rawName) {
        if (rawName == null) {
            throw new IllegalArgumentException("file name is required");
        }
        String name = rawName.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        StringBuilder sanitized = new StringBuilder(name.length());
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            boolean safe = character >= 'a' && character <= 'z'
                    || character >= 'A' && character <= 'Z'
                    || character >= '0' && character <= '9'
                    || character == '.' || character == '_' || character == '-';
            sanitized.append(safe ? character : '_');
        }
        String result = sanitized.toString();
        if (result.length() > MAX_FILE_NAME_LENGTH) {
            result = result.substring(result.length() - MAX_FILE_NAME_LENGTH);
        }
        if (result.isBlank() || result.chars().allMatch(character -> character == '.' || character == '_')) {
            throw new IllegalArgumentException("file name has no safe content");
        }
        return result;
    }

    private String validateCallbackFacts(VehicleAlarmAttachment attachment, AttachmentMediaCallback callback) {
        if (callback.fileType() == null || !ALLOWED_MEDIA_TYPES.contains(callback.fileType())) {
            return "MEDIA_TYPE_NOT_ALLOWED";
        }
        if (callback.sizeBytes() == null
                || callback.sizeBytes() < 0
                || callback.sizeBytes() > MAX_ATTACHMENT_SIZE_BYTES) {
            return "SIZE_NOT_ALLOWED";
        }
        if (!isSha256Hex(callback.sha256())) {
            return "DIGEST_MISMATCH";
        }
        String sanitized;
        try {
            sanitized = sanitizeFileName(callback.fileName());
        } catch (IllegalArgumentException exception) {
            return "FILENAME_MISMATCH";
        }
        if (attachment.getSanitizedFilename() != null
                && !attachment.getSanitizedFilename().equals(sanitized)) {
            return "FILENAME_MISMATCH";
        }
        if (attachment.getSizeBytes() != null
                && attachment.getSizeBytes().longValue() != callback.sizeBytes()) {
            return "SIZE_MISMATCH";
        }
        if (attachment.getPayloadDigest() != null
                && !attachment.getPayloadDigest().equals(callback.sha256())) {
            return "DIGEST_MISMATCH";
        }
        return null;
    }

    private void failTransfer(
            VehicleAlarmAttachment attachment, VehicleAlarmAttachmentTransfer transfer, String errorCode) {
        attachment.markFailed();
        transfer.markFailed(errorCode);
        audit(attachment.getId(), "VEHICLE_ALARM_ATTACHMENT_FAILED", "MEDIA_SERVICE",
                transfer.getExternalTargetReference(), null, "{\"errorCode\":\"" + errorCode + "\"}");
    }

    private VehicleAlarm readAlarm(UUID alarmPublicId) {
        if (alarmPublicId == null) {
            throw new IllegalArgumentException("publicId is required");
        }
        return alarms.findByPublicId(alarmPublicId)
                .orElseThrow(() -> new VehicleAlarmNotFoundException("vehicle alarm not found"));
    }

    private VehicleAlarmAttachment readAttachment(VehicleAlarm alarm, UUID attachmentId) {
        if (attachmentId == null) {
            throw new IllegalArgumentException("attachmentId is required");
        }
        return attachments.findById(attachmentId)
                .filter(candidate -> candidate.getVehicleAlarmId().equals(alarm.getId()))
                .orElseThrow(() -> new AlarmAttachmentNotFoundException("vehicle alarm attachment not found"));
    }

    private void audit(
            UUID attachmentId, String action, String actorType, String actorId, String reason, String metadata) {
        auditLogs.save(AuditLog.record(
                "VEHICLE_ALARM_ATTACHMENT", attachmentId, action, actorType, actorId, reason, metadata));
    }

    private static AttachmentView view(VehicleAlarmAttachment attachment) {
        return new AttachmentView(
                attachment.getId(), attachment.getAttachmentType(), attachment.getChannel(),
                attachment.getMediaFormat(), attachment.getSanitizedFilename(), attachment.getSizeBytes(),
                attachment.getStatus().name(), attachment.getCreatedAt());
    }

    private static boolean isSha256Hex(String value) {
        if (value == null || value.length() != 64) {
            return false;
        }
        try {
            HexFormat.of().parseHex(value);
            return value.equals(value.toLowerCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public record AttachmentView(
            UUID attachmentId,
            String attachmentType,
            String channel,
            String mediaFormat,
            String fileName,
            Long sizeBytes,
            String status,
            Instant createdAt) { }

    public record AttachmentMediaCallback(
            String externalMediaReference,
            String fileName,
            String fileType,
            Long sizeBytes,
            String sha256,
            Result result) {
        public enum Result {
            SUCCESS,
            FAILED
        }
    }

    public enum MediaCallbackOutcome {
        CONFIRMED,
        DUPLICATE,
        UNKNOWN_REFERENCE,
        UNEXPECTED_STATE
    }

    public record AttachmentViewUrl(String url, Instant expiresAt) { }
}
