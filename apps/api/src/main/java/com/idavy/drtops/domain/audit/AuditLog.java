package com.idavy.drtops.domain.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    private UUID id;

    @Column(nullable = false, length = 60)
    private String entityType;

    @Column(nullable = false)
    private UUID entityId;

    @Column(nullable = false, length = 80)
    private String action;

    @Column(nullable = false, length = 40)
    private String actorType;

    @Column(nullable = false, length = 80)
    private String actorId;

    @Column(length = 300)
    private String reason;

    @Column(nullable = false, length = 1000)
    private String metadataJson;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    protected AuditLog() {
    }

    private AuditLog(
            String entityType,
            UUID entityId,
            String action,
            String actorType,
            String actorId,
            String reason,
            String metadataJson) {
        this.id = UUID.randomUUID();
        this.entityType = entityType;
        this.entityId = entityId;
        this.action = action;
        this.actorType = actorType;
        this.actorId = actorId;
        this.reason = reason;
        this.metadataJson = metadataJson;
        this.createdAt = OffsetDateTime.now();
    }

    public static AuditLog record(
            String entityType,
            UUID entityId,
            String action,
            String actorType,
            String actorId,
            String reason,
            String metadataJson) {
        return new AuditLog(entityType, entityId, action, actorType, actorId, reason, metadataJson);
    }

    public UUID getId() {
        return id;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public String getAction() {
        return action;
    }
}
