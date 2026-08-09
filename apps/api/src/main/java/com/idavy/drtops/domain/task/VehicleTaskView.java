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
        UUID driverId,
        TaskStatus status,
        OffsetDateTime plannedStartAt,
        List<TaskStop> stops) {

    static VehicleTaskView from(VehicleTask task, String vehiclePlateNumber) {
        return new VehicleTaskView(
                task.getId(),
                task.getVehicleId(),
                vehiclePlateNumber,
                task.getDriverId(),
                task.getStatus(),
                task.getPlannedStartAt(),
                task.getStops());
    }
}
