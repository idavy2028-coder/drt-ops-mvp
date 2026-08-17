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

    public static VehicleAlarmAttachmentTransfer requested(
            UUID vehicleAlarmAttachmentId,
            String controlMessageType,
            Integer platformSerialNo,
            String externalTargetReference,
            Instant createdAt) {
        VehicleAlarmAttachmentTransfer transfer = new VehicleAlarmAttachmentTransfer();
        transfer.id = UUID.randomUUID();
        transfer.vehicleAlarmAttachmentId =
                Objects.requireNonNull(vehicleAlarmAttachmentId, "vehicleAlarmAttachmentId");
        transfer.controlMessageType = Objects.requireNonNull(controlMessageType, "controlMessageType");
        transfer.platformSerialNo = platformSerialNo;
        transfer.externalTargetReference = externalTargetReference;
        transfer.retryCount = 0;
        transfer.status = Status.REQUESTED;
        transfer.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        transfer.updatedAt = createdAt;
        return transfer;
    }

    public void markUploading(Integer terminalSerialNo) {
        requireStatus(Status.REQUESTED);
        this.terminalSerialNo = terminalSerialNo;
        transition(Status.UPLOADING);
    }

    public void markAvailable() {
        requireStatus(Status.REQUESTED, Status.UPLOADING);
        transition(Status.AVAILABLE);
    }

    public void markFailed(String failureCode) {
        requireStatus(Status.REQUESTED, Status.UPLOADING);
        this.errorCode = Objects.requireNonNull(failureCode, "failureCode");
        transition(Status.FAILED);
    }

    public void markExpired() {
        requireStatus(Status.REQUESTED, Status.UPLOADING);
        transition(Status.EXPIRED);
    }

    private void transition(Status target) {
        this.status = target;
        this.updatedAt = Instant.now();
    }

    private void requireStatus(Status... allowed) {
        for (Status candidate : allowed) {
            if (status == candidate) {
                return;
            }
        }
        throw new IllegalStateException("transfer transition is not allowed from " + status);
    }

    public UUID getId() { return id; }
    public UUID getVehicleAlarmAttachmentId() { return vehicleAlarmAttachmentId; }
    public String getControlMessageType() { return controlMessageType; }
    public Integer getPlatformSerialNo() { return platformSerialNo; }
    public Integer getTerminalSerialNo() { return terminalSerialNo; }
    public String getExternalTargetReference() { return externalTargetReference; }
    public int getRetryCount() { return retryCount; }
    public Status getStatus() { return status; }
    public String getErrorCode() { return errorCode; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public enum Status {
        REQUESTED,
        UPLOADING,
        AVAILABLE,
        FAILED,
        EXPIRED
    }
}
