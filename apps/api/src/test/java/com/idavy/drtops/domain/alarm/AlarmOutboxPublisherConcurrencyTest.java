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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:alarm_outbox_publisher_concurrency;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AlarmOutboxPublisherConcurrencyTest.Configuration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AlarmOutboxPublisherConcurrencyTest {
    @Autowired AlarmOutboxPublisher publisher;
    @Autowired AlarmEventStreamService stream;
    @Autowired VehicleAlarmRepository alarms;
    @Autowired VehicleAlarmOutboxRepository outbox;

    @Test
    void serializesConcurrentPublisherCallsInDatabaseCursorOrderWithoutDuplicateDelivery() throws Exception {
        outbox.deleteAll();
        alarms.deleteAll();
        VehicleAlarm alarm = alarms.saveAndFlush(alarm());
        Instant firstAt = Instant.parse("2026-08-14T10:00:00.000001Z");
        List<VehicleAlarmOutboxEvent> events = new ArrayList<>();
        for (int index = 0; index < 51; index++) {
            VehicleAlarmOutboxEvent event = VehicleAlarmOutboxEvent.pending(alarm, "ALARM_CREATED");
            ReflectionTestUtils.setField(event, "createdAt", firstAt.plus(index, ChronoUnit.MICROS));
            events.add(event);
        }
        outbox.saveAllAndFlush(events);
        List<String> expectedIds = events.stream()
                .sorted(AlarmOutboxPublisherConcurrencyTest::compareByPostgresCursor)
                .map(AlarmOutboxPublisherConcurrencyTest::sseId)
                .toList();

        CountDownLatch firstSendEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstSend = new CountDownLatch(1);
        CapturedSse captured = captureBlockingFirstSend(
                stream.subscribe(UUID.randomUUID(), null), firstSendEntered, releaseFirstSend);
        ExecutorService publishers = Executors.newFixedThreadPool(2);
        ExecutorService releaseTimer = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> firstCall = publishers.submit(publisher::publishPending);
            assertThat(firstSendEntered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Integer> secondCall = publishers.submit(publisher::publishPending);
            releaseTimer.submit(() -> {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    releaseFirstSend.countDown();
                }
            });

            assertThat(firstCall.get(10, TimeUnit.SECONDS) + secondCall.get(10, TimeUnit.SECONDS)).isEqualTo(51);
        } finally {
            releaseFirstSend.countDown();
            publishers.shutdownNow();
            releaseTimer.shutdownNow();
        }

        List<String> receivedIds = captured.eventIds();
        assertThat(receivedIds).hasSize(51).containsExactlyElementsOf(expectedIds);
        expectedIds.forEach(expectedId -> assertThat(receivedIds.stream()
                        .filter(expectedId::equals)
                        .count())
                .as("SSE id %s should be delivered exactly once", expectedId)
                .isEqualTo(1));
        assertThat(outbox.findAll()).hasSize(51)
                .allSatisfy(event -> assertThat(event.getStatus()).isEqualTo("PUBLISHED"));
    }

    private static CapturedSse captureBlockingFirstSend(
            SseEmitter emitter, CountDownLatch firstSendEntered, CountDownLatch releaseFirstSend) throws Exception {
        Class<?> handlerType = Class.forName(
                "org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter$Handler");
        CapturedSse captured = new CapturedSse();
        AtomicBoolean blockFirst = new AtomicBoolean(true);
        InvocationHandler invocation = (proxy, method, arguments) -> {
            if (method.getName().equals("send")) {
                if (blockFirst.compareAndSet(true, false)) {
                    firstSendEntered.countDown();
                    if (!releaseFirstSend.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("the first SSE send was not released");
                    }
                }
                captured.add(arguments[0]);
            }
            return null;
        };
        Object handler = Proxy.newProxyInstance(handlerType.getClassLoader(), new Class<?>[] {handlerType}, invocation);
        Method initialize = ResponseBodyEmitter.class.getDeclaredMethod("initialize", handlerType);
        initialize.setAccessible(true);
        initialize.invoke(emitter, handler);
        return captured;
    }

    private static int compareByPostgresCursor(
            VehicleAlarmOutboxEvent left, VehicleAlarmOutboxEvent right) {
        int timestamp = left.getCreatedAt().compareTo(right.getCreatedAt());
        if (timestamp != 0) return timestamp;
        int mostSignificant = Long.compareUnsigned(
                left.getId().getMostSignificantBits(), right.getId().getMostSignificantBits());
        return mostSignificant != 0 ? mostSignificant : Long.compareUnsigned(
                left.getId().getLeastSignificantBits(), right.getId().getLeastSignificantBits());
    }

    private static String sseId(VehicleAlarmOutboxEvent event) {
        Instant createdAt = event.getCreatedAt();
        long micros = Math.addExact(
                Math.multiplyExact(createdAt.getEpochSecond(), 1_000_000L), createdAt.getNano() / 1_000L);
        return micros + ":" + event.getId();
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

    private static final class CapturedSse {
        private static final Pattern CURSOR_ID = Pattern.compile(
                "(?<![0-9])(-?[0-9]+:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                        + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(?![0-9a-fA-F-])");
        private final List<Object> sends = new CopyOnWriteArrayList<>();

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

    @TestConfiguration
    static class Configuration {
        @Bean
        ObjectMapper alarmObjectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        VehicleAlarmAuthorization permitAlarmStreaming() {
            return new VehicleAlarmAuthorization() {
                @Override public boolean mayRead(UUID actorId) { return true; }
                @Override public boolean mayContinueRead(UUID actorId) { return true; }
                @Override public boolean mayHandle(UUID actorId) { return false; }
                @Override public boolean mayReopen(UUID actorId) { return false; }
            };
        }

        @Bean
        AlarmEventStreamService alarmEventStreamService(
                VehicleAlarmOutboxRepository outbox, VehicleAlarmAuthorization authorization, ObjectMapper objectMapper) {
            return new AlarmEventStreamService(outbox, authorization, objectMapper);
        }

        @Bean
        AlarmOutboxPublisher alarmOutboxPublisher(
                VehicleAlarmOutboxRepository outbox, AlarmEventStreamService stream) {
            return new AlarmOutboxPublisher(outbox, stream);
        }

        @Bean
        TaskScheduler taskScheduler() {
            return new TaskScheduler() {
                @Override public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) { return null; }
                @Override public ScheduledFuture<?> schedule(Runnable task, Instant startTime) { return null; }
                @Override public ScheduledFuture<?> scheduleAtFixedRate(
                        Runnable task, Instant startTime, java.time.Duration period) { return null; }
                @Override public ScheduledFuture<?> scheduleAtFixedRate(
                        Runnable task, java.time.Duration period) { return null; }
                @Override public ScheduledFuture<?> scheduleWithFixedDelay(
                        Runnable task, Instant startTime, java.time.Duration delay) { return null; }
                @Override public ScheduledFuture<?> scheduleWithFixedDelay(
                        Runnable task, java.time.Duration delay) { return null; }
            };
        }
    }
}
