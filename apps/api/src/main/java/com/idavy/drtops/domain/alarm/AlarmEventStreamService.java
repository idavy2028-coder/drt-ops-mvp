package com.idavy.drtops.domain.alarm;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class AlarmEventStreamService {
    private static final Duration RETENTION_WINDOW = Duration.ofDays(7);
    private static final int REPLAY_BATCH_SIZE = 500;
    private final VehicleAlarmOutboxRepository outbox;
    private final VehicleAlarmAuthorization authorization;
    private final ObjectMapper objectMapper;
    private final CopyOnWriteArrayList<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    AlarmEventStreamService(
            VehicleAlarmOutboxRepository outbox,
            VehicleAlarmAuthorization authorization,
            ObjectMapper objectMapper) {
        this.outbox = Objects.requireNonNull(outbox);
        this.authorization = Objects.requireNonNull(authorization);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public SseEmitter subscribe(UUID actorId, String lastEventId) {
        requireRead(actorId);
        SseEmitter emitter = new SseEmitter(0L);
        Subscriber subscriber = null;
        try {
            Cursor cursor = Cursor.parse(lastEventId);
            if (cursor != null && cursor.createdAt().isBefore(Instant.now().minus(RETENTION_WINDOW))) {
                emitter.send(SseEmitter.event().name("resync-required")
                        .data(Map.of("reason", "last event is outside the seven day replay window")));
                emitter.complete();
                return emitter;
            }
            Subscriber created = new Subscriber(actorId, emitter, cursor);
            subscriber = created;
            subscribers.add(created);
            emitter.onCompletion(() -> removeAndClose(created));
            emitter.onTimeout(() -> removeAndClose(created));
            if (cursor != null) replay(created, cursor);
        } catch (Exception exception) {
            if (subscriber != null) removeAndClose(subscriber);
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    void publish(VehicleAlarmOutboxEvent event) {
        StreamEvent payload = StreamEvent.from(event, objectMapper);
        Cursor cursor = new Cursor(event.getCreatedAt(), event.getId());
        for (Subscriber subscriber : subscribers) {
            if (!authorization.mayContinueRead(subscriber.actorId())) {
                forbid(subscriber);
                continue;
            }
            Delivery delivery = subscriber.deliverLive(cursor, payload,
                    SseEmitter.event().id(Cursor.format(cursor.createdAt(), cursor.id()))
                            .name("vehicle-alarm").data(payload));
            if (delivery == Delivery.FAILED) {
                removeAndClose(subscriber);
                subscriber.emitter().complete();
            }
        }
    }

    @Scheduled(fixedRate = 15_000)
    void emitHeartbeat() {
        for (Subscriber subscriber : subscribers) {
            if (!authorization.mayContinueRead(subscriber.actorId())) {
                forbid(subscriber);
                continue;
            }
            send(subscriber, SseEmitter.event().name("heartbeat").data(Map.of("at", Instant.now().toString())));
        }
    }

    int subscriberCount() { return subscribers.size(); }

    private void replay(Subscriber subscriber, Cursor cursor) {
        Cursor pageCursor = cursor;
        while (true) {
            List<VehicleAlarmOutboxEvent> events = outbox.findPublishedAfter(
                    pageCursor.createdAt(), pageCursor.id(), PageRequest.of(0, REPLAY_BATCH_SIZE));
            for (VehicleAlarmOutboxEvent event : events) {
                if (!authorization.mayContinueReadPersisted(subscriber.actorId())) {
                    forbid(subscriber);
                    return;
                }
                Cursor eventCursor = new Cursor(event.getCreatedAt(), event.getId());
                if (!deliverReplay(subscriber, eventCursor, StreamEvent.from(event, objectMapper))) return;
            }
            if (events.size() < REPLAY_BATCH_SIZE) break;
            VehicleAlarmOutboxEvent last = events.getLast();
            pageCursor = new Cursor(last.getCreatedAt(), last.getId());
        }
        while (true) {
            List<BufferedEvent> buffered = subscriber.completeReplayWhenDrained();
            if (buffered == null || buffered.isEmpty()) return;
            for (BufferedEvent event : buffered) {
                if (!authorization.mayContinueReadPersisted(subscriber.actorId())) {
                    forbid(subscriber);
                    return;
                }
                if (!deliverReplay(subscriber, event.cursor(), event.payload())) return;
            }
        }
    }

    private boolean deliverReplay(Subscriber subscriber, Cursor cursor, StreamEvent payload) {
        if (!subscriber.shouldDeliverReplay(cursor)) return true;
        if (!send(subscriber, SseEmitter.event().id(Cursor.format(cursor.createdAt(), cursor.id()))
                .name("vehicle-alarm").data(payload))) return false;
        subscriber.markDelivered(cursor);
        return true;
    }

    private void requireRead(UUID actorId) {
        if (!authorization.mayRead(actorId)) {
            throw new VehicleAlarmAuthorizationException("vehicle alarm stream is forbidden");
        }
    }

    private boolean send(Subscriber subscriber, SseEmitter.SseEventBuilder event) {
        try {
            subscriber.emitter().send(event);
            return true;
        } catch (Exception exception) {
            removeAndClose(subscriber);
            subscriber.emitter().complete();
            return false;
        }
    }

    private void forbid(Subscriber subscriber) {
        removeAndClose(subscriber);
        subscriber.emitter().completeWithError(new VehicleAlarmAuthorizationException("vehicle alarm stream is forbidden"));
    }

    private void removeAndClose(Subscriber subscriber) {
        subscribers.remove(subscriber);
        subscriber.close();
    }

    record StreamEvent(
            UUID publicId,
            String type,
            String status,
            int level,
            String module,
            Instant occurredAt) {
        static StreamEvent from(VehicleAlarmOutboxEvent event, ObjectMapper objectMapper) {
            try {
                VehicleAlarmOutboxEvent.Snapshot snapshot = objectMapper.readValue(
                        event.getPayload(), VehicleAlarmOutboxEvent.Snapshot.class);
                if (!event.getEventType().equals(snapshot.eventType())) {
                    throw new IllegalStateException("outbox event type does not match its snapshot");
                }
                return new StreamEvent(snapshot.publicId(), snapshot.eventType(), snapshot.status(), snapshot.level(),
                        snapshot.module(), snapshot.occurredAt());
            } catch (java.io.IOException exception) {
                throw new IllegalStateException("cannot read vehicle alarm outbox snapshot", exception);
            }
        }
    }

    private static final class Subscriber {
        private final UUID actorId;
        private final SseEmitter emitter;
        private final NavigableMap<Cursor, StreamEvent> buffered = new TreeMap<>();
        private Cursor highWater;
        private Phase phase;

        private Subscriber(UUID actorId, SseEmitter emitter, Cursor highWater) {
            this.actorId = actorId;
            this.emitter = emitter;
            this.highWater = highWater;
            this.phase = highWater == null ? Phase.LIVE : Phase.REPLAYING;
        }

        UUID actorId() { return actorId; }
        SseEmitter emitter() { return emitter; }

        synchronized boolean alreadyDelivered(Cursor cursor) {
            return highWater != null && cursor.compareTo(highWater) <= 0;
        }

        synchronized Delivery deliverLive(
                Cursor cursor, StreamEvent payload, SseEmitter.SseEventBuilder event) {
            if (phase == Phase.CLOSED || alreadyDelivered(cursor)) return Delivery.SKIP;
            if (phase == Phase.REPLAYING) {
                buffered.putIfAbsent(cursor, payload);
                return Delivery.BUFFERED;
            }
            try {
                emitter.send(event);
                markDelivered(cursor);
                return Delivery.SENT;
            } catch (Exception exception) {
                phase = Phase.CLOSED;
                buffered.clear();
                return Delivery.FAILED;
            }
        }

        synchronized boolean shouldDeliverReplay(Cursor cursor) {
            return phase != Phase.CLOSED && !alreadyDelivered(cursor);
        }

        synchronized List<BufferedEvent> completeReplayWhenDrained() {
            if (phase == Phase.CLOSED) return null;
            if (buffered.isEmpty()) {
                phase = Phase.LIVE;
                return List.of();
            }
            List<BufferedEvent> events = new ArrayList<>(buffered.size());
            buffered.forEach((cursor, payload) -> events.add(new BufferedEvent(cursor, payload)));
            buffered.clear();
            return events;
        }

        synchronized void markDelivered(Cursor cursor) {
            if (highWater == null || cursor.compareTo(highWater) > 0) highWater = cursor;
        }

        synchronized void close() {
            phase = Phase.CLOSED;
            buffered.clear();
        }
    }

    private enum Phase { REPLAYING, LIVE, CLOSED }
    private enum Delivery { BUFFERED, SENT, SKIP, FAILED }
    private record BufferedEvent(Cursor cursor, StreamEvent payload) { }

    private record Cursor(Instant createdAt, UUID id) implements Comparable<Cursor> {
        @Override
        public int compareTo(Cursor other) {
            int timestamp = createdAt.compareTo(other.createdAt);
            if (timestamp != 0) return timestamp;
            int mostSignificant = Long.compareUnsigned(
                    id.getMostSignificantBits(), other.id.getMostSignificantBits());
            return mostSignificant != 0 ? mostSignificant : Long.compareUnsigned(
                    id.getLeastSignificantBits(), other.id.getLeastSignificantBits());
        }

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
