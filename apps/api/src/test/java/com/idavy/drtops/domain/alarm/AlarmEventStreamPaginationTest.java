package com.idavy.drtops.domain.alarm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class AlarmEventStreamPaginationTest {
    @Test
    void replaysMoreThanOneRepositoryPageWithoutDroppingOrReorderingEvents() throws Exception {
        Instant firstAt = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        VehicleAlarm alarm = alarm();
        List<VehicleAlarmOutboxEvent> events = new ArrayList<>();
        for (int index = 0; index < 501; index++) {
            VehicleAlarmOutboxEvent event = VehicleAlarmOutboxEvent.pending(alarm, "ALARM_CREATED");
            ReflectionTestUtils.setField(event, "createdAt", firstAt.plus(index, ChronoUnit.MICROS));
            event.markPublished(firstAt.plus(index, ChronoUnit.MICROS));
            events.add(event);
        }
        events.sort(AlarmEventStreamPaginationTest::compare);
        List<String> expectedIds = events.stream()
                .map(event -> cursor(event.getCreatedAt(), event.getId()))
                .toList();
        AtomicInteger queries = new AtomicInteger();
        VehicleAlarmOutboxRepository outbox = pagedRepository(events, queries);
        AlarmEventStreamService stream = new AlarmEventStreamService(
                outbox, permitStreaming(), new ObjectMapper().findAndRegisterModules());

        CapturedSse captured = capture(stream.subscribe(UUID.randomUUID(),
                cursor(firstAt.minus(1, ChronoUnit.MICROS),
                        UUID.fromString("00000000-0000-0000-0000-000000000001"))));

        List<String> receivedIds = captured.eventIds();
        assertThat(receivedIds).hasSize(501).containsExactlyElementsOf(expectedIds);
        expectedIds.forEach(expectedId -> assertThat(receivedIds.stream()
                        .filter(expectedId::equals)
                        .count())
                .as("SSE id %s should be replayed exactly once", expectedId)
                .isEqualTo(1));
        assertThat(queries).hasValue(2);
    }

    private static VehicleAlarmOutboxRepository pagedRepository(
            List<VehicleAlarmOutboxEvent> events, AtomicInteger queries) {
        InvocationHandler invocation = (proxy, method, arguments) -> {
            if (method.getName().equals("findPublishedAfter")) {
                queries.incrementAndGet();
                Instant afterCreatedAt = (Instant) arguments[0];
                UUID afterId = (UUID) arguments[1];
                return events.stream()
                        .filter(event -> compare(event, afterCreatedAt, afterId) > 0)
                        .limit(500)
                        .toList();
            }
            if (method.getName().equals("toString")) return "paged vehicle alarm outbox";
            throw new UnsupportedOperationException(method.getName());
        };
        return (VehicleAlarmOutboxRepository) Proxy.newProxyInstance(
                VehicleAlarmOutboxRepository.class.getClassLoader(),
                new Class<?>[] {VehicleAlarmOutboxRepository.class}, invocation);
    }

    private static int compare(VehicleAlarmOutboxEvent event, Instant createdAt, UUID id) {
        int timestamp = event.getCreatedAt().compareTo(createdAt);
        if (timestamp != 0) return timestamp;
        int mostSignificant = Long.compareUnsigned(
                event.getId().getMostSignificantBits(), id.getMostSignificantBits());
        return mostSignificant != 0
                ? mostSignificant
                : Long.compareUnsigned(event.getId().getLeastSignificantBits(), id.getLeastSignificantBits());
    }

    private static int compare(VehicleAlarmOutboxEvent left, VehicleAlarmOutboxEvent right) {
        return compare(left, right.getCreatedAt(), right.getId());
    }

    private static VehicleAlarmAuthorization permitStreaming() {
        return new VehicleAlarmAuthorization() {
            @Override public boolean mayRead(UUID actorId) { return true; }
            @Override public boolean mayContinueRead(UUID actorId) { return true; }
            @Override public boolean mayContinueReadPersisted(UUID actorId) { return true; }
            @Override public boolean mayHandle(UUID actorId) { return false; }
            @Override public boolean mayReopen(UUID actorId) { return false; }
        };
    }

    private static VehicleAlarm alarm() {
        VehicleAlarmIngressService.AlarmFact fact = new VehicleAlarmIngressService.AlarmFact(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "T/JSATL12-2017", "ADAS", 1, "FORWARD_COLLISION",
                4097L, "START", 1, "ALARM-1", Instant.parse("2026-08-14T10:00:00Z"),
                Instant.parse("2026-08-14T10:00:01Z"), new BigDecimal("118.0000000"),
                new BigDecimal("32.0000000"), new BigDecimal("60.00"), UUID.randomUUID(), "UNASSESSED",
                "a".repeat(64));
        return VehicleAlarm.start(fact, UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID().toString().replace("-", ""),
                new AlarmStore.LocationReference(
                        UUID.randomUUID(), fact.onboardSystemId(), fact.occurredAt(), "GOOD", "[]"));
    }

    private static String cursor(Instant at, UUID id) {
        return (at.getEpochSecond() * 1_000_000L + at.getNano() / 1_000L) + ":" + id;
    }

    private static CapturedSse capture(SseEmitter emitter) throws Exception {
        Class<?> handlerType = Class.forName(
                "org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter$Handler");
        CapturedSse captured = new CapturedSse();
        InvocationHandler invocation = (proxy, method, arguments) -> {
            if (method.getName().equals("send")) captured.add(arguments[0]);
            return null;
        };
        Object handler = Proxy.newProxyInstance(handlerType.getClassLoader(), new Class<?>[] {handlerType}, invocation);
        Method initialize = ResponseBodyEmitter.class.getDeclaredMethod("initialize", handlerType);
        initialize.setAccessible(true);
        initialize.invoke(emitter, handler);
        return captured;
    }

    private static final class CapturedSse {
        private static final Pattern CURSOR_ID = Pattern.compile(
                "(?<![0-9])(-?[0-9]+:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                        + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(?![0-9a-fA-F-])");
        private final List<Object> sends = new ArrayList<>();

        void add(Object sent) {
            if (sent instanceof Set<?> set) {
                for (Object item : set) {
                    if (item instanceof ResponseBodyEmitter.DataWithMediaType data) sends.add(data.getData());
                }
            } else {
                sends.add(sent);
            }
        }

        List<String> eventIds() {
            List<String> ids = new ArrayList<>();
            for (Object sent : sends) {
                if (!(sent instanceof CharSequence text)) continue;
                Matcher matcher = CURSOR_ID.matcher(text);
                while (matcher.find()) ids.add(matcher.group(1));
            }
            return List.copyOf(ids);
        }
    }
}
