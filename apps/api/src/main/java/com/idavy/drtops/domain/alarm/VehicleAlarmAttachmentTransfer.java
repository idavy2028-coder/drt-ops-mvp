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
@Table(name = "vehicle_alarm_attachment_transfers")
public class VehicleAlarmAttachmentTransfer {
    @Id @Column(name = "id", nullable = false) private UUID id;
    @Column(name = "vehicle_alarm_attachment_id", nullable = false) private UUID vehicleAlarmAttachmentId;
    @Column(name = "control_message_type", nullable = false, length = 20) private String controlMessageType;
    @Column(name = "platform_serial_no", nullable = true) private Integer platformSerialNo;
    @Column(name = "terminal_serial_no", nullable = true) private Integer terminalSerialNo;
    @Column(name = "external_target_reference", nullable = true, length = 255)
    private String externalTargetReference;
    @Column(name = "retry_count", nullable = false) private int retryCount;
    @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false, length = 30) private Status status;
    @Column(name = "error_code", nullable = true, length = 80) private String errorCode;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected VehicleAlarmAttachmentTransfer() { }

    public enum Status {
        REQUESTED,
        UPLOADING,
        AVAILABLE,
        FAILED,
        EXPIRED
    }
}
