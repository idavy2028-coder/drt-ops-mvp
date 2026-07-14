package com.idavy.drtops.domain.location;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record VehicleLocationView(
        UUID id,
        UUID vehicleId,
        UUID vehicleTaskId,
        LocationEventType eventType,
        BigDecimal longitude,
        BigDecimal latitude,
        String standardizedAddress,
        LocationSource source,
        String coordinateSystem,
        OffsetDateTime driverReportedAt,
        OffsetDateTime recordedAt,
        UUID recordedBy,
        UUID correctsEventId,
        boolean snapshotApplied) {

    static VehicleLocationView from(VehicleLocationEvent event) {
        return new VehicleLocationView(
                event.getId(), event.getVehicleId(), event.getVehicleTaskId(), event.getEventType(),
                event.getLongitude(), event.getLatitude(), event.getStandardizedAddress(), event.getSource(),
                event.getCoordinateSystem(), event.getDriverReportedAt(), event.getRecordedAt(), event.getRecordedBy(),
                event.getCorrectsEventId(), event.isSnapshotApplied());
    }
}
