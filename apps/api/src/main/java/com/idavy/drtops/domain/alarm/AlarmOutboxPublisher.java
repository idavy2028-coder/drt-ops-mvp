package com.idavy.drtops.domain.alarm;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class AlarmOutboxPublisher {
    private static final int CLAIM_BATCH_SIZE = 50;
    private final VehicleAlarmOutboxRepository outbox;
    private final AlarmEventStreamService stream;
    private final ReentrantLock publishLock = new ReentrantLock(true);

    AlarmOutboxPublisher(
            VehicleAlarmOutboxRepository outbox,
            AlarmEventStreamService stream) {
        this.outbox = Objects.requireNonNull(outbox);
        this.stream = Objects.requireNonNull(stream);
    }

    @Scheduled(fixedDelay = 1_000)
    @Transactional
    public void scheduledPublish() {
        publishPending();
    }

    @Scheduled(fixedDelay = 3_600_000)
    @Transactional
    public void scheduledCleanup() {
        cleanupPublishedBefore(Instant.now().minus(Duration.ofDays(7)));
    }

    @Transactional
    public int publishPending() {
        publishLock.lock();
        boolean releaseAfterTransaction = false;
        try {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        publishLock.unlock();
                    }
                });
                releaseAfterTransaction = true;
            }
            return publishClaimed();
        } finally {
            if (!releaseAfterTransaction) publishLock.unlock();
        }
    }

    private int publishClaimed() {
        List<VehicleAlarmOutboxEvent> events = new ArrayList<>(outbox.claimPending(CLAIM_BATCH_SIZE));
        events.sort(Comparator.comparing(VehicleAlarmOutboxEvent::getCreatedAt)
                .thenComparing(VehicleAlarmOutboxEvent::getId, AlarmOutboxPublisher::compareUuid));
        for (VehicleAlarmOutboxEvent event : events) {
            stream.publish(event);
            event.markPublished(Instant.now());
        }
        return events.size();
    }

    @Transactional
    public int cleanupPublishedBefore(Instant cutoff) {
        if (cutoff == null) throw new IllegalArgumentException("cleanup cutoff is required");
        return outbox.deletePublishedBefore(cutoff);
    }

    private static int compareUuid(UUID left, UUID right) {
        int mostSignificant = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return mostSignificant != 0
                ? mostSignificant
                : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    }
}
