package com.idavy.drtops.jtgateway.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.jtgateway.ingress.GatewayIngressBuffer;
import com.idavy.drtops.jtgateway.ingress.GatewayIngressEnvelope;
import com.idavy.drtops.jtgateway.ingress.IngressKind;
import com.idavy.drtops.jtgateway.ingress.OperationsApiStatus;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** HTTP adapter for the operations API terminal registry; all decisions fail closed. */
public final class OperationsTerminalRegistryClient implements TerminalRegistryPort {
    private static final String VERSION_HEADER = "X-Service-Credential-Version";

    private final RestClient client;
    private final String credential;
    private final int credentialVersion;
    private final String gatewayInstance;
    private final SecureRandom secureRandom;
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
        this.client = Objects.requireNonNull(builder, "builder").baseUrl(baseUrl).build();
        this.credential = requireText(credential, "credential");
        if (credentialVersion < 1) {
            throw new IllegalArgumentException("credentialVersion must be positive");
        }
        this.credentialVersion = credentialVersion;
        this.gatewayInstance = requireText(gatewayInstance, "gatewayInstance");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
        this.apiStatus = Objects.requireNonNull(apiStatus, "apiStatus");
        this.auditBuffer = Objects.requireNonNull(auditBuffer, "auditBuffer");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public RegistrationDecision verifyRegistration(TerminalRegistrationIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        try {
            RegistrationResponse response = authenticatedPost("/internal/jt-gateway/registrations/verify")
                    .body(new RegistrationRequest(
                            identity.terminalNumber(), identity.terminalCode(), identity.manufacturerId(),
                            identity.model(), identity.vehicleIdentifier(), identity.protocolVersion().name()))
                    .retrieve()
                    .body(RegistrationResponse.class);
            RegistrationPayload approved = response == null ? null : response.data();
            if (approved == null || !approved.approved() || approved.terminalId() == null
                    || approved.vehicleId() == null || approved.tokenVersion() < 1) {
                if (approved == null) {
                    apiStatus.failure(OperationsApiStatus.Source.REGISTRY, "REGISTRATION_VERIFY");
                } else {
                    apiStatus.success(OperationsApiStatus.Source.REGISTRY, "REGISTRATION_VERIFY");
                }
                return RegistrationDecision.rejected(RegistrationRejection.NOT_PREPROVISIONED);
            }
            apiStatus.success(OperationsApiStatus.Source.REGISTRY, "REGISTRATION_VERIFY");
            byte[] entropy = new byte[32];
            secureRandom.nextBytes(entropy);
            byte[] token = Base64.getUrlEncoder().withoutPadding().encode(entropy);
            Arrays.fill(entropy, (byte) 0);
            String digest = sha256(token);
            try {
                authenticatedPost("/internal/jt-gateway/registrations/{terminalId}/complete", approved.terminalId())
                        .body(new RegistrationCompletionRequest(
                                approved.tokenVersion(), digest, gatewayInstance))
                        .retrieve()
                        .toBodilessEntity();
                apiStatus.success(OperationsApiStatus.Source.REGISTRY, "REGISTRATION_COMPLETE");
                return RegistrationDecision.approved(
                        approved.terminalId(), approved.vehicleId(), approved.sourceCoordinateSystem(),
                        approved.activeSafetyStandard(), approved.activeSafetyModules(),
                        approved.tokenVersion(), token, digest);
            } finally {
                Arrays.fill(token, (byte) 0);
            }
        } catch (RestClientException | IllegalArgumentException unavailable) {
            apiStatus.failure(OperationsApiStatus.Source.REGISTRY, "REGISTRATION");
            return RegistrationDecision.rejected(RegistrationRejection.NOT_PREPROVISIONED);
        }
    }

    @Override
    public AuthenticationDecision verifyAuthentication(
            UUID terminalId, int tokenVersion, String presentedTokenSha256) {
        try {
            AuthenticationResponse response = authenticatedPost(
                            "/internal/jt-gateway/authentications/verify")
                    .body(new AuthenticationRequest(
                            terminalId, tokenVersion, presentedTokenSha256, gatewayInstance))
                    .retrieve()
                    .body(AuthenticationResponse.class);
            if (response == null || response.data() == null) {
                apiStatus.failure(OperationsApiStatus.Source.REGISTRY, "AUTHENTICATION_VERIFY");
            } else {
                apiStatus.success(OperationsApiStatus.Source.REGISTRY, "AUTHENTICATION_VERIFY");
            }
            return response != null && response.data() != null && response.data().approved()
                    ? AuthenticationDecision.allow()
                    : AuthenticationDecision.rejected(AuthenticationRejection.TOKEN_MISMATCH);
        } catch (RestClientException unavailable) {
            apiStatus.failure(OperationsApiStatus.Source.REGISTRY, "AUTHENTICATION_VERIFY");
            return AuthenticationDecision.rejected(AuthenticationRejection.TOKEN_MISMATCH);
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
            boolean approved, UUID terminalId, UUID vehicleId, String sourceCoordinateSystem,
            String activeSafetyStandard, List<String> activeSafetyModules, int tokenVersion,
            String reasonCode) { }

    private record AuthenticationRequest(
            UUID terminalId, int tokenVersion, String tokenSha256, String gatewayInstance) { }

    private record AuthenticationResponse(AuthenticationPayload data) { }

    private record AuthenticationPayload(boolean approved, String reasonCode) { }

    private record AuditRequest(
            UUID idempotencyKey, UUID terminalId, UUID vehicleId, String eventType, String result, String reasonCode,
            String protocolVersion, Integer messageId, String payloadDigest, String remoteAddress,
            java.time.OffsetDateTime occurredAt, String gatewayInstance) { }

    private record AuditMapping(String eventType, String result) { }
}
