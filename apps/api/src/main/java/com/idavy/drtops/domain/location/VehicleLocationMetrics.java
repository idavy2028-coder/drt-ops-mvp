package com.idavy.drtops.domain.location;

import com.idavy.drtops.domain.fleet.VehicleRepository;
import com.idavy.drtops.domain.task.TaskStatus;
import com.idavy.drtops.domain.task.TaskStop;
import com.idavy.drtops.domain.task.VehicleTask;
import com.idavy.drtops.domain.task.VehicleTaskRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class VehicleLocationMetrics {

    private static final String MANUAL_SOURCE = LocationSource.MANUAL_DISPATCHER.name();
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(30);
    private static final Set<TaskStatus> TRACKED_TASK_STATUSES =
            EnumSet.of(TaskStatus.IN_PROGRESS, TaskStatus.COMPLETED);

    private final MeterRegistry registry;
    private final VehicleLocationEventRepository eventRepository;
    private final VehicleRepository vehicleRepository;
    private final VehicleTaskRepository vehicleTaskRepository;
    private final Clock clock;
    private final AtomicInteger missingTaskNodes = new AtomicInteger();
    private final boolean enabled;

    @Autowired
    public VehicleLocationMetrics(
            MeterRegistry registry,
            VehicleLocationEventRepository eventRepository,
            VehicleRepository vehicleRepository,
            VehicleTaskRepository vehicleTaskRepository) {
        this(registry, eventRepository, vehicleRepository, vehicleTaskRepository, Clock.systemUTC(), true);
    }

    VehicleLocationMetrics(
            MeterRegistry registry,
            VehicleLocationEventRepository eventRepository,
            VehicleRepository vehicleRepository,
            VehicleTaskRepository vehicleTaskRepository,
            Clock clock,
            boolean enabled) {
        this.registry = registry;
        this.eventRepository = eventRepository;
        this.vehicleRepository = vehicleRepository;
        this.vehicleTaskRepository = vehicleTaskRepository;
        this.clock = clock;
        this.enabled = enabled;
        if (enabled) {
            Gauge.builder("drt.vehicle.location.stale.count", this, VehicleLocationMetrics::staleVehicleCount)
                    .register(registry);
            Gauge.builder("drt.vehicle.location.missing_task_nodes", missingTaskNodes, AtomicInteger::get)
                    .register(registry);
        }
    }

    static VehicleLocationMetrics noop() {
        return new VehicleLocationMetrics(new SimpleMeterRegistry(), null, null, null, Clock.systemUTC(), false);
    }

    void recordSuccess(VehicleLocationEvent event) {
        if (!enabled) {
            return;
        }
        reportCounter("success").increment();
        Timer.builder("drt.vehicle.location.recording.delay")
                .register(registry)
                .record(Duration.between(event.getDriverReportedAt(), event.getRecordedAt()));
        if (event.isOutsideServiceArea()) {
            Counter.builder("drt.vehicle.location.outside_area.total").register(registry).increment();
        }
        if (event.getEventType() == LocationEventType.MANUAL_CORRECTION) {
            Counter.builder("drt.vehicle.location.correction.total").register(registry).increment();
        }
    }

    void recordReplay(LocationSource source) {
        if (enabled) {
            reportCounter("replay", source).increment();
        }
    }

    void recordFailure(LocationSource source) {
        if (enabled) {
            reportCounter("failure", source).increment();
        }
    }

    <T> T recordQuery(Supplier<T> supplier) {
        if (!enabled) {
            return supplier.get();
        }
        return Timer.builder("drt.vehicle.location.query.duration")
                .register(registry)
                .record(() -> {
                    refreshMissingTaskNodes();
                    return supplier.get();
                });
    }

    private Counter reportCounter(String result) {
        return reportCounter(result, LocationSource.MANUAL_DISPATCHER);
    }

    private Counter reportCounter(String result, LocationSource source) {
        return Counter.builder("drt.vehicle.location.report.total")
                .tag("result", result)
                .tag("source", source == null ? MANUAL_SOURCE : source.name())
                .register(registry);
    }

    private void refreshMissingTaskNodes() {
        if (vehicleTaskRepository == null || eventRepository == null) {
            return;
        }
        int missingCount = vehicleTaskRepository.findAllByOrderByPlannedStartAtAsc().stream()
                .filter(task -> TRACKED_TASK_STATUSES.contains(task.getStatus()))
                .mapToInt(this::missingTaskNodeCount)
                .sum();
        missingTaskNodes.set(missingCount);
    }

    private int missingTaskNodeCount(VehicleTask task) {
        List<VehicleLocationEvent> events = eventRepository.findByVehicleTaskIdOrderByDriverReportedAtAsc(task.getId());
        int missing = 0;
        if (missingTaskLevelEvent(events, LocationEventType.TASK_STARTED)) {
            missing++;
        }
        for (TaskStop stop : task.getStops()) {
            if ("BOARDING".equals(stop.getStopType())) {
                missing += missingStopEvent(events, stop, LocationEventType.PICKUP_ARRIVED);
                missing += missingStopEvent(events, stop, LocationEventType.PASSENGER_BOARDED);
            } else if ("ALIGHTING".equals(stop.getStopType())) {
                missing += missingStopEvent(events, stop, LocationEventType.DROPOFF_ARRIVED);
                missing += missingStopEvent(events, stop, LocationEventType.PASSENGER_ALIGHTED);
            }
        }
        if (missingTaskLevelEvent(events, LocationEventType.TASK_COMPLETED)) {
            missing++;
        }
        return missing;
    }

    private static boolean missingTaskLevelEvent(List<VehicleLocationEvent> events, LocationEventType eventType) {
        return events.stream().noneMatch(event -> event.getEventType() == eventType);
    }

    private static int missingStopEvent(
            List<VehicleLocationEvent> events,
            TaskStop stop,
            LocationEventType eventType) {
        boolean present = events.stream().anyMatch(event ->
                event.getEventType() == eventType && stop.getId().equals(event.getTaskStopId()));
        return present ? 0 : 1;
    }

    private double staleVehicleCount() {
        if (vehicleRepository == null) {
            return 0;
        }
        OffsetDateTime staleBefore = OffsetDateTime.now(clock).minus(STALE_THRESHOLD);
        return vehicleRepository.findAll().stream()
                .filter(vehicle -> vehicle.getCurrentLocationTaskId() != null)
                .filter(vehicle -> vehicle.getCurrentLocationReportedAt() != null)
                .filter(vehicle -> vehicle.getCurrentLocationReportedAt().isBefore(staleBefore))
                .count();
    }
}
