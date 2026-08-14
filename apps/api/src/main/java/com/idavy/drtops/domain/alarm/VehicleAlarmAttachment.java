package com.idavy.drtops.domain.alarm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
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

    public enum Status {
        WAITING_MEDIA_SERVICE,
        REQUESTED,
        UPLOADING,
        AVAILABLE,
        FAILED,
        EXPIRED
    }
}
