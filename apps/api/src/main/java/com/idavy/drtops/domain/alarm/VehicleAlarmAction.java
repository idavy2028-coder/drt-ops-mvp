package com.idavy.drtops.domain.alarm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vehicle_alarm_actions")
public class VehicleAlarmAction {
    @Id private UUID id;
    @Column(name = "vehicle_alarm_id", nullable = false) private UUID vehicleAlarmId;
    @Column(nullable = false) private String actionType;
    @Column private String fromStatus;
    @Column private String toStatus;
    @Column private String reason;
    @Column(name = "actor_id") private UUID actorId;
    @Column(nullable = false) private Instant occurredAt;
    @Column(nullable = false) private Instant createdAt;

    protected VehicleAlarmAction() { }

    static VehicleAlarmAction record(
            UUID alarmId,
            String actionType,
            VehicleAlarm.ProcessingStatus fromStatus,
            VehicleAlarm.ProcessingStatus toStatus,
            UUID actorId,
            String reason,
            Instant occurredAt) {
        VehicleAlarmAction action = new VehicleAlarmAction();
        action.id = UUID.randomUUID();
        action.vehicleAlarmId = alarmId;
        action.actionType = actionType;
        action.fromStatus = fromStatus.name();
        action.toStatus = toStatus.name();
        action.reason = reason;
        action.actorId = actorId;
        action.occurredAt = occurredAt;
        action.createdAt = occurredAt;
        return action;
    }

    public UUID getId() { return id; }
    public UUID getVehicleAlarmId() { return vehicleAlarmId; }
    public String getActionType() { return actionType; }
    public String getFromStatus() { return fromStatus; }
    public String getToStatus() { return toStatus; }
    public String getReason() { return reason; }
    public UUID getActorId() { return actorId; }
    public Instant getOccurredAt() { return occurredAt; }
}
