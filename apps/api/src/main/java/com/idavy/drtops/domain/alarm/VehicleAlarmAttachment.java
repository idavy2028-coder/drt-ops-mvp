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
    @Id private UUID id;
    @Column(name = "vehicle_alarm_id", nullable = false) private UUID vehicleAlarmId;
    @Column(nullable = false) private String attachmentType;
    @Column(nullable = false) private String channel;
    @Column(nullable = false) private String mediaFormat;
    private String sanitizedFilename;
    private Long sizeBytes;
    @Column(length = 64, columnDefinition = "char(64)") private String payloadDigest;
    private String externalMediaReference;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status;
    @Column(nullable = false) private Instant createdAt;

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
