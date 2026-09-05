package com.idavy.drtops.domain.terminal;

import com.idavy.drtops.common.ApiResponse;
import com.idavy.drtops.domain.onboard.OnboardDeviceRoleAssignment.Role;
import com.idavy.drtops.domain.onboard.OnboardRegistrationResolver;
import com.idavy.drtops.domain.terminal.JtTerminalSessionLeaseService.SessionLeaseGrant;
import com.idavy.drtops.domain.terminal.JtTerminalSessionLeaseService.SessionLeaseOwner;
import com.idavy.drtops.domain.terminal.JtTerminalSessionLeaseService.SessionLeaseReleaseResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/jt-gateway")
public class GatewayRegistryController {

    private final TerminalManagementService service;
    private final JtTerminalSessionLeaseService leaseService;

    public GatewayRegistryController(
            TerminalManagementService service,
            JtTerminalSessionLeaseService leaseService) {
        this.service = service;
        this.leaseService = leaseService;
    }

    @PostMapping("/registrations/verify")
    ApiResponse<RegistrationVerificationResponse> verifyRegistration(
            @Valid @RequestBody RegistrationVerificationRequest request) {
        return ApiResponse.ok(RegistrationVerificationResponse.from(
                service.verifyCompositeRegistration(
                        request.terminalPhone(), request.terminalCode(),
                        request.manufacturerId(), request.model(),
                        request.vehicleIdentifier(), request.protocolVersion())));
    }

    @PostMapping("/authentications/verify")
    ApiResponse<OnboardRegistrationResolver.AuthenticationDecision> verifyAuthentication(
            @Valid @RequestBody AuthenticationVerificationRequest request) {
        return ApiResponse.ok(service.verifyCompositeAuthentication(
                request.terminalId(), request.tokenVersion(), request.tokenSha256(),
                request.gatewayInstance(), request.connectionId()));
    }

    @PostMapping("/authentications/verify-by-identity")
    ApiResponse<OnboardRegistrationResolver.AuthenticationDecision> verifyAuthenticationByIdentity(
            @Valid @RequestBody IdentityAuthenticationVerificationRequest request) {
        return ApiResponse.ok(service.verifyCompositeAuthenticationByIdentity(
                request.protocolVersion(), request.terminalPhone(),
                request.tokenSha256(), request.gatewayInstance(), request.connectionId()));
    }

    @PostMapping("/session-leases/renew")
    ResponseEntity<ApiResponse<SessionLeaseGrant>> renewSessionLease(
            @Valid @RequestBody SessionLeaseOwner owner) {
        return leaseService.renew(owner)
                .map(grant -> ResponseEntity.ok(ApiResponse.ok(grant)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT).body(null));
    }

    @PostMapping("/session-leases/release")
    ApiResponse<SessionLeaseReleaseResult> releaseSessionLease(
            @Valid @RequestBody SessionLeaseReleaseRequest request) {
        return ApiResponse.ok(leaseService.release(
                request.owner(), request.reasonCode()));
    }

    @PostMapping("/registrations/{terminalId}/complete")
    ApiResponse<Map<String, Boolean>> completeRegistration(
            @PathVariable UUID terminalId,
            @Valid @RequestBody RegistrationCompletionRequest request) {
        service.completeCompositeRegistration(
                terminalId, request.tokenVersion(), request.tokenSha256(), request.gatewayInstance());
        return ApiResponse.ok(Map.of("completed", true));
    }

    @PostMapping("/audit-events")
    ApiResponse<TerminalManagementService.GatewayAuditResult> recordAudit(
            @Valid @RequestBody GatewayAuditEventRequest request) {
        return ApiResponse.ok(service.recordGatewayAudit(JtGatewayAuditEvent.record(
                request.idempotencyKey(), request.terminalId(), request.vehicleId(), request.eventType(), request.result(),
                request.reasonCode(), request.protocolVersion(), request.messageId(), request.payloadDigest(),
                request.remoteAddress(), request.occurredAt(), request.gatewayInstance())));
    }

    public record RegistrationVerificationRequest(
            @NotBlank String terminalPhone,
            @NotBlank String terminalCode,
            @NotBlank String manufacturerId,
            @NotBlank String model,
            @NotBlank String vehicleIdentifier,
            @NotBlank String protocolVersion) {
    }

    public record AuthenticationVerificationRequest(
            @NotNull UUID terminalId,
            @Positive int tokenVersion,
            @NotBlank String tokenSha256,
            @NotBlank String gatewayInstance,
            @NotNull UUID connectionId) {
    }

    public record IdentityAuthenticationVerificationRequest(
            @NotBlank String protocolVersion,
            @NotBlank String terminalPhone,
            @NotBlank String tokenSha256,
            @NotBlank String gatewayInstance,
            @NotNull UUID connectionId) {
    }

    public record SessionLeaseReleaseRequest(
            @Valid @NotNull SessionLeaseOwner owner,
            @NotBlank String reasonCode) {
    }

    public record RegistrationVerificationResponse(
            boolean approved,
            int contractVersion,
            UUID terminalId,
            UUID onboardSystemId,
            UUID vehicleId,
            long onboardConfigurationVersion,
            Set<Role> roles,
            String sourceCoordinateSystem,
            OnboardRegistrationResolver.SessionProtocolProfile protocolProfile,
            String activeSafetyStandard,
            List<String> activeSafetyModules,
            int tokenVersion,
            OnboardRegistrationResolver.TerminalSessionContext context,
            List<String> warnings,
            String reasonCode) {
        static RegistrationVerificationResponse from(
                OnboardRegistrationResolver.RegistrationDecision decision) {
            OnboardRegistrationResolver.TerminalSessionContext context = decision.context();
            return new RegistrationVerificationResponse(
                    decision.approved(),
                    context == null ? 0 : context.contractVersion(),
                    context == null ? null : context.terminalId(),
                    context == null ? null : context.onboardSystemId(),
                    context == null ? null : context.vehicleId(),
                    context == null ? 0 : context.onboardConfigurationVersion(),
                    context == null ? Set.of() : context.roles(),
                    context == null ? null : context.sourceCoordinateSystem(),
                    context == null ? null : context.protocolProfile(),
                    context == null ? null : context.activeSafetyStandard(),
                    context == null ? List.of() : context.activeSafetyModules(),
                    context == null ? 0 : context.tokenVersion(),
                    context,
                    decision.warnings(),
                    decision.reasonCode());
        }
    }

    public record RegistrationCompletionRequest(
            @Positive int tokenVersion,
            @NotBlank String tokenSha256,
            @NotBlank String gatewayInstance) {
    }

    public record GatewayAuditEventRequest(
            @NotNull UUID idempotencyKey,
            UUID terminalId,
            UUID vehicleId,
            @NotNull JtGatewayAuditEvent.EventType eventType,
            @NotNull JtGatewayAuditEvent.Result result,
            String reasonCode,
            String protocolVersion,
            Integer messageId,
            String payloadDigest,
            String remoteAddress,
            @NotNull OffsetDateTime occurredAt,
            @NotBlank String gatewayInstance) {
    }
}
