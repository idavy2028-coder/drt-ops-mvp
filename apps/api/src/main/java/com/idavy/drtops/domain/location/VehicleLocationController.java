package com.idavy.drtops.domain.location;

import com.idavy.drtops.common.ApiResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class VehicleLocationController {

    private final VehicleLocationCommandService commandService;
    private final VehicleLocationQueryService queryService;
    private final VehicleLocationExportService exportService;

    public VehicleLocationController(
            VehicleLocationCommandService commandService,
            VehicleLocationQueryService queryService,
            VehicleLocationExportService exportService) {
        this.commandService = commandService;
        this.queryService = queryService;
        this.exportService = exportService;
    }

    @PostMapping("/vehicles/{vehicleId}/location-reports")
    @PreAuthorize("(#request.correctsEventId() != null || #request.eventType() == "
            + "T(com.idavy.drtops.domain.location.LocationEventType).MANUAL_CORRECTION) "
            + "? hasAuthority('LOCATION_CORRECT') : hasAuthority('LOCATION_REPORT')")
    public ResponseEntity<ApiResponse<LocationReportResponse>> report(
            Authentication authentication, @PathVariable UUID vehicleId, @Valid @RequestBody LocationReportRequest request) {
        LocationReportResponse response = commandService.report(vehicleId, actorId(authentication), request);
        return ResponseEntity.status(response.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @GetMapping("/vehicles/locations/latest")
    @PreAuthorize("hasAuthority('LOCATION_READ')")
    public ApiResponse<List<VehicleLocationQueryService.VehicleLocationSnapshotItem>> latest() {
        return ApiResponse.ok(queryService.latest());
    }

    @GetMapping("/vehicles/{vehicleId}/location-events")
    @PreAuthorize("hasAuthority('LOCATION_READ')")
    public ApiResponse<List<VehicleLocationView>> history(
            @PathVariable UUID vehicleId,
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to,
            @RequestParam(required = false) LocalDate date,
            @RequestParam(required = false) UUID taskId,
            @RequestParam(required = false) LocationEventType eventType) {
        return ApiResponse.ok(queryService.history(vehicleId, from, to, date, taskId, eventType));
    }

    @GetMapping("/vehicle-tasks/{taskId}/location-events")
    @PreAuthorize("hasAuthority('LOCATION_READ')")
    public ApiResponse<List<VehicleLocationView>> taskHistory(
            @PathVariable UUID taskId,
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to,
            @RequestParam(required = false) LocalDate date) {
        return ApiResponse.ok(queryService.taskHistory(taskId, from, to, date));
    }

    @GetMapping("/vehicle-locations/export.csv")
    @PreAuthorize("hasAuthority('LOCATION_EXPORT')")
    public ResponseEntity<byte[]> export(
            Authentication authentication,
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to,
            @RequestParam(required = false) LocalDate date,
            @RequestParam(required = false) UUID taskId,
            @RequestParam(required = false) LocationEventType eventType) {
        byte[] body = exportService.export(actorId(authentication), from, to, date, taskId, eventType);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=vehicle-locations.csv").body(body);
    }

    private static UUID actorId(Authentication authentication) { return (UUID) authentication.getPrincipal(); }
}
