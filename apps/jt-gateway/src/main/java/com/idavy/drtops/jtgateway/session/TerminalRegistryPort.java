package com.idavy.drtops.jtgateway.session;

import java.util.UUID;

public interface TerminalRegistryPort {
    RegistrationDecision verifyRegistration(TerminalRegistrationIdentity identity);

    AuthenticationDecision verifyAuthentication(
            UUID terminalId, int tokenVersion, String presentedTokenSha256);

    void recordSessionAudit(SessionAuditIngress event);
}
