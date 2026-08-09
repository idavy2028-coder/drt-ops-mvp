package com.idavy.drtops.domain.task;

import com.idavy.drtops.common.ApiResponse;
import com.idavy.drtops.domain.fleet.VehicleRepository;
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
    private final VehicleRepository vehicleRepository;
    private final TaskExecutionService taskExecutionService;

    public VehicleTaskController(
            VehicleTaskRepository vehicleTaskRepository,
            VehicleRepository vehicleRepository,
            TaskExecutionService taskExecutionService) {
        this.vehicleTaskRepository = vehicleTaskRepository;
        this.vehicleRepository = vehicleRepository;
        this.taskExecutionService = taskExecutionService;
    }

    @GetMapping
    ApiResponse<List<VehicleTaskView>> list() {
        return ApiResponse.ok(vehicleTaskRepository.findAllByOrderByPlannedStartAtAsc().stream()
                .map(this::toView)
                .toList());
    }

    @PostMapping("/{taskId}/start")
    ApiResponse<TaskActionView> start(
            Authentication authentication, @PathVariable UUID taskId, @Valid @RequestBody TaskActionRequest request) {
        return ApiResponse.ok(toView(taskExecutionService.start(actorId(authentication), taskId, request.locationReport())));
    }

    @PostMapping("/{taskId}/stops/{taskStopId}/arrive")
    ApiResponse<TaskActionView> arrive(
            Authentication authentication,
            @PathVariable UUID taskId,
            @PathVariable UUID taskStopId,
            @Valid @RequestBody TaskActionRequest request) {
        return ApiResponse.ok(toView(taskExecutionService.arrive(
                actorId(authentication), taskId, taskStopId, request.locationReport())));
    }

    @PostMapping("/{taskId}/stops/{taskStopId}/board")
    ApiResponse<TaskActionView> board(
            Authentication authentication,
            @PathVariable UUID taskId,
            @PathVariable UUID taskStopId,
            @Valid @RequestBody TaskActionRequest request) {
        return ApiResponse.ok(toView(taskExecutionService.board(
                actorId(authentication), taskId, taskStopId, request.locationReport())));
    }

    @PostMapping("/{taskId}/stops/{taskStopId}/alight")
    ApiResponse<TaskActionView> alight(
            Authentication authentication,
            @PathVariable UUID taskId,
            @PathVariable UUID taskStopId,
            @Valid @RequestBody TaskActionRequest request) {
        return ApiResponse.ok(toView(taskExecutionService.alight(
                actorId(authentication), taskId, taskStopId, request.locationReport())));
    }

    @PostMapping("/{taskId}/complete")
    ApiResponse<TaskActionView> complete(
            Authentication authentication, @PathVariable UUID taskId, @Valid @RequestBody TaskActionRequest request) {
        return ApiResponse.ok(toView(taskExecutionService.complete(actorId(authentication), taskId, request.locationReport())));
    }

    @PostMapping("/{taskId}/exception")
    ApiResponse<VehicleTaskView> markException(
            Authentication authentication, @PathVariable UUID taskId, @RequestBody ReasonRequest request) {
        return ApiResponse.ok(toView(taskExecutionService.markException(actorId(authentication), taskId, request.reason())));
    }

    @PostMapping("/{taskId}/delay")
    ApiResponse<VehicleTaskView> markSevereDelay(
            Authentication authentication, @PathVariable UUID taskId, @RequestBody ReasonRequest request) {
        return ApiResponse.ok(toView(taskExecutionService.markSevereDelay(actorId(authentication), taskId, request.reason())));
    }

    private TaskActionView toView(TaskActionResponse response) {
        return TaskActionView.from(response, toView(response.task()));
    }

    private VehicleTaskView toView(VehicleTask task) {
        String plateNumber = vehicleRepository.findById(task.getVehicleId())
                .map(vehicle -> vehicle.getPlateNumber())
                .orElse(null);
        return VehicleTaskView.from(task, plateNumber);
    }

    private UUID actorId(Authentication authentication) {
        return (UUID) authentication.getPrincipal();
    }

    public record ReasonRequest(String reason) {
    }
}
