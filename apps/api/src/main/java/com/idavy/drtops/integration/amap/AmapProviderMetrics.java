package com.idavy.drtops.integration.amap;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
class AmapProviderMetrics {

    private final MeterRegistry registry;

    AmapProviderMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    <T> T record(String operation, Supplier<T> action) {
        Counter.builder("drt.map.provider.call.total")
                .tag("operation", operation)
                .register(registry)
                .increment();
        Timer.Sample sample = Timer.start(registry);
        String result = "success";
        try {
            return action.get();
        } catch (RuntimeException exception) {
            result = "failure";
            throw exception;
        } finally {
            Counter.builder("drt.map.provider.request.total")
                    .tag("operation", operation)
                    .tag("result", result)
                    .register(registry)
                    .increment();
            sample.stop(Timer.builder("drt.map.provider.request.duration")
                    .tag("operation", operation)
                    .tag("result", result)
                    .register(registry));
        }
    }

    void recordDegraded(String operation, String reason) {
        Counter.builder("drt.map.provider.degraded.total")
                .tag("operation", operation)
                .tag("reason", reason)
                .register(registry)
                .increment();
    }
}
