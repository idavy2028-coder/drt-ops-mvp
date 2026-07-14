package com.idavy.drtops.domain.location;

import java.util.List;

public record LocationReportResponse(
        VehicleLocationView event,
        boolean snapshotApplied,
        List<LocationWarning> warnings,
        boolean replayed) {

    static LocationReportResponse from(LocationReportResult result) {
        return new LocationReportResponse(
                VehicleLocationView.from(result.event()), result.event().isSnapshotApplied(),
                result.warnings(), result.replayed());
    }
}
