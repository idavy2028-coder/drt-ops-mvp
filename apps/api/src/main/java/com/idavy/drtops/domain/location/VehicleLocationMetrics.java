package com.idavy.drtops.domain.location;

import com.idavy.drtops.domain.fleet.VehicleRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class VehicleLocationMetrics {

    private static final String MANUAL_SOURCE = LocationSource.MANUAL_DISPATCHER.name();
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(30);

    private final MeterRegistry registry;
    private final VehicleRepository vehicleRepository;
    private final Clock clock;
    private final AtomicInteger missingTaskNodes = new AtomicInteger();
    private final boolean enabled;

    @Autowired
    public VehicleLocationMetrics(
            MeterRegistry registry,
            VehicleLocationEventRepository eventRepository,
            VehicleRepository vehicleRepository) {
        this(registry, vehicleRepository, Clock.systemUTC(), true);
    }

    VehicleLocationMetrics(
            MeterRegistry registry,
            VehicleRepository vehicleRepository,
            Clock clock,
            boolean enabled) {
        this.registry = registry;
        this.vehicleRepository = vehicleRepository;
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
        return new VehicleLocationMetrics(new SimpleMeterRegistry(), null, Clock.systemUTC(), false);
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
                .record(supplier);
    }

    public void updateMissingTaskNodes(int count) {
        if (enabled) {
            missingTaskNodes.set(Math.max(0, count));
        }
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
