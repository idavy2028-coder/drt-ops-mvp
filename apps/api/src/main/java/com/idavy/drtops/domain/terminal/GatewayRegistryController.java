package com.idavy.drtops.domain.terminal;

import com.idavy.drtops.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/jt-gateway")
public class GatewayRegistryController {

    private final TerminalManagementService service;

    public GatewayRegistryController(TerminalManagementService service) {
        this.service = service;
    }

    @PostMapping("/registrations/verify")
    ApiResponse<TerminalManagementService.RegistrationDecision> verifyRegistration(
            @Valid @RequestBody RegistrationVerificationRequest request) {
        return ApiResponse.ok(service.verifyRegistration(
                request.terminalPhone(), request.terminalCode(), request.manufacturerId(), request.model(),
                request.vehicleIdentifier(), request.protocolVersion()));
    }

    @PostMapping("/authentications/verify")
    ApiResponse<TerminalManagementService.AuthenticationDecision> verifyAuthentication(
            @Valid @RequestBody AuthenticationVerificationRequest request) {
        return ApiResponse.ok(service.verifyAuthentication(
                request.terminalId(), request.tokenVersion(), request.tokenSha256(), request.gatewayInstance()));
    }

    @PostMapping("/registrations/{terminalId}/complete")
    ApiResponse<Map<String, Boolean>> completeRegistration(
            @PathVariable UUID terminalId,
            @Valid @RequestBody RegistrationCompletionRequest request) {
        service.completeRegistration(
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
            @NotBlank String gatewayInstance) {
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
