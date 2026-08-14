package com.idavy.drtops.domain.alarm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.Duration;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.idavy.drtops.auth.JwtTokenService;
import com.idavy.drtops.auth.RoleCode;
import com.idavy.drtops.auth.UserAccount;
import com.idavy.drtops.auth.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.BeforeEach;
import jakarta.persistence.EntityManager;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:vehicle_alarm_event_stream;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "drt.auth.jwt-secret=alarm-event-stream-test-secret-123456789"
})
@AutoConfigureMockMvc
class AlarmEventStreamIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AlarmOutboxPublisher publisher;

    @Autowired
    AlarmEventStreamService stream;

    @Autowired
    VehicleAlarmRepository alarms;

    @Autowired
    VehicleAlarmOutboxRepository outbox;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManager entityManager;

    @Autowired
    UserAccountRepository users;

    @Autowired
    JwtTokenService jwt;

    @BeforeEach
    void resetOutbox() {
        outbox.deleteAll();
        alarms.deleteAll();
        users.deleteAll();
        SecurityContextHolder.clearContext();
    }

    @Test
    void opensAnAsyncSseStreamForABearerAuthenticatedAlarmReader() throws Exception {
        UserAccount operator = UserAccount.create("alarm-reader", "Alarm reader", "hash");
        operator.assignRoles(Set.of(RoleCode.OPERATOR));
        String token = jwt.issue(users.saveAndFlush(operator)).value();

        mockMvc.perform(get("/api/vehicle-alarms/events")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
    }

    @Test
    void rejectsASubscriberWithoutVehicleAlarmReadPermission() throws Exception {
        mockMvc.perform(get("/api/vehicle-alarms/events")
                        .with(user(UUID.fromString("22222222-2222-2222-2222-222222222222").toString())
                                .authorities(new SimpleGrantedAuthority("ORDER_READ"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void emitsFifteenSecondHeartbeatAndDropsAStreamWhoseAuthorizationIsRevoked() throws Exception {
        UUID actorId = authenticateReader();
        SseEmitter emitter = stream.subscribe(actorId, null);
        CapturedSse captured = capture(emitter);

        stream.emitHeartbeat();

        assertThat(captured.rendered()).contains("event:heartbeat");
        assertThat(AlarmEventStreamService.class.getDeclaredMethod("emitHeartbeat")
                .getAnnotation(org.springframework.scheduling.annotation.Scheduled.class).fixedRate())
                .isEqualTo(15_000L);

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                actorId.toString(), actorId, List.of(new SimpleGrantedAuthority("ORDER_READ"))));
        stream.emitHeartbeat();

        assertThat(captured.error()).isInstanceOf(VehicleAlarmAuthorizationException.class);
    }

    @Test
    void keepsAnEnabledPersistedReaderSubscribedAfterTheOriginalRequestContextIsCleared() {
        UserAccount operator = UserAccount.create("stream-reader", "Stream reader", "hash");
        operator.assignRoles(Set.of(RoleCode.OPERATOR));
        UUID actorId = users.saveAndFlush(operator).getId();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                actorId.toString(), actorId, List.of(new SimpleGrantedAuthority("VEHICLE_ALARM_READ"))));
        CapturedSse captured = capture(stream.subscribe(actorId, null));

        SecurityContextHolder.clearContext();
        stream.emitHeartbeat();

        assertThat(captured.rendered()).contains("event:heartbeat");
        assertThat(captured.error()).isNull();
    }

    @Test
    void replaysPublishedEventsAfterLastEventIdInStableCursorOrderAndRedactsInternalFields() throws Exception {
        UUID actorId = authenticateReader();
        VehicleAlarm alarm = alarms.saveAndFlush(alarm());
        Instant firstAt = Instant.parse("2026-08-14T10:00:00.000001Z");
        VehicleAlarmOutboxEvent first = published(alarm, "ALARM_CREATED", firstAt);
        VehicleAlarmOutboxEvent second = published(alarm, "ALARM_STATUS_CHANGED", firstAt.plusMillis(1));
        String cursor = cursor(firstAt, first.getId());

        CapturedSse captured = capture(stream.subscribe(actorId, cursor));

        String rendered = captured.rendered();
        assertThat(rendered).contains("event:vehicle-alarm", second.getId().toString(), alarm.getPublicId().toString())
                .doesNotContain(first.getId().toString(), alarm.getId().toString(), "terminalId", "payloadDigest", "deduplicationKey");
    }

    @Test
    void asksClientToResyncWhenLastEventIdIsOutsideSevenDayWindow() {
        UUID actorId = authenticateReader();

        CapturedSse captured = capture(stream.subscribe(actorId,
                cursor(Instant.now().minusSeconds(8 * 24 * 60 * 60), UUID.randomUUID())));

        assertThat(captured.rendered()).contains("event:resync-required", "seven day replay window");
    }

    @Test
    void deliversPersistedOutboxOnceToLiveSseSubscriberAndOnlyThenMarksPublished() {
        UUID actorId = authenticateReader();
        CapturedSse captured = capture(stream.subscribe(actorId, null));
        VehicleAlarm alarm = alarms.saveAndFlush(alarm());
        VehicleAlarmOutboxEvent event = outbox.saveAndFlush(
                VehicleAlarmOutboxEvent.pending(alarm.getId(), "ALARM_CREATED"));

        assertThat(publisher.publishPending()).isEqualTo(1);
        assertThat(outbox.findById(event.getId()).orElseThrow().getStatus()).isEqualTo("PUBLISHED");
        assertThat(publisher.publishPending()).isZero();

        assertThat(occurrences(captured.rendered(), "event:vehicle-alarm")).isEqualTo(1);
        assertThat(captured.rendered()).contains(alarm.getPublicId().toString())
                .doesNotContain(alarm.getId().toString());
    }

    @Test
    void recoversPendingOutboxRowsAfterPublisherRestartAndKeepsTheP95DispatchBelowTwoSeconds() {
        UUID actorId = authenticateReader();
        CapturedSse captured = capture(stream.subscribe(actorId, null));
        VehicleAlarm alarm = alarms.saveAndFlush(alarm());
        List<Long> dispatchNanos = new ArrayList<>();

        for (int index = 0; index < 20; index++) {
            VehicleAlarmOutboxEvent event = outbox.saveAndFlush(
                    VehicleAlarmOutboxEvent.pending(alarm.getId(), "ALARM_STATUS_CHANGED"));
            entityManager.clear(); // Simulates a process restart: the next publisher observes only persisted state.
            long started = System.nanoTime();
            assertThat(publisher.publishPending()).isEqualTo(1);
            dispatchNanos.add(System.nanoTime() - started);
            assertThat(outbox.findById(event.getId()).orElseThrow().getStatus()).isEqualTo("PUBLISHED");
        }

        Collections.sort(dispatchNanos);
        long p95Nanos = dispatchNanos.get((int) Math.ceil(dispatchNanos.size() * 0.95) - 1);
        assertThat(Duration.ofNanos(p95Nanos)).isLessThan(Duration.ofSeconds(2));
        assertThat(occurrences(captured.rendered(), "event:vehicle-alarm")).isEqualTo(20);
    }

    @Test
    void claimsAnEmptyOutboxWithoutADataSourceSyntaxFailure() {
        assertThatCode(publisher::publishPending).doesNotThrowAnyException();
    }

    @Test
    void publishesEachPersistedOutboxRecordOnlyOnceAndMarksItPublishedAfterDispatch() {
        VehicleAlarm alarm = alarms.saveAndFlush(alarm());
        VehicleAlarmOutboxEvent event = outbox.saveAndFlush(
                VehicleAlarmOutboxEvent.pending(alarm.getId(), "ALARM_CREATED"));

        assertThat(publisher.publishPending()).isEqualTo(1);
        assertThat(outbox.findById(event.getId()).orElseThrow().getStatus()).isEqualTo("PUBLISHED");
        assertThat(publisher.publishPending()).isZero();
    }

    @Test
    void cleansOnlyPublishedOutboxRowsOlderThanSevenDays() {
        VehicleAlarm alarm = alarms.saveAndFlush(alarm());
        VehicleAlarmOutboxEvent published = outbox.saveAndFlush(
                VehicleAlarmOutboxEvent.pending(alarm.getId(), "ALARM_CREATED"));
        published.markPublished(Instant.parse("2026-08-01T00:00:00Z"));
        outbox.saveAndFlush(published);
        jdbc.update("update vehicle_alarm_outbox set created_at = ? where id = ?",
                Instant.parse("2026-08-01T00:00:00Z"), published.getId());
        VehicleAlarmOutboxEvent pending = outbox.saveAndFlush(
                VehicleAlarmOutboxEvent.pending(alarm.getId(), "ALARM_STATUS_CHANGED"));
        jdbc.update("update vehicle_alarm_outbox set created_at = ? where id = ?",
                Instant.parse("2026-08-01T00:00:00Z"), pending.getId());

        assertThat(publisher.cleanupPublishedBefore(Instant.parse("2026-08-08T00:00:00Z"))).isEqualTo(1);
        assertThat(outbox.findById(published.getId())).isEmpty();
        assertThat(outbox.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo("PENDING");
    }

    private static VehicleAlarm alarm() {
        VehicleAlarmIngressService.AlarmFact fact = new VehicleAlarmIngressService.AlarmFact(
                UUID.randomUUID(), UUID.randomUUID(), "T/JSATL12-2017", "ADAS", 1, "FORWARD_COLLISION",
                4097L, "START", 1, "ALARM-1", Instant.parse("2026-08-14T10:00:00Z"),
                Instant.parse("2026-08-14T10:00:01Z"), new BigDecimal("118.0000000"),
                new BigDecimal("32.0000000"), new BigDecimal("60.00"), UUID.randomUUID(), "UNASSESSED",
                "a".repeat(64));
        return VehicleAlarm.start(fact, UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID().toString().replace("-", ""),
                new AlarmStore.LocationReference(UUID.randomUUID(), "GOOD", "[]"));
    }

    private UUID authenticateReader() {
        UUID actorId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                actorId.toString(), actorId, List.of(new SimpleGrantedAuthority("VEHICLE_ALARM_READ"))));
        return actorId;
    }

    private VehicleAlarmOutboxEvent published(VehicleAlarm alarm, String type, Instant createdAt) {
        VehicleAlarmOutboxEvent event = VehicleAlarmOutboxEvent.pending(alarm.getId(), type);
        event.markPublished(createdAt);
        outbox.saveAndFlush(event);
        jdbc.update("update vehicle_alarm_outbox set created_at = ? where id = ?", createdAt, event.getId());
        entityManager.clear();
        return event;
    }

    private static String cursor(Instant at, UUID id) {
        return (at.getEpochSecond() * 1_000_000L + at.getNano() / 1_000L) + ":" + id;
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        for (int offset = value.indexOf(needle); offset >= 0; offset = value.indexOf(needle, offset + needle.length())) {
            count++;
        }
        return count;
    }

    private static CapturedSse capture(SseEmitter emitter) {
        try {
            Class<?> handlerType = Class.forName(
                    "org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter$Handler");
            CapturedSse captured = new CapturedSse();
            InvocationHandler invocation = (proxy, method, arguments) -> {
                switch (method.getName()) {
                    case "send" -> captured.add(arguments[0]);
                    case "completeWithError" -> captured.error = (Throwable) arguments[0];
                    default -> { }
                }
                return null;
            };
            Object handler = Proxy.newProxyInstance(handlerType.getClassLoader(), new Class<?>[] {handlerType}, invocation);
            Method initialize = ResponseBodyEmitter.class.getDeclaredMethod("initialize", handlerType);
            initialize.setAccessible(true);
            initialize.invoke(emitter, handler);
            return captured;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("cannot observe SSE emitter", exception);
        }
    }

    private static final class CapturedSse {
        private final List<Object> sends = new ArrayList<>();
        private Throwable error;

        void add(Object sent) {
            if (sent instanceof Set<?> set) {
                for (Object item : set) {
                    if (item instanceof ResponseBodyEmitter.DataWithMediaType data) sends.add(data.getData());
                }
            } else {
                sends.add(sent);
            }
        }

        String rendered() {
            return sends.toString();
        }

        Throwable error() {
            return error;
        }
    }
}
