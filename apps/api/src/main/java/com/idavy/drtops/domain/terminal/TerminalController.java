package com.idavy.drtops.domain.terminal;

import com.idavy.drtops.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
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
    ApiResponse<TerminalView> get(@PathVariable String terminalCode) {
        return ApiResponse.ok(TerminalView.from(service.get(terminalCode)));
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
    ApiResponse<TerminalView> rotateAuthentication(
            @PathVariable String terminalCode,
            Authentication authentication,
            @Valid @RequestBody RotateAuthRequest request) {
        return ApiResponse.ok(TerminalView.from(service.rotateAuthentication(
                terminalCode, request.expectedVersion(), request.tokenVersion(), request.tokenSha256(),
                request.reason(), actorId(authentication))));
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
            @NotBlank String terminalPhone,
            @NotBlank String terminalCode,
            @NotBlank String manufacturerId,
            @NotBlank String model,
            @NotBlank String protocolVersion,
            @NotBlank String sourceCoordinateSystem,
            @NotBlank String reason) {
    }

    public record ActionRequest(@NotNull Long expectedVersion, @NotBlank String reason) {
    }

    public record BindRequest(@NotNull UUID vehicleId, @NotNull Long expectedVersion, @NotBlank String reason) {
    }

    public record ReplaceRequest(
            @NotBlank String replacementTerminalCode,
            @NotNull Long expectedVersion,
            @NotNull Long replacementExpectedVersion,
            @NotBlank String reason) {
    }

    public record RotateAuthRequest(
            @NotNull Long expectedVersion,
            @Positive int tokenVersion,
            @NotBlank String tokenSha256,
            @NotBlank String reason) {
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
            int visible = Math.min(4, value.length());
            return "****" + value.substring(value.length() - visible);
        }
    }

    public record ActionView(String code, TerminalView terminal) {
    }
}
