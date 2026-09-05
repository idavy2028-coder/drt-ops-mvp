package com.idavy.drtops.domain.fleet;

import com.idavy.drtops.domain.audit.AuditLog;
import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.domain.location.LocationEventType;
import com.idavy.drtops.domain.location.LocationReportCommand;
import com.idavy.drtops.domain.location.LocationReportResult;
import com.idavy.drtops.domain.location.LocationReportScope;
import com.idavy.drtops.domain.location.VehicleLocationRecorder;
import com.idavy.drtops.domain.location.VehicleLocationSnapshotService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VehicleProvisioningService {

    private static final String INITIAL_LOCATION_ADDRESS = "车辆首配位置";
    private static final String INITIAL_LOCATION_NOTE = "车辆创建时首配位置";

    private final VehicleRepository vehicleRepository;
    private final VehicleLocationRecorder locationRecorder;
    private final VehicleLocationSnapshotService locationSnapshotService;
    private final AuditLogRepository auditLogRepository;

    public VehicleProvisioningService(
            VehicleRepository vehicleRepository,
            VehicleLocationRecorder locationRecorder,
            VehicleLocationSnapshotService locationSnapshotService,
            AuditLogRepository auditLogRepository) {
        this.vehicleRepository = vehicleRepository;
        this.locationRecorder = locationRecorder;
        this.locationSnapshotService = locationSnapshotService;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public Vehicle create(
            String plateNumber,
            String vehicleType,
            int capacity,
            String currentStatus,
            BigDecimal longitude,
            BigDecimal latitude,
            String fleetName,
            boolean dispatchable,
            String reason,
            UUID actorId) {
        Vehicle vehicle = vehicleRepository.save(Vehicle.create(
                UUID.randomUUID(), plateNumber, vehicleType, capacity, currentStatus,
                "POINT(" + longitude.toPlainString() + " " + latitude.toPlainString() + ")",
                fleetName, dispatchable));

        OffsetDateTime reportedAt = OffsetDateTime.now();
        LocationReportResult locationResult = locationRecorder.append(new LocationReportCommand(
                LocationReportScope.INDEPENDENT_REPORT,
                vehicle.getId(), null, null, null, LocationEventType.MANUAL_REPORT,
                longitude, latitude, INITIAL_LOCATION_ADDRESS, reportedAt, actorId,
                INITIAL_LOCATION_NOTE, null, null, UUID.randomUUID()));
        locationSnapshotService.apply(locationResult.event());
        auditLogRepository.save(AuditLog.record(
                "VEHICLE", vehicle.getId(), "VEHICLE_CREATED", "USER", actorId.toString(), reason,
                "{\"locationEventId\":\"" + locationResult.event().getId() + "\"}"));
        return vehicle;
    }
}
