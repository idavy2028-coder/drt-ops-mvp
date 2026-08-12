package com.idavy.drtops.domain.terminal;

import com.idavy.drtops.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/terminals")
public class TerminalController {

    private final TerminalManagementService service;

    public TerminalController(TerminalManagementService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<List<TerminalView>> list() {
        return ApiResponse.ok(service.list().stream().map(TerminalView::from).toList());
    }

    @GetMapping("/{terminalCode}")
    ApiResponse<TerminalDetailView> get(@PathVariable String terminalCode) {
        return ApiResponse.ok(TerminalDetailView.from(service.getDetail(terminalCode)));
    }

    @PostMapping
    ResponseEntity<ApiResponse<TerminalView>> preset(
            Authentication authentication, @Valid @RequestBody PresetRequest request) {
        JtTerminal terminal = service.preset(new TerminalManagementService.PresetCommand(
                request.terminalPhone(), request.terminalCode(), request.manufacturerId(), request.model(),
                request.protocolVersion(), request.sourceCoordinateSystem(), actorId(authentication), request.reason()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(TerminalView.from(terminal)));
    }

    @PostMapping("/{terminalCode}/bind")
    ApiResponse<TerminalView> bind(
            @PathVariable String terminalCode,
            Authentication authentication,
            @Valid @RequestBody BindRequest request) {
        return ApiResponse.ok(TerminalView.from(service.bind(
                terminalCode, request.vehicleId(), request.expectedVersion(), request.reason(), actorId(authentication))));
    }

    @PostMapping("/{terminalCode}/activate")
    ApiResponse<TerminalView> activate(
            @PathVariable String terminalCode,
            Authentication authentication,
            @Valid @RequestBody ActionRequest request) {
        return ApiResponse.ok(TerminalView.from(service.activate(
                terminalCode, request.expectedVersion(), request.reason(), actorId(authentication))));
    }

    @PostMapping("/{terminalCode}/suspend")
    ResponseEntity<ApiResponse<ActionView>> suspend(
            @PathVariable String terminalCode,
            Authentication authentication,
            @Valid @RequestBody ActionRequest request) {
        return actionResponse(service.suspend(
                terminalCode, request.expectedVersion(), request.reason(), actorId(authentication)));
    }

    @PostMapping("/{terminalCode}/retire")
    ResponseEntity<ApiResponse<ActionView>> retire(
            @PathVariable String terminalCode,
            Authentication authentication,
            @Valid @RequestBody ActionRequest request) {
        return actionResponse(service.retire(
                terminalCode, request.expectedVersion(), request.reason(), actorId(authentication)));
    }

    @PostMapping("/{terminalCode}/disconnect")
    ResponseEntity<ApiResponse<ActionView>> disconnect(
            @PathVariable String terminalCode,
            Authentication authentication,
            @Valid @RequestBody ActionRequest request) {
        return actionResponse(service.disconnect(
                terminalCode, request.expectedVersion(), request.reason(), actorId(authentication)));
    }

    @PostMapping("/{terminalCode}/replace")
    ResponseEntity<ApiResponse<ActionView>> replace(
            @PathVariable String terminalCode,
            Authentication authentication,
            @Valid @RequestBody ReplaceRequest request) {
        TerminalManagementService.ReplacementResult result = service.replace(
                terminalCode, request.replacementTerminalCode(), request.expectedVersion(),
                request.replacementExpectedVersion(), request.reason(), actorId(authentication));
        HttpStatus status = "DISCONNECT_PENDING_CONFIRMATION".equals(result.disconnectStatus())
                ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.ok(
                new ActionView(result.disconnectStatus(), TerminalView.from(result.terminal()))));
    }

    @PostMapping("/{terminalCode}/rotate-auth")
    ResponseEntity<ApiResponse<ActionView>> rotateAuthentication(
            @PathVariable String terminalCode,
            Authentication authentication,
            @Valid @RequestBody ActionRequest request) {
        return actionResponse(service.rotateAuthentication(
                terminalCode, request.expectedVersion(), request.reason(), actorId(authentication)));
    }

    private ResponseEntity<ApiResponse<ActionView>> actionResponse(TerminalManagementService.ActionResult result) {
        HttpStatus status = "DISCONNECT_PENDING_CONFIRMATION".equals(result.disconnectStatus())
                ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.ok(
                new ActionView(result.disconnectStatus(), TerminalView.from(result.terminal()))));
    }

    private static UUID actorId(Authentication authentication) {
        return authentication.getPrincipal() instanceof UUID actor
                ? actor : UUID.fromString(authentication.getName());
    }

    public record PresetRequest(
            @NotBlank @Size(max = 30) String terminalPhone,
            @NotBlank @Size(max = 80) String terminalCode,
            @NotBlank @Size(max = 80) String manufacturerId,
            @NotBlank @Size(max = 120) String model,
            @NotBlank @Size(max = 40) String protocolVersion,
            @NotBlank @Size(max = 20) String sourceCoordinateSystem,
            @NotBlank @Size(max = 300) String reason) {
    }

    public record ActionRequest(@NotNull Long expectedVersion, @NotBlank @Size(max = 300) String reason) {
    }

    public record BindRequest(
            @NotNull UUID vehicleId, @NotNull Long expectedVersion, @NotBlank @Size(max = 300) String reason) {
    }

    public record ReplaceRequest(
            @NotBlank @Size(max = 80) String replacementTerminalCode,
            @NotNull Long expectedVersion,
            @NotNull Long replacementExpectedVersion,
            @NotBlank @Size(max = 300) String reason) {
    }

    public record TerminalView(
            String terminalCode,
            String terminalPhoneMasked,
            String manufacturerId,
            String model,
            String protocolVersion,
            String sourceCoordinateSystem,
            String status,
            boolean registrationCompleted,
            long version) {
        static TerminalView from(JtTerminal terminal) {
            return new TerminalView(
                    terminal.getTerminalCode(), mask(terminal.getTerminalPhone()), terminal.getManufacturerId(),
                    terminal.getModel(), terminal.getProtocolVersion(), terminal.getSourceCoordinateSystem(),
                    terminal.getStatus().name(), terminal.getLastRegisteredAt() != null, terminal.getVersion());
        }

        private static String mask(String value) {
            if (value.length() <= 4) {
                return "****";
            }
            int visible = Math.min(4, value.length());
            return "****" + value.substring(value.length() - visible);
        }
    }

    public record ActionView(String code, TerminalView terminal) {
    }

    public record TerminalDetailView(
            String terminalCode,
            String terminalPhoneMasked,
            String manufacturerId,
            String model,
            String protocolVersion,
            String sourceCoordinateSystem,
            String activeSafetyStandard,
            java.util.List<String> activeSafetyModules,
            boolean jt1078Enabled,
            String status,
            String onlineStatus,
            boolean registrationCompleted,
            long version,
            java.time.OffsetDateTime lastRegisteredAt,
            java.time.OffsetDateTime lastAuthenticatedAt,
            java.time.OffsetDateTime lastValidMessageAt,
            java.time.OffsetDateTime lastHeartbeatAt,
            java.time.OffsetDateTime lastLocationAt,
            java.time.OffsetDateTime offlineAt,
            TerminalManagementService.BindingSummary currentBinding,
            java.util.List<TerminalManagementService.BindingSummary> bindingHistory,
            java.util.List<TerminalManagementService.GatewayAuditSummary> securityAudits) {
        static TerminalDetailView from(TerminalManagementService.TerminalDetail detail) {
            JtTerminal terminal = detail.terminal();
            return new TerminalDetailView(terminal.getTerminalCode(), TerminalView.mask(terminal.getTerminalPhone()),
                    terminal.getManufacturerId(), terminal.getModel(), terminal.getProtocolVersion(),
                    terminal.getSourceCoordinateSystem(), terminal.getActiveSafetyStandard(),
                    parseModules(terminal.getActiveSafetyModules()), terminal.isJt1078Enabled(), terminal.getStatus().name(),
                    detail.onlineStatus().name(), terminal.getLastRegisteredAt() != null, terminal.getVersion(),
                    terminal.getLastRegisteredAt(), terminal.getLastAuthenticatedAt(), detail.lastValidMessageAt(),
                    null, null, detail.offlineAt(), detail.currentBinding(), detail.bindingHistory(), detail.securityAudits());
        }

        private static java.util.List<String> parseModules(String serialized) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().readValue(serialized,
                        new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() { });
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                return java.util.List.of();
            }
        }
    }
}
