package com.idavy.drtops.domain.audit;

import com.idavy.drtops.auth.UserAccountRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final UserAccountRepository userAccountRepository;

    public AuditLogService(AuditLogRepository auditLogRepository, UserAccountRepository userAccountRepository) {
        this.auditLogRepository = auditLogRepository;
        this.userAccountRepository = userAccountRepository;
    }

    @Transactional(readOnly = true)
    public List<AuditLogView> list(UUID entityId) {
        List<AuditLog> logs = entityId == null ? auditLogRepository.findAllByOrderByCreatedAtAsc()
                : auditLogRepository.findByEntityIdOrderByCreatedAtAsc(entityId);
        Map<String, String> usernames = new HashMap<>();
        userAccountRepository.findAll().forEach(account -> usernames.put(account.getId().toString(), account.getUsername()));
        return logs.stream().map(log -> AuditLogView.from(log,
                "USER".equals(log.getActorType()) ? usernames.getOrDefault(log.getActorId(), log.getActorId()) : log.getActorId())).toList();
    }
}
