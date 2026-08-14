package com.idavy.drtops.domain.alarm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "vehicle_alarm_outbox")
public class VehicleAlarmOutboxEvent {
    @Id private UUID id;
    @Column(nullable = false) private UUID vehicleAlarmId;
    @Column(nullable = false) private String eventType;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private String payload;
    @Column(nullable = false) private String status;
    @Column(nullable = false) private Instant createdAt;
    protected VehicleAlarmOutboxEvent() { }
    static VehicleAlarmOutboxEvent pending(UUID alarmId, String eventType) {
        VehicleAlarmOutboxEvent event = new VehicleAlarmOutboxEvent();
        event.id = UUID.randomUUID(); event.vehicleAlarmId = alarmId; event.eventType = eventType;
        event.payload = "{}"; event.status = "PENDING"; event.createdAt = Instant.now(); return event;
    }
}
