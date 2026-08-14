package com.idavy.drtops.domain.alarm;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class AlarmEventStreamService {
    private static final Duration RETENTION_WINDOW = Duration.ofDays(7);
    private final VehicleAlarmOutboxRepository outbox;
    private final VehicleAlarmRepository alarms;
    private final VehicleAlarmAuthorization authorization;
    private final CopyOnWriteArrayList<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    AlarmEventStreamService(
            VehicleAlarmOutboxRepository outbox,
            VehicleAlarmRepository alarms,
            VehicleAlarmAuthorization authorization) {
        this.outbox = Objects.requireNonNull(outbox);
        this.alarms = Objects.requireNonNull(alarms);
        this.authorization = Objects.requireNonNull(authorization);
    }

    public SseEmitter subscribe(UUID actorId, String lastEventId) {
        requireRead(actorId);
        SseEmitter emitter = new SseEmitter(0L);
        Subscriber subscriber = new Subscriber(actorId, emitter);
        subscribers.add(subscriber);
        emitter.onCompletion(() -> subscribers.remove(subscriber));
        emitter.onTimeout(() -> subscribers.remove(subscriber));
        try {
            Cursor cursor = Cursor.parse(lastEventId);
            if (cursor != null && cursor.createdAt().isBefore(Instant.now().minus(RETENTION_WINDOW))) {
                emitter.send(SseEmitter.event().name("resync-required")
                        .data(Map.of("reason", "last event is outside the seven day replay window")));
                return emitter;
            }
            if (cursor != null) replay(subscriber, cursor);
        } catch (Exception exception) {
            subscribers.remove(subscriber);
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    void publish(VehicleAlarmOutboxEvent event, VehicleAlarm alarm) {
        StreamEvent payload = StreamEvent.from(event, alarm);
        String cursor = Cursor.format(event.getCreatedAt(), event.getId());
        for (Subscriber subscriber : subscribers) {
            if (!authorization.mayContinueRead(subscriber.actorId())) {
                subscribers.remove(subscriber);
                subscriber.emitter().completeWithError(
                        new VehicleAlarmAuthorizationException("vehicle alarm stream is forbidden"));
                continue;
            }
            send(subscriber, SseEmitter.event().id(cursor).name("vehicle-alarm").data(payload));
        }
    }

    @Scheduled(fixedRate = 15_000)
    void emitHeartbeat() {
        for (Subscriber subscriber : subscribers) {
            if (!authorization.mayContinueRead(subscriber.actorId())) {
                subscribers.remove(subscriber);
                subscriber.emitter().completeWithError(
                        new VehicleAlarmAuthorizationException("vehicle alarm stream is forbidden"));
                continue;
            }
            send(subscriber, SseEmitter.event().name("heartbeat").data(Map.of("at", Instant.now().toString())));
        }
    }

    int subscriberCount() { return subscribers.size(); }

    private void replay(Subscriber subscriber, Cursor cursor) {
        List<VehicleAlarmOutboxEvent> events = outbox.findPublishedAfter(cursor.createdAt(), cursor.id());
        for (VehicleAlarmOutboxEvent event : events) {
            VehicleAlarm alarm = alarms.findById(event.getVehicleAlarmId()).orElse(null);
            if (alarm == null) continue;
            send(subscriber, SseEmitter.event().id(Cursor.format(event.getCreatedAt(), event.getId()))
                    .name("vehicle-alarm").data(StreamEvent.from(event, alarm)));
        }
    }

    private void requireRead(UUID actorId) {
        if (!authorization.mayRead(actorId)) {
            throw new VehicleAlarmAuthorizationException("vehicle alarm stream is forbidden");
        }
    }

    private void send(Subscriber subscriber, SseEmitter.SseEventBuilder event) {
        try {
            subscriber.emitter().send(event);
        } catch (Exception exception) {
            subscribers.remove(subscriber);
            subscriber.emitter().complete();
        }
    }

    record StreamEvent(
            UUID publicId,
            String type,
            String status,
            int level,
            String module,
            Instant occurredAt) {
        static StreamEvent from(VehicleAlarmOutboxEvent event, VehicleAlarm alarm) {
            return new StreamEvent(alarm.getPublicId(), event.getEventType(), alarm.getProcessingStatus().name(),
                    alarm.getAlarmLevel(), alarm.getModule(), alarm.getOccurredAt());
        }
    }

    private record Subscriber(UUID actorId, SseEmitter emitter) { }

    private record Cursor(Instant createdAt, UUID id) {
        static Cursor parse(String value) {
            if (value == null || value.isBlank()) return null;
            int separator = value.indexOf(':');
            if (separator <= 0 || separator != value.lastIndexOf(':')) {
                throw new IllegalArgumentException("invalid Last-Event-ID");
            }
            try {
                long micros = Long.parseLong(value.substring(0, separator));
                UUID id = UUID.fromString(value.substring(separator + 1));
                return new Cursor(Instant.ofEpochSecond(
                        Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000L), id);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("invalid Last-Event-ID");
            }
        }

        static String format(Instant createdAt, UUID id) {
            long micros = Math.addExact(Math.multiplyExact(createdAt.getEpochSecond(), 1_000_000L),
                    createdAt.getNano() / 1_000L);
            return micros + ":" + id;
        }
    }
}
