package com.idavy.drtops.domain.location;

import com.idavy.drtops.domain.fleet.VehicleRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;

@Service
public class VehicleLocationQueryService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final VehicleLocationEventRepository eventRepository;
    private final VehicleRepository vehicleRepository;
    private final VehicleLocationMetrics metrics;

    public VehicleLocationQueryService(
            VehicleLocationEventRepository eventRepository,
            VehicleRepository vehicleRepository,
            VehicleLocationMetrics metrics) {
        this.eventRepository = eventRepository;
        this.vehicleRepository = vehicleRepository;
        this.metrics = metrics;
    }

    @PreAuthorize("hasAuthority('LOCATION_READ')")
    public List<VehicleLocationView> history(
            UUID vehicleId, OffsetDateTime from, OffsetDateTime to, LocalDate date, UUID taskId, LocationEventType eventType) {
        return metrics.recordQuery(() -> {
            TimeRange range = range(from, to, date);
            return eventRepository.findByVehicleIdOrderByDriverReportedAtDesc(vehicleId).stream()
                    .filter(event -> matches(event, range, taskId, eventType))
                    .sorted(Comparator.comparing(VehicleLocationEvent::getDriverReportedAt))
                    .map(VehicleLocationView::from)
                    .toList();
        });
    }

    @PreAuthorize("hasAuthority('LOCATION_READ')")
    public List<VehicleLocationView> taskHistory(UUID taskId, OffsetDateTime from, OffsetDateTime to, LocalDate date) {
        return metrics.recordQuery(() -> {
            TimeRange range = range(from, to, date);
            return eventRepository.findByVehicleTaskIdOrderByDriverReportedAtAsc(taskId).stream()
                    .filter(event -> matches(event, range, null, null))
                    .map(VehicleLocationView::from)
                    .toList();
        });
    }

    @PreAuthorize("hasAuthority('LOCATION_READ')")
    public List<VehicleLocationSnapshotItem> latest() {
        return metrics.recordQuery(() -> vehicleRepository.findAll().stream()
                    .map(vehicle -> new VehicleLocationSnapshotItem(
                            vehicle.getId(), vehicle.getPlateNumber(), vehicle.getCurrentStatus(), VehicleLocationSnapshotView.from(vehicle)))
                    .filter(item -> item.latestLocation() != null)
                    .toList());
    }

    @PreAuthorize("hasAuthority('LOCATION_EXPORT')")
    public List<VehicleLocationView> export(
            OffsetDateTime from, OffsetDateTime to, LocalDate date, UUID taskId, LocationEventType eventType) {
        return metrics.recordQuery(() -> {
            TimeRange range = range(from, to, date);
            return eventRepository.findAll().stream()
                    .filter(event -> matches(event, range, taskId, eventType))
                    .sorted(Comparator.comparing(VehicleLocationEvent::getDriverReportedAt))
                    .map(VehicleLocationView::from)
                    .toList();
        });
    }

    private static boolean matches(VehicleLocationEvent event, TimeRange range, UUID taskId, LocationEventType eventType) {
        return (range.from() == null || !event.getDriverReportedAt().isBefore(range.from()))
                && (range.to() == null || event.getDriverReportedAt().isBefore(range.to()))
                && (taskId == null || taskId.equals(event.getVehicleTaskId()))
                && (eventType == null || eventType == event.getEventType());
    }

    private static TimeRange range(OffsetDateTime from, OffsetDateTime to, LocalDate date) {
        if (date == null) {
            return new TimeRange(from, to);
        }
        return new TimeRange(date.atStartOfDay(SHANGHAI).toOffsetDateTime(),
                date.plusDays(1).atStartOfDay(SHANGHAI).toOffsetDateTime());
    }

    record VehicleLocationSnapshotItem(UUID vehicleId, String plateNumber, String currentStatus, VehicleLocationSnapshotView latestLocation) { }
    private record TimeRange(OffsetDateTime from, OffsetDateTime to) { }
}
