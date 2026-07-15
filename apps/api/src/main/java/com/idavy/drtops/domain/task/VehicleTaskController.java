package com.idavy.drtops.domain.task;

import com.idavy.drtops.common.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/vehicle-tasks")
public class VehicleTaskController {

    private final VehicleTaskRepository vehicleTaskRepository;
    private final TaskExecutionService taskExecutionService;

    public VehicleTaskController(VehicleTaskRepository vehicleTaskRepository, TaskExecutionService taskExecutionService) {
        this.vehicleTaskRepository = vehicleTaskRepository;
        this.taskExecutionService = taskExecutionService;
    }

    @GetMapping
    ApiResponse<List<VehicleTask>> list() {
        return ApiResponse.ok(vehicleTaskRepository.findAllByOrderByPlannedStartAtAsc());
    }

    @PostMapping("/{taskId}/start")
    ApiResponse<TaskActionResponse> start(
            Authentication authentication, @PathVariable UUID taskId, @Valid @RequestBody TaskActionRequest request) {
        return ApiResponse.ok(taskExecutionService.start(actorId(authentication), taskId, request.locationReport()));
    }

    @PostMapping("/{taskId}/stops/{taskStopId}/arrive")
    ApiResponse<TaskActionResponse> arrive(
            Authentication authentication,
            @PathVariable UUID taskId,
            @PathVariable UUID taskStopId,
            @Valid @RequestBody TaskActionRequest request) {
        return ApiResponse.ok(taskExecutionService.arrive(
                actorId(authentication), taskId, taskStopId, request.locationReport()));
    }

    @PostMapping("/{taskId}/stops/{taskStopId}/board")
    ApiResponse<TaskActionResponse> board(
            Authentication authentication,
            @PathVariable UUID taskId,
            @PathVariable UUID taskStopId,
            @Valid @RequestBody TaskActionRequest request) {
        return ApiResponse.ok(taskExecutionService.board(
                actorId(authentication), taskId, taskStopId, request.locationReport()));
    }

    @PostMapping("/{taskId}/stops/{taskStopId}/alight")
    ApiResponse<TaskActionResponse> alight(
            Authentication authentication,
            @PathVariable UUID taskId,
            @PathVariable UUID taskStopId,
            @Valid @RequestBody TaskActionRequest request) {
        return ApiResponse.ok(taskExecutionService.alight(
                actorId(authentication), taskId, taskStopId, request.locationReport()));
    }

    @PostMapping("/{taskId}/complete")
    ApiResponse<TaskActionResponse> complete(
            Authentication authentication, @PathVariable UUID taskId, @Valid @RequestBody TaskActionRequest request) {
        return ApiResponse.ok(taskExecutionService.complete(actorId(authentication), taskId, request.locationReport()));
    }

    @PostMapping("/{taskId}/exception")
    ApiResponse<VehicleTask> markException(
            Authentication authentication, @PathVariable UUID taskId, @RequestBody ReasonRequest request) {
        return ApiResponse.ok(taskExecutionService.markException(actorId(authentication), taskId, request.reason()));
    }

    @PostMapping("/{taskId}/delay")
    ApiResponse<VehicleTask> markSevereDelay(
            Authentication authentication, @PathVariable UUID taskId, @RequestBody ReasonRequest request) {
        return ApiResponse.ok(taskExecutionService.markSevereDelay(actorId(authentication), taskId, request.reason()));
    }

    private UUID actorId(Authentication authentication) {
        return (UUID) authentication.getPrincipal();
    }

    public record ReasonRequest(String reason) {
    }
}
