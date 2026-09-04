package com.idavy.drtops.jtgateway.session;

import com.idavy.drtops.jt.protocol.codec.ProtocolVersion;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface TerminalRegistryPort {
    RegistrationDecision verifyRegistration(TerminalRegistrationIdentity identity);

    AuthenticationDecision verifyAuthentication(
            UUID terminalId,
            int tokenVersion,
            String presentedTokenSha256,
            UUID connectionId);

    AuthenticationDecision verifyAuthenticationByIdentity(
            ProtocolVersion protocolVersion,
            String terminalPhone,
            String presentedTokenSha256,
            UUID connectionId);

    Optional<SessionLeaseGrant> renewSessionLease(SessionLeaseOwner owner);

    SessionLeaseReleaseResult releaseSessionLease(
            SessionLeaseOwner owner, String reasonCode);

    void recordSessionAudit(SessionAuditIngress event);

    record SessionLeaseOwner(
            UUID terminalId,
            String gatewayInstance,
            UUID connectionId,
            int tokenVersion,
            long leaseGeneration) {
        @Override
        public String toString() {
            return "SessionLeaseOwner[terminalId=" + terminalId
                    + ", tokenVersion=" + tokenVersion + "]";
        }
    }

    record SessionLeaseGrant(
            SessionLeaseOwner owner,
            Instant authenticatedAt,
            Instant lastValidMessageAt,
            Instant expiresAt) {
    }

    record SessionLeaseReleaseResult(String status) {
    }
}
