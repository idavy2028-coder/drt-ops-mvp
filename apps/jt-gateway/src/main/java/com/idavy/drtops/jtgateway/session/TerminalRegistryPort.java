package com.idavy.drtops.jtgateway.session;

import com.idavy.drtops.jt.protocol.codec.ProtocolVersion;
import java.util.UUID;

public interface TerminalRegistryPort {
    RegistrationDecision verifyRegistration(TerminalRegistrationIdentity identity);

    AuthenticationDecision verifyAuthentication(
            UUID terminalId, int tokenVersion, String presentedTokenSha256);

    default AuthenticationDecision verifyAuthenticationByIdentity(
            ProtocolVersion protocolVersion,
            String terminalPhone,
            String presentedTokenSha256) {
        return AuthenticationDecision.rejected(AuthenticationRejection.REGISTRATION_REQUIRED);
    }

    void recordSessionAudit(SessionAuditIngress event);
}
