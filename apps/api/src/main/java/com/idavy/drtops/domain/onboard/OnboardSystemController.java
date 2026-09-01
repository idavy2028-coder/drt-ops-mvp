package com.idavy.drtops.domain.onboard;

import com.idavy.drtops.common.ApiResponse;
import com.idavy.drtops.domain.onboard.OnboardDeviceCapability.Capability;
import com.idavy.drtops.domain.onboard.OnboardSystemConfigurationService.CapabilityVerificationCommand;
import com.idavy.drtops.domain.onboard.OnboardSystemConfigurationService.CapabilityVerificationView;
import com.idavy.drtops.domain.onboard.OnboardSystemConfigurationService.ConfigurationCommand;
import com.idavy.drtops.domain.onboard.OnboardSystemConfigurationService.ConfigurationPreview;
import com.idavy.drtops.domain.onboard.OnboardSystemConfigurationService.OnboardSystemView;
import com.idavy.drtops.domain.onboard.OnboardSystemConfigurationService.OnboardSystemPage;
import com.idavy.drtops.domain.onboard.OnboardSystemConfigurationService.OnboardSystemDetailSnapshot;
import com.idavy.drtops.domain.onboard.OnboardReadinessService.OnboardReadiness;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class OnboardSystemController {

    private final OnboardSystemConfigurationService service;

    public OnboardSystemController(OnboardSystemConfigurationService service) {
        this.service = service;
    }

    @GetMapping("/onboard-systems")
    ApiResponse<OnboardSystemPage> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.listSystems(page, size));
    }

    @GetMapping("/onboard-systems/{vehicleId}")
    ApiResponse<OnboardSystemDetailResponse> get(@PathVariable UUID vehicleId) {
        OnboardSystemDetailSnapshot snapshot = service.getSystemDetail(vehicleId);
        return ApiResponse.ok(OnboardSystemDetailResponse.from(
                snapshot.system(), snapshot.readiness()));
    }

    @PostMapping("/onboard-systems/{vehicleId}/configuration/preview")
    ApiResponse<ConfigurationPreview> preview(
            @PathVariable UUID vehicleId,
            @RequestBody ConfigurationCommand command) {
        return ApiResponse.ok(service.preview(vehicleId, command));
    }

    @PostMapping("/onboard-systems/{vehicleId}/configuration")
    ApiResponse<ConfigurationPreview> apply(
            @PathVariable UUID vehicleId,
            Authentication authentication,
            @RequestBody ConfigurationCommand command) {
        return ApiResponse.ok(service.apply(vehicleId, command, actorId(authentication)));
    }

    @PostMapping("/terminals/{terminalCode}/capability-verifications")
    ApiResponse<CapabilityVerificationView> verifyCapability(
            @PathVariable String terminalCode,
            Authentication authentication,
            @RequestBody CapabilityVerificationRequest request) {
        return ApiResponse.ok(service.verifyCapability(
                terminalCode,
                new CapabilityVerificationCommand(
                        request.capability(), request.expectedVersion(),
                        request.reason(), request.evidenceRef()),
                actorId(authentication)));
    }

    @ExceptionHandler(OnboardConfigurationConflictException.class)
    ResponseEntity<ApiResponse<Map<String, String>>> handleConflict(
            OnboardConfigurationConflictException exception) {
        String code = exception.getMessage();
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.ok(Map.of("code", code, "message", code)));
    }

    private static UUID actorId(Authentication authentication) {
        return authentication.getPrincipal() instanceof UUID actor
                ? actor : UUID.fromString(authentication.getName());
    }

    public record CapabilityVerificationRequest(
            Capability capability,
            Long expectedVersion,
            String reason,
            String evidenceRef) {
    }

    public record OnboardSystemDetailResponse(
            UUID onboardSystemId,
            UUID vehicleId,
            OnboardSystem.Status status,
            OnboardSystem.OperatingMode operatingMode,
            long version,
            String activeLocationDeviceAlias,
            String wanDeviceAlias,
            List<OnboardSystemConfigurationService.DeviceView> devices,
            OnboardReadiness readiness) {

        static OnboardSystemDetailResponse from(
                OnboardSystemView system,
                OnboardReadiness readiness) {
            return new OnboardSystemDetailResponse(
                    system.onboardSystemId(), system.vehicleId(), system.status(),
                    system.operatingMode(), system.version(),
                    system.activeLocationDeviceAlias(), system.wanDeviceAlias(),
                    system.devices(), readiness);
        }
    }
}
