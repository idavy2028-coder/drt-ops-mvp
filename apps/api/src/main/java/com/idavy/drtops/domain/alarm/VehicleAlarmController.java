package com.idavy.drtops.domain.alarm;

import com.idavy.drtops.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
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
@RequestMapping("/api/vehicle-alarms")
public class VehicleAlarmController {
    private final VehicleAlarmQueryService queries;
    private final VehicleAlarmActionService actions;

    VehicleAlarmController(VehicleAlarmQueryService queries, VehicleAlarmActionService actions) {
        this.queries = queries;
        this.actions = actions;
    }

    @GetMapping
    ApiResponse<List<VehicleAlarmQueryService.AlarmReadModel>> list(
            Authentication authentication,
            @RequestParam(required = false) Integer level,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID vehicleId,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) Boolean hasAttachment) {
        return ApiResponse.ok(queries.list(actorId(authentication),
                new VehicleAlarmQueryService.Filter(level, status, vehicleId, module, hasAttachment)));
    }

    @GetMapping("/{publicId}")
    ApiResponse<VehicleAlarmQueryService.AlarmReadModel> get(
            Authentication authentication, @PathVariable UUID publicId) {
        return ApiResponse.ok(queries.get(actorId(authentication), publicId));
    }

    @PostMapping("/{publicId}/actions")
    ApiResponse<VehicleAlarmQueryService.AlarmReadModel> act(
            Authentication authentication, @PathVariable UUID publicId,
            @Valid @RequestBody ActionRequest request) {
        if (!request.confirmed()) {
            throw new IllegalArgumentException("alarm action must be confirmed");
        }
        UUID actorId = actorId(authentication);
        VehicleAlarmQueryService.AlarmReadModel current = queries.get(actorId, publicId);
        request.action().requireAllowedFrom(current.status());
        VehicleAlarm alarm = actions.transition(
                queries.findInternalId(actorId, publicId), request.expectedVersion(), actorId,
                request.action().targetStatus(), request.reason().trim());
        return ApiResponse.ok(queries.get(actorId, alarm.getPublicId()));
    }

    private static UUID actorId(Authentication authentication) {
        if (authentication == null) throw new VehicleAlarmAuthorizationException("vehicle alarm access is forbidden");
        Object principal = authentication.getPrincipal();
        if (principal instanceof UUID actor) return actor;
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException exception) {
            throw new VehicleAlarmAuthorizationException("vehicle alarm access is forbidden");
        }
    }

    @ExceptionHandler(VehicleAlarmNotFoundException.class)
    ResponseEntity<ApiResponse<Map<String, String>>> notFound(VehicleAlarmNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler({VehicleAlarmActionConflictException.class, VehicleAlarmVersionConflictException.class})
    ResponseEntity<ApiResponse<Map<String, String>>> conflict(RuntimeException exception) {
        return error(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(VehicleAlarmAuthorizationException.class)
    ResponseEntity<ApiResponse<Map<String, String>>> forbidden(VehicleAlarmAuthorizationException exception) {
        return error(HttpStatus.FORBIDDEN, exception.getMessage());
    }

    private static ResponseEntity<ApiResponse<Map<String, String>>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(ApiResponse.ok(Map.of("message", message)));
    }

    public record ActionRequest(
            @NotNull Action action,
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotBlank @Size(max = 300) String reason,
            @NotNull Boolean confirmed) {
    }

    public enum Action {
        ACKNOWLEDGE(VehicleAlarm.ProcessingStatus.ACKNOWLEDGED),
        TAKE_OVER(VehicleAlarm.ProcessingStatus.PROCESSING),
        RESOLVE(VehicleAlarm.ProcessingStatus.RESOLVED),
        MARK_FALSE_POSITIVE(VehicleAlarm.ProcessingStatus.FALSE_POSITIVE),
        REOPEN(VehicleAlarm.ProcessingStatus.PROCESSING);

        private final VehicleAlarm.ProcessingStatus targetStatus;
        Action(VehicleAlarm.ProcessingStatus targetStatus) { this.targetStatus = targetStatus; }
        VehicleAlarm.ProcessingStatus targetStatus() { return targetStatus; }

        void requireAllowedFrom(String currentStatus) {
            boolean allowed = switch (this) {
                case ACKNOWLEDGE -> "NEW".equals(currentStatus);
                case TAKE_OVER -> "ACKNOWLEDGED".equals(currentStatus);
                case RESOLVE -> "ACKNOWLEDGED".equals(currentStatus) || "PROCESSING".equals(currentStatus);
                case MARK_FALSE_POSITIVE -> "NEW".equals(currentStatus)
                        || "ACKNOWLEDGED".equals(currentStatus) || "PROCESSING".equals(currentStatus);
                case REOPEN -> "RESOLVED".equals(currentStatus) || "FALSE_POSITIVE".equals(currentStatus);
            };
            if (!allowed) throw new VehicleAlarmActionConflictException("invalid vehicle alarm status transition");
        }
    }
}
