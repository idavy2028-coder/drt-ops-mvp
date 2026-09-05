package com.idavy.drtops.jtgateway.ingress;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class GatewayOutboxDispatcher {
    private static final int MAX_URGENT_BATCH = 50;
    private static final int MAX_LOCATION_BATCH = 50;

    private final GatewayOutboxRepository repository;
    private final DeliveryClient deliveryClient;
    private final Clock clock;
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maximumBackoff;

    public GatewayOutboxDispatcher(
            GatewayOutboxRepository repository,
            DeliveryClient deliveryClient,
            Clock clock,
            int maxAttempts,
            Duration initialBackoff,
            Duration maximumBackoff) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.deliveryClient = Objects.requireNonNull(deliveryClient, "deliveryClient");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        if (initialBackoff.isNegative() || initialBackoff.isZero()
                || maximumBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("backoff configuration is invalid");
        }
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.maximumBackoff = maximumBackoff;
    }

    public synchronized DispatchReport dispatchOnce() {
        Instant dependencyClaimAt = clock.instant();
        DispatchReport report = deliver(repository.claimHighPriorityDependencies(
                dependencyClaimAt, MAX_URGENT_BATCH));
        if (report.attempted() != report.delivered()) {
            return report;
        }
        Instant auditClaimAt = clock.instant();
        report = report.plus(deliverIndividually(repository.claimSessionAudits(
                auditClaimAt, MAX_URGENT_BATCH)));
        Instant urgentClaimAt = clock.instant();
        report = report.plus(deliver(repository.claimEligible(
                urgentClaimAt, GatewayOutboxRepository.Priority.HIGH, MAX_URGENT_BATCH)));
        Instant locationClaimAt = clock.instant();
        List<GatewayOutboxRepository.OutboxEntry> locations = repository.claimEligible(
                locationClaimAt, GatewayOutboxRepository.Priority.LOCATION, MAX_LOCATION_BATCH);
        if (!locations.isEmpty()) {
            report = report.plus(deliver(locations));
        }
        return report;
    }

    private DispatchReport deliverIndividually(List<GatewayOutboxRepository.OutboxEntry> entries) {
        DispatchReport report = DispatchReport.empty();
        for (GatewayOutboxRepository.OutboxEntry entry : entries) {
            report = report.plus(deliver(List.of(entry)));
        }
        return report;
    }

    private DispatchReport deliver(List<GatewayOutboxRepository.OutboxEntry> entries) {
        if (entries.isEmpty()) {
            return DispatchReport.empty();
        }
        DeliveryResult result;
        try {
            result = Objects.requireNonNull(
                    deliveryClient.deliver(entries.stream()
                            .map(GatewayOutboxRepository.OutboxEntry::toEnvelope)
                            .toList()),
                    "deliveryClient returned null");
        } catch (RuntimeException unavailable) {
            result = DeliveryResult.retryable("CLIENT_UNAVAILABLE");
        }
        Instant completedAt = clock.instant();
        if (result.successful()) {
            repository.markDelivered(entries, completedAt);
            return new DispatchReport(entries.size(), entries.size(), 0, 0);
        }

        int retried = 0;
        int deadLettered = 0;
        List<GatewayOutboxRepository.FailureUpdate> updates =
                new ArrayList<>(entries.size());
        for (GatewayOutboxRepository.OutboxEntry entry : entries) {
            int attempt = entry.attemptCount() + 1;
            if (attempt >= maxAttempts) {
                updates.add(new GatewayOutboxRepository.FailureUpdate(
                        entry, attempt, completedAt, true, result.errorCode()));
                deadLettered++;
            } else {
                updates.add(new GatewayOutboxRepository.FailureUpdate(
                        entry,
                        attempt,
                        completedAt.plus(backoffFor(attempt)),
                        false,
                        result.errorCode()));
                retried++;
            }
        }
        repository.markFailed(updates);
        return new DispatchReport(entries.size(), 0, retried, deadLettered);
    }

    private Duration backoffFor(int attempt) {
        long multiplier = 1L << Math.min(attempt - 1, 30);
        try {
            Duration candidate = initialBackoff.multipliedBy(multiplier);
            return candidate.compareTo(maximumBackoff) > 0 ? maximumBackoff : candidate;
        } catch (ArithmeticException overflow) {
            return maximumBackoff;
        }
    }

    @FunctionalInterface
    public interface DeliveryClient {
        DeliveryResult deliver(List<GatewayIngressEnvelope> batch);
    }

    public record DeliveryResult(boolean successful, String errorCode) {
        public DeliveryResult {
            if (successful == (errorCode != null)) {
                throw new IllegalArgumentException("delivery result is inconsistent");
            }
        }

        public static DeliveryResult success() {
            return new DeliveryResult(true, null);
        }

        public static DeliveryResult retryable(String errorCode) {
            if (errorCode == null || errorCode.isBlank()) {
                throw new IllegalArgumentException("errorCode must not be blank");
            }
            return new DeliveryResult(false, errorCode);
        }
    }

    public record DispatchReport(int attempted, int delivered, int retried, int deadLettered) {
        private static DispatchReport empty() {
            return new DispatchReport(0, 0, 0, 0);
        }

        private DispatchReport plus(DispatchReport other) {
            return new DispatchReport(
                    attempted + other.attempted,
                    delivered + other.delivered,
                    retried + other.retried,
                    deadLettered + other.deadLettered);
        }
    }
}
