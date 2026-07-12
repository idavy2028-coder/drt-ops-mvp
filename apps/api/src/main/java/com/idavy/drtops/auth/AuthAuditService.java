package com.idavy.drtops.auth;

import com.idavy.drtops.domain.audit.AuditLog;
import com.idavy.drtops.domain.audit.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthAuditService {

    private static final java.util.UUID UNKNOWN_LOGIN_ENTITY_ID =
            java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");

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
        recordAuthenticationFailure(user.getId(), action);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUnknownAuthenticationFailure() {
        recordAuthenticationFailure(UNKNOWN_LOGIN_ENTITY_ID, "AUTH_LOGIN_FAILED");
    }

    private void recordAuthenticationFailure(java.util.UUID userId, String action) {
        auditLogs.save(AuditLog.record(
                "USER_ACCOUNT",
                userId,
                action,
                "ANONYMOUS",
                "anonymous",
                "invalid authentication attempt",
                "{}"));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAuthorizationDenied(java.util.UUID actorId, java.util.UUID entityId) {
        auditLogs.save(AuditLog.record(
                "USER_ACCOUNT",
                entityId,
                "AUTHORIZATION_DENIED",
                "USER",
                actorId.toString(),
                "access denied",
                "{}"));
    }
}
