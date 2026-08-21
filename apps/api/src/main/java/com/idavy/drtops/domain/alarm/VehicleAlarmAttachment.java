package com.idavy.drtops.domain.alarm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "vehicle_alarm_attachments")
public class VehicleAlarmAttachment {
    @Id @Column(name = "id", nullable = false) private UUID id;
    @Column(name = "vehicle_alarm_id", nullable = false) private UUID vehicleAlarmId;
    @Column(name = "attachment_type", nullable = false, length = 40) private String attachmentType;
    @Column(name = "channel", nullable = false, length = 40) private String channel;
    @Column(name = "media_format", nullable = false, length = 40) private String mediaFormat;
    @Column(name = "sanitized_filename", nullable = true, length = 255) private String sanitizedFilename;
    @Column(name = "size_bytes", nullable = true) private Long sizeBytes;
    @Column(name = "payload_digest", nullable = true, length = 64, columnDefinition = "char(64)")
    private String payloadDigest;
    @Column(name = "external_media_reference", nullable = true, length = 255)
    private String externalMediaReference;
    @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false, length = 30) private Status status;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected VehicleAlarmAttachment() { }

    public static VehicleAlarmAttachment register(
            UUID vehicleAlarmId,
            String attachmentType,
            String channel,
            String mediaFormat,
            String sanitizedFilename,
            Long sizeBytes,
            String payloadDigest,
            Status status,
            Instant createdAt) {
        VehicleAlarmAttachment attachment = new VehicleAlarmAttachment();
        attachment.id = UUID.randomUUID();
        attachment.vehicleAlarmId = Objects.requireNonNull(vehicleAlarmId, "vehicleAlarmId");
        attachment.attachmentType = Objects.requireNonNull(attachmentType, "attachmentType");
        attachment.channel = Objects.requireNonNull(channel, "channel");
        attachment.mediaFormat = Objects.requireNonNull(mediaFormat, "mediaFormat");
        attachment.sanitizedFilename = sanitizedFilename;
        if (sizeBytes != null && sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative");
        }
        attachment.sizeBytes = sizeBytes;
        attachment.payloadDigest = payloadDigest;
        attachment.status = Objects.requireNonNull(status, "status");
        attachment.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        return attachment;
    }

    public void markRequested() {
        requireStatus(Status.WAITING_MEDIA_SERVICE, Status.FAILED);
        status = Status.REQUESTED;
    }

    public void markUploading() {
        requireStatus(Status.REQUESTED);
        status = Status.UPLOADING;
    }

    public void markAvailable(String confirmedDigest, long confirmedSizeBytes, String mediaReference) {
        requireStatus(Status.UPLOADING);
        this.payloadDigest = Objects.requireNonNull(confirmedDigest, "confirmedDigest");
        if (confirmedSizeBytes < 0) {
            throw new IllegalArgumentException("confirmedSizeBytes must not be negative");
        }
        this.sizeBytes = confirmedSizeBytes;
        this.externalMediaReference = Objects.requireNonNull(mediaReference, "mediaReference");
        status = Status.AVAILABLE;
    }

    /** Fills the filename once when the metadata was not known at registration; never renames. */
    public void confirmSanitizedFilename(String confirmedFilename) {
        if (sanitizedFilename != null) {
            throw new IllegalStateException("sanitized filename is already recorded");
        }
        this.sanitizedFilename = Objects.requireNonNull(confirmedFilename, "confirmedFilename");
    }

    public void markFailed() {
        requireStatus(Status.WAITING_MEDIA_SERVICE, Status.REQUESTED, Status.UPLOADING);
        status = Status.FAILED;
    }

    public void markExpired() {
        requireStatus(Status.REQUESTED, Status.UPLOADING);
        status = Status.EXPIRED;
    }

    private void requireStatus(Status... allowed) {
        for (Status candidate : allowed) {
            if (status == candidate) {
                return;
            }
        }
        throw new IllegalStateException("attachment transition is not allowed from " + status);
    }

    public UUID getId() { return id; }
    public UUID getVehicleAlarmId() { return vehicleAlarmId; }
    public String getAttachmentType() { return attachmentType; }
    public String getChannel() { return channel; }
    public String getMediaFormat() { return mediaFormat; }
    public String getSanitizedFilename() { return sanitizedFilename; }
    public Long getSizeBytes() { return sizeBytes; }
    public String getPayloadDigest() { return payloadDigest; }
    public String getExternalMediaReference() { return externalMediaReference; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }

    public enum Status {
        WAITING_MEDIA_SERVICE,
        REQUESTED,
        UPLOADING,
        AVAILABLE,
        FAILED,
        EXPIRED
    }
}
