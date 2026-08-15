package com.idavy.drtops.domain.alarm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    @Column(name = "published_at") private Instant publishedAt;
    @Column(nullable = false) private Instant createdAt;
    protected VehicleAlarmOutboxEvent() { }
    static VehicleAlarmOutboxEvent pending(VehicleAlarm alarm, String eventType) {
        VehicleAlarmOutboxEvent event = new VehicleAlarmOutboxEvent();
        event.id = UUID.randomUUID(); event.vehicleAlarmId = alarm.getId(); event.eventType = eventType;
        event.payload = Snapshot.from(alarm, eventType).json();
        event.status = "PENDING"; event.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS); return event;
    }
    public UUID getVehicleAlarmId() { return vehicleAlarmId; }
    public UUID getId() { return id; }
    public String getEventType() { return eventType; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
    String getPayload() { return payload; }
    void markPublished(Instant at) { status = "PUBLISHED"; publishedAt = at; }

    record Snapshot(UUID publicId, String eventType, String status, int level, String module, Instant occurredAt) {
        static Snapshot from(VehicleAlarm alarm, String eventType) {
            return new Snapshot(alarm.getPublicId(), eventType, alarm.getProcessingStatus().name(),
                    alarm.getAlarmLevel(), alarm.getModule(), alarm.getOccurredAt());
        }

        private String json() {
            return "{\"publicId\":\"" + publicId + "\",\"eventType\":\"" + escape(eventType)
                    + "\",\"status\":\"" + status + "\",\"level\":" + level
                    + ",\"module\":\"" + escape(module) + "\",\"occurredAt\":\"" + occurredAt + "\"}";
        }

        private static String escape(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
