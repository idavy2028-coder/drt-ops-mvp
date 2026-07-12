package com.idavy.drtops.auth;

import com.idavy.drtops.domain.audit.AuditLog;
import com.idavy.drtops.domain.audit.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthAuditService {

    private final AuditLogRepository auditLogs;

    public AuthAuditService(AuditLogRepository auditLogs) {
        this.auditLogs = auditLogs;
    }

    public void recordUserChange(java.util.UUID actorId, UserAccount user, String action) {
        auditLogs.save(AuditLog.record(
                "USER_ACCOUNT",
                user.getId(),
                action,
                "USER",
                actorId.toString(),
                null,
                "{}"));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAuthenticationFailure(UserAccount user, String action) {
        auditLogs.save(AuditLog.record(
                "USER_ACCOUNT",
                user.getId(),
                action,
                "ANONYMOUS",
                "anonymous",
                "invalid authentication attempt",
                "{}"));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAuthorizationDenied(java.util.UUID actorId) {
        auditLogs.save(AuditLog.record(
                "USER_ACCOUNT",
                actorId,
                "AUTHORIZATION_DENIED",
                "USER",
                actorId.toString(),
                "access denied",
                "{}"));
    }
}
