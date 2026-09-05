package com.idavy.drtops.jtgateway.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.jt.protocol.codec.ProtocolVersion;
import com.idavy.drtops.jtgateway.ingress.GatewayIngressBuffer;
import com.idavy.drtops.jtgateway.ingress.GatewayIngressEnvelope;
import com.idavy.drtops.jtgateway.ingress.IngressKind;
import com.idavy.drtops.jtgateway.ingress.OperationsApiStatus;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** HTTP adapter for the operations API terminal registry; all decisions fail closed. */
public final class OperationsTerminalRegistryClient implements TerminalRegistryPort {
    private static final String VERSION_HEADER = "X-Service-Credential-Version";

    private final RestClient client;
    private final String credential;
    private final int credentialVersion;
    private final String gatewayInstance;
    private final SecureRandom secureRandom;
    private final RegistrationAuthenticationTokenPolicy authenticationTokens;
    private final OperationsApiStatus apiStatus;
    private final GatewayIngressBuffer auditBuffer;
    private final ObjectMapper objectMapper;

    public OperationsTerminalRegistryClient(
            RestClient.Builder builder,
            String baseUrl,
            String credential,
            int credentialVersion,
            String gatewayInstance,
            SecureRandom secureRandom,
            OperationsApiStatus apiStatus,
            GatewayIngressBuffer auditBuffer,
            ObjectMapper objectMapper) {
        this(builder, baseUrl, credential, credentialVersion, gatewayInstance, secureRandom,
                apiStatus, auditBuffer, objectMapper,
                RegistrationAuthenticationTokenPolicy.fromCommaSeparated(""));
    }

    public OperationsTerminalRegistryClient(
            RestClient.Builder builder,
            String baseUrl,
            String credential,
            int credentialVersion,
            String gatewayInstance,
            SecureRandom secureRandom,
            OperationsApiStatus apiStatus,
            GatewayIngressBuffer auditBuffer,
            ObjectMapper objectMapper,
            RegistrationAuthenticationTokenPolicy authenticationTokens) {
        this.client = Objects.requireNonNull(builder, "builder").baseUrl(baseUrl).build();
        this.credential = requireText(credential, "credential");
        if (credentialVersion < 1) {
            throw new IllegalArgumentException("credentialVersion must be positive");
        }
        this.credentialVersion = credentialVersion;
        this.gatewayInstance = requireText(gatewayInstance, "gatewayInstance");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
        this.authenticationTokens = Objects.requireNonNull(authenticationTokens, "authenticationTokens");
        this.apiStatus = Objects.requireNonNull(apiStatus, "apiStatus");
        this.auditBuffer = Objects.requireNonNull(auditBuffer, "auditBuffer");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public RegistrationDecision verifyRegistration(TerminalRegistrationIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        RegistrationStage stage = RegistrationStage.VERIFY;
        try {
            RegistrationResponse response = authenticatedPost("/internal/jt-gateway/registrations/verify")
                    .body(new RegistrationRequest(
                            identity.terminalNumber(), identity.terminalCode(), identity.manufacturerId(),
                            identity.model(), identity.vehicleIdentifier(), identity.protocolVersion().name()))
                    .retrieve()
                    .body(RegistrationResponse.class);
            RegistrationPayload approved = response == null ? null : response.data();
            TerminalSessionContext context = approved == null ? null : approved.context();
            if (registrationTransportMismatch(
                    approved, context, identity.protocolVersion())) {
                apiStatus.success(OperationsApiStatus.Source.REGISTRY, "REGISTRATION_VERIFY");
                return RegistrationDecision.rejected(
                        RegistrationRejection.SESSION_TRANSPORT_PROFILE_MISMATCH);
            }
            if (approved == null || !approved.approved()
                    || !registrationContextIsConsistent(
                            approved, context, identity.protocolVersion())) {
                if (approved == null) {
                    apiStatus.failure(OperationsApiStatus.Source.REGISTRY, "REGISTRATION_VERIFY");
                } else {
                    apiStatus.success(OperationsApiStatus.Source.REGISTRY, "REGISTRATION_VERIFY");
                }
                return RegistrationDecision.rejected(RegistrationRejection.fromReasonCode(
                        approved == null ? null : approved.reasonCode()));
            }
            apiStatus.success(OperationsApiStatus.Source.REGISTRY, "REGISTRATION_VERIFY");
            byte[] token = authenticationTokens.issue(identity.model(), secureRandom);
            try {
                String digest = sha256(token);
                stage = RegistrationStage.COMPLETE;
                authenticatedPost(
                                "/internal/jt-gateway/registrations/{terminalId}/complete",
                                context.terminalId())
                        .body(new RegistrationCompletionRequest(
                                context.tokenVersion(), digest, gatewayInstance))
                        .retrieve()
                        .toBodilessEntity();
                apiStatus.success(OperationsApiStatus.Source.REGISTRY, "REGISTRATION_COMPLETE");
                return RegistrationDecision.approved(
                        context, token, digest);
            } finally {
                Arrays.fill(token, (byte) 0);
            }
        } catch (RestClientResponseException responseFailure) {
            apiStatus.failure(OperationsApiStatus.Source.REGISTRY, "REGISTRATION_" + stage.name());
            return RegistrationDecision.rejected(classifyRegistrationHttpFailure(
                    stage, responseFailure.getStatusCode().value()));
        } catch (RestClientException unavailable) {
            apiStatus.failure(OperationsApiStatus.Source.REGISTRY, "REGISTRATION");
            return RegistrationDecision.rejected(registrationUnavailable(stage));
        } catch (IllegalArgumentException invalidResponse) {
            apiStatus.failure(OperationsApiStatus.Source.REGISTRY, "REGISTRATION");
            return RegistrationDecision.rejected(RegistrationRejection.NOT_PREPROVISIONED);
        }
    }

    private static RegistrationRejection classifyRegistrationHttpFailure(
            RegistrationStage stage, int statusCode) {
        if (stage == RegistrationStage.VERIFY && statusCode == 400) {
            return RegistrationRejection.REGISTRATION_VERIFY_BAD_REQUEST;
        }
        if (stage == RegistrationStage.VERIFY && (statusCode == 401 || statusCode == 403)) {
            return RegistrationRejection.REGISTRATION_VERIFY_UNAUTHORIZED;
        }
        if (stage == RegistrationStage.VERIFY && statusCode == 409) {
            return RegistrationRejection.REGISTRATION_VERIFY_CONFLICT;
        }
        if (stage == RegistrationStage.VERIFY && statusCode >= 500) {
            return RegistrationRejection.REGISTRATION_VERIFY_UNAVAILABLE;
        }
        if (stage == RegistrationStage.COMPLETE && statusCode == 400) {
            return RegistrationRejection.REGISTRATION_COMPLETE_BAD_REQUEST;
        }
        if (stage == RegistrationStage.COMPLETE && (statusCode == 401 || statusCode == 403)) {
            return RegistrationRejection.REGISTRATION_COMPLETE_UNAUTHORIZED;
        }
        if (stage == RegistrationStage.COMPLETE && statusCode == 409) {
            return RegistrationRejection.REGISTRATION_COMPLETE_CONFLICT;
        }
        if (stage == RegistrationStage.COMPLETE && statusCode >= 500) {
            return RegistrationRejection.REGISTRATION_COMPLETE_UNAVAILABLE;
        }
        return RegistrationRejection.NOT_PREPROVISIONED;
    }

    private static RegistrationRejection registrationUnavailable(RegistrationStage stage) {
        return stage == RegistrationStage.VERIFY
                ? RegistrationRejection.REGISTRATION_VERIFY_UNAVAILABLE
                : RegistrationRejection.REGISTRATION_COMPLETE_UNAVAILABLE;
    }

    @Override
    public AuthenticationDecision verifyAuthentication(
            UUID terminalId,
            int tokenVersion,
            String presentedTokenSha256,
            UUID connectionId) {
        AuthenticationDecision decision = verifyAuthentication(
                "/internal/jt-gateway/authentications/verify",
                new AuthenticationRequest(
                        terminalId, tokenVersion, presentedTokenSha256,
                        gatewayInstance, connectionId),
                connectionId);
        return decision.approved()
                        && (!decision.context().terminalId().equals(terminalId)
                                || decision.context().tokenVersion() != tokenVersion)
                ? AuthenticationDecision.rejectedWithCleanup(
                        AuthenticationRejection.TOKEN_MISMATCH,
                        decision.lease().owner())
                : decision;
    }

    @Override
    public AuthenticationDecision verifyAuthenticationByIdentity(
            ProtocolVersion protocolVersion,
            String terminalPhone,
            String presentedTokenSha256,
            UUID connectionId) {
        Objects.requireNonNull(protocolVersion, "protocolVersion");
        return verifyAuthentication(
                "/internal/jt-gateway/authentications/verify-by-identity",
                new IdentityAuthenticationRequest(
                        protocolVersion.name(), requireText(terminalPhone, "terminalPhone"),
                        presentedTokenSha256, gatewayInstance, connectionId),
                connectionId);
    }

    private AuthenticationDecision verifyAuthentication(
            String path, Object request, UUID connectionId) {
        try {
            AuthenticationResponse response = authenticatedPost(path)
                    .body(request)
                    .retrieve()
                    .body(AuthenticationResponse.class);
            if (response == null || response.data() == null) {
                apiStatus.failure(OperationsApiStatus.Source.REGISTRY, "AUTHENTICATION_VERIFY");
            } else {
                apiStatus.success(OperationsApiStatus.Source.REGISTRY, "AUTHENTICATION_VERIFY");
            }
            AuthenticationPayload payload = response == null ? null : response.data();
            boolean accepted = payload != null
                            && payload.approved()
                            && payload.context() != null
                            && leaseMatches(payload.lease(), payload.context(), connectionId)
                            && payload.reasonCode() == null;
            if (accepted) {
                return AuthenticationDecision.allow(payload.context(), payload.lease());
            }
            if (payload != null && payload.approved()
                    && payload.lease() != null && payload.lease().owner() != null) {
                return AuthenticationDecision.rejectedWithCleanup(
                        AuthenticationRejection.TOKEN_MISMATCH,
                        payload.lease().owner());
            }
            return AuthenticationDecision.rejected(AuthenticationRejection.TOKEN_MISMATCH);
        } catch (RestClientException unavailable) {
            apiStatus.failure(OperationsApiStatus.Source.REGISTRY, "AUTHENTICATION_VERIFY");
            return AuthenticationDecision.rejected(AuthenticationRejection.TOKEN_MISMATCH);
        } catch (IllegalArgumentException invalidResponse) {
            apiStatus.failure(OperationsApiStatus.Source.REGISTRY, "AUTHENTICATION_VERIFY");
            return AuthenticationDecision.rejected(AuthenticationRejection.TOKEN_MISMATCH);
        }
    }

    @Override
    public Optional<SessionLeaseGrant> renewSessionLease(SessionLeaseOwner owner) {
        try {
            SessionLeaseResponse response = authenticatedPost(
                            "/internal/jt-gateway/session-leases/renew")
                    .body(Objects.requireNonNull(owner, "owner"))
                    .retrieve()
                    .body(SessionLeaseResponse.class);
            return response == null ? Optional.empty() : Optional.ofNullable(response.data());
        } catch (RestClientResponseException conflict) {
            return Optional.empty();
        } catch (RestClientException | IllegalArgumentException unavailable) {
            return Optional.empty();
        }
    }

    @Override
    public SessionLeaseReleaseResult releaseSessionLease(
            SessionLeaseOwner owner, String reasonCode) {
        try {
            SessionLeaseReleaseResponse response = authenticatedPost(
                            "/internal/jt-gateway/session-leases/release")
                    .body(new SessionLeaseReleaseRequest(
                            Objects.requireNonNull(owner, "owner"),
                            requireText(reasonCode, "reasonCode")))
                    .retrieve()
                    .body(SessionLeaseReleaseResponse.class);
            return response == null || response.data() == null
                    ? new SessionLeaseReleaseResult("STALE_OWNER_IGNORED")
                    : response.data();
        } catch (RestClientException | IllegalArgumentException unavailable) {
            return new SessionLeaseReleaseResult("STALE_OWNER_IGNORED");
        }
    }

    @Override
    public void recordSessionAudit(SessionAuditIngress event) {
        Objects.requireNonNull(event, "event");
        AuditMapping mapping = auditMapping(event.type());
        try {
            UUID idempotencyKey = UUID.randomUUID();
            AuditRequest audit = new AuditRequest(
                    idempotencyKey, event.terminalId(), null, mapping.eventType(), mapping.result(),
                    event.reasonCode(), null, null, null, event.remoteAddress(),
                    event.occurredAt().atOffset(ZoneOffset.UTC), gatewayInstance);
            GatewayIngressBuffer.WriteResult result = auditBuffer.append(new GatewayIngressEnvelope(
                    1,
                    idempotencyKey,
                    IngressKind.SESSION_AUDIT,
                    event.occurredAt(),
                    objectMapper.writeValueAsString(audit)));
            if (result == GatewayIngressBuffer.WriteResult.UNAVAILABLE) {
                throw new IllegalStateException("session audit buffer is unavailable");
            }
        } catch (JsonProcessingException serializationFailure) {
            throw new IllegalStateException("session audit serialization failed", serializationFailure);
        }
    }

    private RestClient.RequestBodySpec authenticatedPost(String path, Object... variables) {
        return client.post()
                .uri(path, variables)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential)
                .header(VERSION_HEADER, Integer.toString(credentialVersion));
    }

    private static boolean registrationContextIsConsistent(
            RegistrationPayload payload,
            TerminalSessionContext context,
            ProtocolVersion requestedProtocol) {
        return context != null
                && payload.reasonCode() == null
                && context.protocolProfile().transportProfile()
                        .equals(requestedProtocol.name())
                && payload.contractVersion() == context.contractVersion()
                && Objects.equals(payload.terminalId(), context.terminalId())
                && Objects.equals(payload.onboardSystemId(), context.onboardSystemId())
                && Objects.equals(payload.vehicleId(), context.vehicleId())
                && payload.onboardConfigurationVersion()
                        == context.onboardConfigurationVersion()
                && Objects.equals(payload.roles(), context.roles())
                && Objects.equals(
                        payload.sourceCoordinateSystem(), context.sourceCoordinateSystem())
                && Objects.equals(payload.protocolProfile(), context.protocolProfile())
                && Objects.equals(
                        payload.activeSafetyStandard(), context.activeSafetyStandard())
                && Objects.equals(
                        payload.activeSafetyModules(), context.activeSafetyModules())
                && payload.tokenVersion() == context.tokenVersion();
    }

    private static boolean registrationTransportMismatch(
            RegistrationPayload payload,
            TerminalSessionContext context,
            ProtocolVersion requestedProtocol) {
        return payload != null
                && payload.approved()
                && payload.reasonCode() == null
                && context != null
                && !context.protocolProfile().transportProfile()
                        .equals(requestedProtocol.name());
    }

    private boolean leaseMatches(
            SessionLeaseGrant lease,
            TerminalSessionContext context,
            UUID connectionId) {
        if (lease == null || lease.owner() == null || context == null) {
            return false;
        }
        SessionLeaseOwner owner = lease.owner();
        return Objects.equals(owner.terminalId(), context.terminalId())
                && Objects.equals(owner.connectionId(), connectionId)
                && Objects.equals(owner.gatewayInstance(), gatewayInstance)
                && owner.tokenVersion() == context.tokenVersion()
                && owner.leaseGeneration() > 0
                && lease.authenticatedAt() != null
                && lease.lastValidMessageAt() != null
                && lease.expiresAt() != null
                && lease.expiresAt().isAfter(lease.lastValidMessageAt());
    }

    private static AuditMapping auditMapping(SessionAuditType type) {
        return switch (type) {
            case REGISTRATION_ACCEPTED -> new AuditMapping("REGISTERED", "ACCEPTED");
            case REGISTRATION_REJECTED -> new AuditMapping("REGISTERED", "REJECTED");
            case AUTHENTICATION_ACCEPTED -> new AuditMapping("AUTHENTICATED", "ACCEPTED");
            case AUTHENTICATION_REJECTED, AUTHENTICATION_LOCKED, AUTHENTICATION_TIMEOUT,
                    PRE_AUTH_MESSAGE_REJECTED, SESSION_IDENTITY_MISMATCH ->
                    new AuditMapping("PROTOCOL_REJECTED", "REJECTED");
            case SESSION_TAKEN_OVER -> new AuditMapping("DUPLICATE_LOGIN", "APPLIED");
            case SESSION_OFFLINE -> new AuditMapping("OFFLINE", "APPLIED");
        };
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record RegistrationRequest(
            String terminalPhone, String terminalCode, String manufacturerId, String model,
            String vehicleIdentifier, String protocolVersion) { }

    private record RegistrationCompletionRequest(
            int tokenVersion, String tokenSha256, String gatewayInstance) { }

    private record RegistrationResponse(RegistrationPayload data) { }

    private record RegistrationPayload(
            boolean approved,
            int contractVersion,
            UUID terminalId,
            UUID onboardSystemId,
            UUID vehicleId,
            long onboardConfigurationVersion,
            Set<String> roles,
            String sourceCoordinateSystem,
            TerminalSessionContext.SessionProtocolProfile protocolProfile,
            String activeSafetyStandard,
            List<String> activeSafetyModules,
            int tokenVersion,
            TerminalSessionContext context,
            String reasonCode) { }

    private record AuthenticationRequest(
            UUID terminalId,
            int tokenVersion,
            String tokenSha256,
            String gatewayInstance,
            UUID connectionId) { }

    private record IdentityAuthenticationRequest(
            String protocolVersion,
            String terminalPhone,
            String tokenSha256,
            String gatewayInstance,
            UUID connectionId) { }

    private record AuthenticationResponse(AuthenticationPayload data) { }

    private record AuthenticationPayload(
            boolean approved,
            TerminalSessionContext context,
            SessionLeaseGrant lease,
            String reasonCode) { }

    private record SessionLeaseResponse(SessionLeaseGrant data) { }

    private record SessionLeaseReleaseRequest(
            SessionLeaseOwner owner, String reasonCode) { }

    private record SessionLeaseReleaseResponse(
            SessionLeaseReleaseResult data) { }

    private record AuditRequest(
            UUID idempotencyKey, UUID terminalId, UUID vehicleId, String eventType, String result, String reasonCode,
            String protocolVersion, Integer messageId, String payloadDigest, String remoteAddress,
            java.time.OffsetDateTime occurredAt, String gatewayInstance) { }

    private record AuditMapping(String eventType, String result) { }

    private enum RegistrationStage {
        VERIFY,
        COMPLETE
    }
}
