package com.idavy.drtops.domain.location;

import com.idavy.drtops.domain.fleet.Vehicle;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;

public record VehicleLocationSnapshotView(
        BigDecimal longitude,
        BigDecimal latitude,
        String standardizedAddress,
        LocationSource source,
        String coordinateSystem,
        OffsetDateTime driverReportedAt,
        OffsetDateTime recordedAt,
        UUID eventId,
        UUID vehicleTaskId) {

    public static VehicleLocationSnapshotView from(Vehicle vehicle) {
        if (vehicle.getCurrentLocationReportedAt() == null) {
            return null;
        }
        try {
            var point = new WKTReader().read(vehicle.getCurrentLocation());
            return new VehicleLocationSnapshotView(
                    BigDecimal.valueOf(point.getCoordinate().x), BigDecimal.valueOf(point.getCoordinate().y),
                    vehicle.getCurrentLocationAddress(), vehicle.getCurrentLocationSource(),
                    vehicle.getCurrentLocationCoordinateSystem(), vehicle.getCurrentLocationReportedAt(),
                    vehicle.getCurrentLocationRecordedAt(), vehicle.getCurrentLocationEventId(),
                    vehicle.getCurrentLocationTaskId());
        } catch (ParseException exception) {
            throw new IllegalStateException("车辆位置快照格式无效", exception);
        }
    }
}
