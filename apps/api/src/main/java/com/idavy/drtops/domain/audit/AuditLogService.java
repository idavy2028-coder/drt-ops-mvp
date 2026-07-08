package com.idavy.drtops.domain.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    public AuditLogService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(readOnly = true)
    public List<AuditLog> list(UUID entityId) {
        if (entityId == null) {
            return auditLogRepository.findAllByOrderByCreatedAtAsc();
        }
        return auditLogRepository.findByEntityIdOrderByCreatedAtAsc(entityId);
    }
}
