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
    @Id private UUID id;
    @Column(name = "vehicle_alarm_attachment_id", nullable = false) private UUID vehicleAlarmAttachmentId;
    @Column(nullable = false) private String controlMessageType;
    private Integer platformSerialNo;
    private Integer terminalSerialNo;
    private String externalTargetReference;
    @Column(nullable = false) private int retryCount;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status;
    private String errorCode;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;

    protected VehicleAlarmAttachmentTransfer() { }

    public enum Status {
        REQUESTED,
        UPLOADING,
        AVAILABLE,
        FAILED,
        EXPIRED
    }
}
