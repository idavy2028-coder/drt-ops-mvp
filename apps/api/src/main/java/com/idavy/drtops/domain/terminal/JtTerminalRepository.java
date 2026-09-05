package com.idavy.drtops.domain.terminal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JtTerminalRepository extends JpaRepository<JtTerminal, UUID> {
    Optional<JtTerminal> findByTerminalCode(String terminalCode);
    Optional<JtTerminal> findByTerminalPhone(String terminalPhone);
    List<JtTerminal> findAllByTerminalCode(String terminalCode);
    List<JtTerminal> findAllByTerminalPhoneIdentity(String terminalPhoneIdentity);

    default List<JtTerminal> findAllBySemanticPhone(
            String terminalPhone, String protocolVersion) {
        String canonicalProtocol = TerminalPhoneIdentity.canonicalProtocolVersion(protocolVersion);
        if (canonicalProtocol == null) {
            return List.of();
        }
        String identity = TerminalPhoneIdentity.canonicalForPersistence(
                terminalPhone, canonicalProtocol);
        return findAllByTerminalPhoneIdentity(identity);
    }

    static String canonicalProtocolVersion(String protocolVersion) {
        return TerminalPhoneIdentity.canonicalProtocolVersion(protocolVersion);
    }
}
