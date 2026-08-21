package com.idavy.drtops.domain.terminal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JtGatewayAuditEventRepository extends JpaRepository<JtGatewayAuditEvent, UUID> {
    java.util.List<JtGatewayAuditEvent> findByTerminalIdOrderByOccurredAtDesc(UUID terminalId);
}
