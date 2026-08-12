package com.idavy.drtops.domain.terminal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JtTerminalRepository extends JpaRepository<JtTerminal, UUID> {
    Optional<JtTerminal> findByTerminalCode(String terminalCode);
    Optional<JtTerminal> findByTerminalPhone(String terminalPhone);
}
