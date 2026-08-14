package com.idavy.drtops.domain.alarm;

import java.time.Instant;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AlarmOutboxPublisher {
    private static final int CLAIM_BATCH_SIZE = 50;
    private final VehicleAlarmOutboxRepository outbox;
    private final VehicleAlarmRepository alarms;
    private final AlarmEventStreamService stream;

    AlarmOutboxPublisher(
            VehicleAlarmOutboxRepository outbox,
            VehicleAlarmRepository alarms,
            AlarmEventStreamService stream) {
        this.outbox = Objects.requireNonNull(outbox);
        this.alarms = Objects.requireNonNull(alarms);
        this.stream = Objects.requireNonNull(stream);
    }

    @Scheduled(fixedDelay = 1_000)
    public void scheduledPublish() {
        publishPending();
    }

    @Transactional
    public int publishPending() {
        int published = 0;
        for (VehicleAlarmOutboxEvent event : outbox.claimPending(CLAIM_BATCH_SIZE)) {
            VehicleAlarm alarm = alarms.findById(event.getVehicleAlarmId())
                    .orElseThrow(() -> new IllegalStateException("outbox alarm is missing"));
            stream.publish(event, alarm);
            event.markPublished(Instant.now());
            published++;
        }
        return published;
    }

    @Transactional
    public int cleanupPublishedBefore(Instant cutoff) {
        if (cutoff == null) throw new IllegalArgumentException("cleanup cutoff is required");
        return outbox.deletePublishedBefore(cutoff);
    }
}
