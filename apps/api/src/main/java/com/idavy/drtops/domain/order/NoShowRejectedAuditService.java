package com.idavy.drtops.domain.order;

import com.idavy.drtops.domain.audit.AuditLog;
import com.idavy.drtops.domain.audit.AuditLogRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NoShowRejectedAuditService {

    private final AuditLogRepository auditLogRepository;

    public NoShowRejectedAuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID actorId, UUID orderId, NoShowEligibility eligibility) {
        auditLogRepository.saveAndFlush(AuditLog.record(
                "RIDE_ORDER",
                orderId,
                "ORDER_NO_SHOW_REJECTED",
                "USER",
                actorId.toString(),
                eligibility.reasonMessage(),
                "{\"reasonCode\":\"" + eligibility.reasonCode() + "\"}"));
    }
}
