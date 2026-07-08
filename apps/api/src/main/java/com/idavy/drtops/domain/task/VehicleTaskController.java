package com.idavy.drtops.domain.task;

import com.idavy.drtops.common.ApiResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/vehicle-tasks")
public class VehicleTaskController {

    private final TaskExecutionService taskExecutionService;

    public VehicleTaskController(TaskExecutionService taskExecutionService) {
        this.taskExecutionService = taskExecutionService;
    }

    @PostMapping("/{taskId}/start")
    ApiResponse<VehicleTask> start(@PathVariable UUID taskId) {
        return ApiResponse.ok(taskExecutionService.start(taskId));
    }

    @PostMapping("/{taskId}/stops/{taskStopId}/arrive")
    ApiResponse<VehicleTask> arrive(@PathVariable UUID taskId, @PathVariable UUID taskStopId) {
        return ApiResponse.ok(taskExecutionService.arrive(taskId, taskStopId));
    }

    @PostMapping("/{taskId}/stops/{taskStopId}/board")
    ApiResponse<VehicleTask> board(@PathVariable UUID taskId, @PathVariable UUID taskStopId) {
        return ApiResponse.ok(taskExecutionService.board(taskId, taskStopId));
    }

    @PostMapping("/{taskId}/stops/{taskStopId}/alight")
    ApiResponse<VehicleTask> alight(@PathVariable UUID taskId, @PathVariable UUID taskStopId) {
        return ApiResponse.ok(taskExecutionService.alight(taskId, taskStopId));
    }

    @PostMapping("/{taskId}/complete")
    ApiResponse<VehicleTask> complete(@PathVariable UUID taskId) {
        return ApiResponse.ok(taskExecutionService.complete(taskId));
    }

    @PostMapping("/{taskId}/exception")
    ApiResponse<VehicleTask> markException(@PathVariable UUID taskId, @RequestBody ReasonRequest request) {
        return ApiResponse.ok(taskExecutionService.markException(taskId, request.reason()));
    }

    public record ReasonRequest(String reason) {
    }
}
