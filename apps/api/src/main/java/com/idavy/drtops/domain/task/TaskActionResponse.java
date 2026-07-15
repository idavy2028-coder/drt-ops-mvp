package com.idavy.drtops.domain.task;

import com.idavy.drtops.domain.location.LocationReportResult;
import com.idavy.drtops.domain.location.LocationWarning;
import com.idavy.drtops.domain.location.VehicleLocationView;
import java.util.List;

public record TaskActionResponse(
        VehicleTask task,
        VehicleLocationView locationEvent,
        boolean snapshotApplied,
        List<LocationWarning> warnings,
        boolean replayed) {

    static TaskActionResponse from(VehicleTask task, LocationReportResult result) {
        return new TaskActionResponse(
                task,
                VehicleLocationView.from(result.event()),
                result.event().isSnapshotApplied(),
                result.warnings(),
                result.replayed());
    }
}
