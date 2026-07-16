package com.idavy.drtops.domain.location;

import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class VehicleLocationSnapshotService {

    private final VehicleRepository vehicleRepository;

    public VehicleLocationSnapshotService(VehicleRepository vehicleRepository) {
        this.vehicleRepository = vehicleRepository;
    }

    public void apply(VehicleLocationEvent event) {
        if (!event.isSnapshotApplied()) {
            return;
        }

        Vehicle vehicle = vehicleRepository.findByIdForLocationUpdate(event.getVehicleId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "车辆不存在"));
        vehicle.applyLocationSnapshot(
                event.getLocation(),
                event.getStandardizedAddress(),
                event.getSource(),
                event.getCoordinateSystem(),
                event.getDriverReportedAt(),
                event.getRecordedAt(),
                event.getId(),
                event.getVehicleTaskId());
    }
}
