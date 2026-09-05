package com.idavy.drtops.jtgateway;

import com.idavy.drtops.jtgateway.ingress.GatewayOutboxDispatcher;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs bounded outbox delivery batches without exposing payloads or credentials in logs. */
public final class GatewayDispatchScheduler {
    private final GatewayOutboxDispatcher dispatcher;

    public GatewayDispatchScheduler(GatewayOutboxDispatcher dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    @Scheduled(
            fixedDelayString = "${jt.gateway.dispatch.fixed-delay:1000}",
            initialDelayString = "${jt.gateway.dispatch.initial-delay:1000}",
            scheduler = "gatewayDispatchTaskScheduler")
    void dispatch() {
        try {
            dispatcher.dispatchOnce();
        } catch (RuntimeException ignored) {
            // Health and pending-count signals carry the failure without logging event material.
        }
    }
}
