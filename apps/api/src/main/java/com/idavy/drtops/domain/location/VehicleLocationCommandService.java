package com.idavy.drtops.domain.location;

import com.idavy.drtops.domain.audit.AuditLog;
import com.idavy.drtops.domain.audit.AuditLogRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VehicleLocationCommandService {

    private final VehicleLocationRecorder recorder;
    private final VehicleLocationSnapshotService snapshotService;
    private final AuditLogRepository auditLogRepository;

    public VehicleLocationCommandService(
            VehicleLocationRecorder recorder,
            VehicleLocationSnapshotService snapshotService,
            AuditLogRepository auditLogRepository) {
        this.recorder = recorder;
        this.snapshotService = snapshotService;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public LocationReportResponse report(UUID vehicleId, UUID actorId, LocationReportRequest request) {
        LocationReportResult result = recorder.append(new LocationReportCommand(
                vehicleId, request.vehicleTaskId(), request.taskStopId(), request.virtualStopId(), request.eventType(),
                request.longitude(), request.latitude(), request.standardizedAddress(), request.driverReportedAt(), actorId,
                request.note(), request.correctionReason(), request.correctsEventId(), request.idempotencyKey()));
        if (result.replayed()) {
            return LocationReportResponse.from(result);
        }
        snapshotService.apply(result.event());
        String action = request.correctsEventId() == null
                ? "VEHICLE_LOCATION_REPORTED" : "VEHICLE_LOCATION_CORRECTED";
        auditLogRepository.save(AuditLog.record(
                "VEHICLE", vehicleId, action, "USER", actorId.toString(), null,
                "{\"eventId\":\"" + result.event().getId() + "\",\"snapshotApplied\":"
                        + result.event().isSnapshotApplied() + "}"));
        return LocationReportResponse.from(result);
    }
}
