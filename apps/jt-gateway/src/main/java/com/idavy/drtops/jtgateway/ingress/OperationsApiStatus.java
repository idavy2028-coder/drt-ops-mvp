package com.idavy.drtops.jtgateway.ingress;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Sanitized operations API reachability facts with source isolation and bounded freshness. */
public final class OperationsApiStatus {
    private static final Duration DEFAULT_FRESHNESS_TTL = Duration.ofSeconds(90);

    private final Clock clock;
    private final Duration freshnessTtl;
    private final AtomicReference<Map<Source, Observation>> current;

    public OperationsApiStatus(Clock clock) {
        this(clock, DEFAULT_FRESHNESS_TTL);
    }

    public OperationsApiStatus(Clock clock, Duration freshnessTtl) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (freshnessTtl == null || freshnessTtl.isZero() || freshnessTtl.isNegative()) {
            throw new IllegalArgumentException("freshnessTtl must be positive");
        }
        this.freshnessTtl = freshnessTtl;
        EnumMap<Source, Observation> initial = new EnumMap<>(Source.class);
        for (Source source : Source.values()) {
            initial.put(source, new Observation(State.UNKNOWN, "NONE", null));
        }
        current = new AtomicReference<>(Map.copyOf(initial));
    }

    public void success(Source source, String operation) {
        record(source, State.UP, operation);
    }

    public void failure(Source source, String operation) {
        record(source, State.DOWN, operation);
    }

    public Snapshot snapshot() {
        Instant now = clock.instant();
        Map<Source, Observation> observations = current.get();
        EnumMap<Source, SourceSnapshot> sources = new EnumMap<>(Source.class);
        Observation latestHistorical = null;
        Observation latestFailure = null;
        Observation latestSuccess = null;
        for (Source source : Source.values()) {
            Observation observation = observations.get(source);
            boolean fresh = isFresh(observation, now);
            sources.put(source, new SourceSnapshot(
                    observation.state(), observation.operation(), observation.checkedAt(), fresh));
            latestHistorical = later(latestHistorical, observation);
            if (fresh && observation.state() == State.DOWN) {
                latestFailure = later(latestFailure, observation);
            } else if (fresh && observation.state() == State.UP && source != Source.PROBE) {
                latestSuccess = later(latestSuccess, observation);
            }
        }
        if (latestFailure != null) {
            return aggregate(latestFailure, sources);
        }
        if (latestSuccess != null) {
            return aggregate(latestSuccess, sources);
        }
        if (latestHistorical != null && latestHistorical.checkedAt() != null) {
            String operation = observations.entrySet().stream()
                    .anyMatch(entry -> entry.getKey() != Source.PROBE
                            && entry.getValue().checkedAt() != null)
                    ? "STALE" : "AUTHENTICATED_CONTRACT_REQUIRED";
            return new Snapshot(
                    State.DOWN, operation, latestHistorical.checkedAt(), Map.copyOf(sources));
        }
        return new Snapshot(State.UNKNOWN, "NONE", null, Map.copyOf(sources));
    }

    private void record(Source source, State state, String operation) {
        Objects.requireNonNull(source, "source");
        Observation observation = new Observation(state, requireOperation(operation), clock.instant());
        current.updateAndGet(previous -> {
            EnumMap<Source, Observation> updated = new EnumMap<>(previous);
            updated.put(source, observation);
            return Map.copyOf(updated);
        });
    }

    private boolean isFresh(Observation observation, Instant now) {
        return observation.checkedAt() != null
                && !now.isAfter(observation.checkedAt().plus(freshnessTtl));
    }

    private static Observation later(Observation current, Observation candidate) {
        if (candidate.checkedAt() == null) {
            return current;
        }
        return current == null || candidate.checkedAt().isAfter(current.checkedAt())
                ? candidate : current;
    }

    private static Snapshot aggregate(
            Observation observation, EnumMap<Source, SourceSnapshot> sources) {
        return new Snapshot(observation.state(), observation.operation(), observation.checkedAt(),
                Map.copyOf(sources));
    }

    private static String requireOperation(String operation) {
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("operation must not be blank");
        }
        return operation;
    }

    public enum State { UNKNOWN, UP, DOWN }

    public enum Source { REGISTRY, INGRESS, PROBE }

    public record SourceSnapshot(
            State state, String operation, Instant checkedAt, boolean fresh) { }

    public record Snapshot(
            State state,
            String operation,
            Instant checkedAt,
            Map<Source, SourceSnapshot> sources) { }

    private record Observation(State state, String operation, Instant checkedAt) { }
}
