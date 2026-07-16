package com.idavy.drtops.domain.location;

import java.util.List;

public record LocationReportResult(
        VehicleLocationEvent event,
        List<LocationWarning> warnings,
        boolean replayed) {

    public LocationReportResult {
        warnings = List.copyOf(warnings);
    }
}
