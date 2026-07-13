package com.idavy.drtops.domain.audit;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AuditLogView(
        UUID id,
        String entityType,
        UUID entityId,
        String action,
        String actorType,
        String actorId,
        String actorDisplayName,
        String reason,
        String metadataJson,
        OffsetDateTime createdAt) {

    static AuditLogView from(AuditLog log, String actorDisplayName) {
        return new AuditLogView(log.getId(), log.getEntityType(), log.getEntityId(), log.getAction(), log.getActorType(),
                log.getActorId(), actorDisplayName, log.getReason(), log.getMetadataJson(), log.getCreatedAt());
    }
}
