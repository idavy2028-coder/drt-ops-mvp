package com.idavy.drtops.domain.fleet;

import com.idavy.drtops.domain.location.VehicleLocationSnapshotView;
import java.time.OffsetDateTime;
import java.util.UUID;

public record VehicleView(
        UUID id,
        String plateNumber,
        String vehicleType,
        int capacity,
        String currentStatus,
        String fleetName,
        boolean dispatchable,
        OffsetDateTime createdAt,
        VehicleLocationSnapshotView latestLocation) {

    static VehicleView from(Vehicle vehicle) {
        return new VehicleView(vehicle.getId(), vehicle.getPlateNumber(), vehicle.getVehicleType(), vehicle.getCapacity(),
                vehicle.getCurrentStatus(), vehicle.getFleetName(), vehicle.isDispatchable(), vehicle.getCreatedAt(),
                VehicleLocationSnapshotView.from(vehicle));
    }
}
