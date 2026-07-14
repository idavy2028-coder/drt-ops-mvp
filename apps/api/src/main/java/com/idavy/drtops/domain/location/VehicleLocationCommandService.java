package com.idavy.drtops.domain.location;

import com.idavy.drtops.domain.audit.AuditLog;
import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.domain.area.VirtualStopRepository;
import com.idavy.drtops.domain.task.TaskStop;
import com.idavy.drtops.domain.task.VehicleTask;
import com.idavy.drtops.domain.task.VehicleTaskRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class VehicleLocationCommandService {

    private final VehicleLocationRecorder recorder;
    private final VehicleLocationSnapshotService snapshotService;
    private final AuditLogRepository auditLogRepository;
    private final VehicleTaskRepository vehicleTaskRepository;
    private final VirtualStopRepository virtualStopRepository;

    public VehicleLocationCommandService(
            VehicleLocationRecorder recorder,
            VehicleLocationSnapshotService snapshotService,
            AuditLogRepository auditLogRepository,
            VehicleTaskRepository vehicleTaskRepository,
            VirtualStopRepository virtualStopRepository) {
        this.recorder = recorder;
        this.snapshotService = snapshotService;
        this.auditLogRepository = auditLogRepository;
        this.vehicleTaskRepository = vehicleTaskRepository;
        this.virtualStopRepository = virtualStopRepository;
    }

    @Transactional
    @PreAuthorize("(#request.correctsEventId() != null || #request.eventType() == "
            + "T(com.idavy.drtops.domain.location.LocationEventType).MANUAL_CORRECTION) "
            + "? hasAuthority('LOCATION_CORRECT') : hasAuthority('LOCATION_REPORT')")
    public LocationReportResponse report(UUID vehicleId, UUID actorId, LocationReportRequest request) {
        validateAssociations(vehicleId, request);
        LocationReportResult result = recorder.append(new LocationReportCommand(
                vehicleId, request.vehicleTaskId(), request.taskStopId(), request.virtualStopId(), request.eventType(),
                request.longitude(), request.latitude(), request.standardizedAddress(), request.driverReportedAt(), actorId,
                request.note(), request.correctionReason(), request.correctsEventId(), request.idempotencyKey()));
        if (result.replayed()) {
            return LocationReportResponse.from(result);
        }
        snapshotService.apply(result.event());
        String action = result.event().getEventType() == LocationEventType.MANUAL_CORRECTION
                ? "VEHICLE_LOCATION_CORRECTED" : "VEHICLE_LOCATION_REPORTED";
        auditLogRepository.save(AuditLog.record(
                "VEHICLE", vehicleId, action, "USER", actorId.toString(), null,
                "{\"eventId\":\"" + result.event().getId() + "\",\"snapshotApplied\":"
                        + result.event().isSnapshotApplied() + "}"));
        return LocationReportResponse.from(result);
    }

    private void validateAssociations(UUID vehicleId, LocationReportRequest request) {
        VehicleTask task = null;
        if (request.vehicleTaskId() != null) {
            task = vehicleTaskRepository.findWithStopsById(request.vehicleTaskId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "车辆任务不存在"));
            if (!vehicleId.equals(task.getVehicleId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "车辆任务不属于当前车辆");
            }
        }

        TaskStop taskStop = null;
        if (request.taskStopId() != null) {
            if (task == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "任务节点必须关联车辆任务");
            }
            taskStop = task.getStops().stream()
                    .filter(stop -> request.taskStopId().equals(stop.getId()))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "任务节点不存在或不属于车辆任务"));
        }

        if (request.virtualStopId() != null && !virtualStopRepository.existsById(request.virtualStopId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "虚拟站点不存在");
        }
        if (taskStop != null && !taskStop.getVirtualStopId().equals(request.virtualStopId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "虚拟站点与任务节点不一致");
        }
    }
}
