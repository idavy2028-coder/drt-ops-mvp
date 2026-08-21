package com.idavy.drtops.jtgateway.ingress;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Shared, sanitized reachability state updated by registry and ingress API calls. */
public final class OperationsApiStatus {
    private final Clock clock;
    private final AtomicReference<Snapshot> current = new AtomicReference<>(
            new Snapshot(State.UNKNOWN, "NONE", null));

    public OperationsApiStatus(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void success(String operation) {
        current.set(new Snapshot(State.UP, requireOperation(operation), clock.instant()));
    }

    public void failure(String operation) {
        current.set(new Snapshot(State.DOWN, requireOperation(operation), clock.instant()));
    }

    public Snapshot snapshot() {
        return current.get();
    }

    private static String requireOperation(String operation) {
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("operation must not be blank");
        }
        return operation;
    }

    public enum State { UNKNOWN, UP, DOWN }

    public record Snapshot(State state, String operation, Instant checkedAt) { }
}
