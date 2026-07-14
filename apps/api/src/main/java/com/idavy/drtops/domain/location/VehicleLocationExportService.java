package com.idavy.drtops.domain.location;

import com.idavy.drtops.domain.audit.AuditLog;
import com.idavy.drtops.domain.audit.AuditLogRepository;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VehicleLocationExportService {

    private final VehicleLocationQueryService queryService;
    private final AuditLogRepository auditLogRepository;

    public VehicleLocationExportService(VehicleLocationQueryService queryService, AuditLogRepository auditLogRepository) {
        this.queryService = queryService;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    @PreAuthorize("hasAuthority('LOCATION_EXPORT')")
    public byte[] export(UUID actorId, OffsetDateTime from, OffsetDateTime to, LocalDate date, UUID taskId, LocationEventType eventType) {
        List<VehicleLocationView> rows = queryService.export(from, to, date, taskId, eventType);
        StringBuilder csv = new StringBuilder("\uFEFFid,vehicleId,vehicleTaskId,eventType,longitude,latitude,address,source,driverReportedAt,recordedAt\r\n");
        for (VehicleLocationView row : rows) {
            csv.append(row.id()).append(',').append(row.vehicleId()).append(',').append(value(row.vehicleTaskId())).append(',')
                    .append(row.eventType()).append(',').append(row.longitude()).append(',').append(row.latitude()).append(',')
                    .append(safeTextCell(row.standardizedAddress())).append(',').append(row.source()).append(',')
                    .append(row.driverReportedAt()).append(',').append(row.recordedAt()).append("\r\n");
        }
        auditLogRepository.save(AuditLog.record(
                "VEHICLE_LOCATION_EXPORT", actorId, "VEHICLE_LOCATION_EXPORT", "USER", actorId.toString(), null,
                "{\"from\":\"" + value(from) + "\",\"to\":\"" + value(to) + "\",\"date\":\"" + value(date)
                        + "\",\"taskId\":\"" + value(taskId) + "\",\"eventType\":\"" + value(eventType)
                        + "\",\"recordCount\":" + rows.size() + "}"));
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String safeTextCell(String value) {
        String safeValue = startsWithFormulaMarker(value) ? "'" + value : value;
        return '"' + safeValue.replace("\"", "\"\"") + '"';
    }

    private static boolean startsWithFormulaMarker(String value) {
        if (value.isEmpty()) {
            return false;
        }
        return switch (value.charAt(0)) {
            case '=', '+', '-', '@', '\t', '\r' -> true;
            default -> false;
        };
    }

    private static String value(Object value) { return value == null ? "" : value.toString(); }
}
