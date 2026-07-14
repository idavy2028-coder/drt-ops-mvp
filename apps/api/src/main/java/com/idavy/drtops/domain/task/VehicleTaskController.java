package com.idavy.drtops.domain.task;

import com.idavy.drtops.common.ApiResponse;
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
    ApiResponse<VehicleTask> start(Authentication authentication, @PathVariable UUID taskId) {
        return ApiResponse.ok(taskExecutionService.start(actorId(authentication), taskId));
    }

    @PostMapping("/{taskId}/stops/{taskStopId}/arrive")
    ApiResponse<VehicleTask> arrive(Authentication authentication, @PathVariable UUID taskId, @PathVariable UUID taskStopId) {
        return ApiResponse.ok(taskExecutionService.arrive(actorId(authentication), taskId, taskStopId));
    }

    @PostMapping("/{taskId}/stops/{taskStopId}/board")
    ApiResponse<VehicleTask> board(Authentication authentication, @PathVariable UUID taskId, @PathVariable UUID taskStopId) {
        return ApiResponse.ok(taskExecutionService.board(actorId(authentication), taskId, taskStopId));
    }

    @PostMapping("/{taskId}/stops/{taskStopId}/alight")
    ApiResponse<VehicleTask> alight(Authentication authentication, @PathVariable UUID taskId, @PathVariable UUID taskStopId) {
        return ApiResponse.ok(taskExecutionService.alight(actorId(authentication), taskId, taskStopId));
    }

    @PostMapping("/{taskId}/complete")
    ApiResponse<VehicleTask> complete(Authentication authentication, @PathVariable UUID taskId) {
        return ApiResponse.ok(taskExecutionService.complete(actorId(authentication), taskId));
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
