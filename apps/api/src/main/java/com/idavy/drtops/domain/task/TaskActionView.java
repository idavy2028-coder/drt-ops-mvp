package com.idavy.drtops.domain.task;

import com.idavy.drtops.domain.location.LocationWarning;
import com.idavy.drtops.domain.location.VehicleLocationView;
import java.util.List;

/**
 * 任务动作接口的输出视图，保证动作后的任务也带有车牌号。
 */
public record TaskActionView(
        VehicleTaskView task,
        VehicleLocationView locationEvent,
        boolean snapshotApplied,
        List<LocationWarning> warnings,
        boolean replayed) {

    static TaskActionView from(TaskActionResponse response, VehicleTaskView task) {
        return new TaskActionView(
                task,
                response.locationEvent(),
                response.snapshotApplied(),
                response.warnings(),
                response.replayed());
    }
}
