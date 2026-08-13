package com.idavy.drtops.domain.task;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 面向任务执行界面的任务视图：保留内部车辆标识，并额外提供可辨识的车牌号。
 */
public record VehicleTaskView(
        UUID id,
        UUID vehicleId,
        String vehiclePlateNumber,
        String vehicleStatus,
        UUID driverId,
        TaskStatus status,
        OffsetDateTime plannedStartAt,
        OffsetDateTime createdAt,
        List<TaskStop> stops) {

    static VehicleTaskView from(VehicleTask task, String vehiclePlateNumber, String vehicleStatus) {
        return new VehicleTaskView(
                task.getId(),
                task.getVehicleId(),
                vehiclePlateNumber,
                vehicleStatus,
                task.getDriverId(),
                task.getStatus(),
                task.getPlannedStartAt(),
                task.getCreatedAt(),
                task.getStops());
    }
}
