package com.idavy.drtops.domain.alarm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import com.idavy.drtops.auth.JwtTokenService;
import com.idavy.drtops.auth.RoleCode;
import com.idavy.drtops.auth.UserAccount;
import com.idavy.drtops.auth.UserAccountRepository;
import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import com.idavy.drtops.domain.location.CanonicalPositionIngress;
import com.idavy.drtops.domain.location.GatewayIngressEnvelope;
import com.idavy.drtops.domain.location.ServiceAreaLocationChecker;
import com.idavy.drtops.domain.onboard.OnboardDeviceMembership;
import com.idavy.drtops.domain.onboard.OnboardDeviceMembershipRepository;
import com.idavy.drtops.domain.onboard.OnboardDeviceRoleAssignment;
import com.idavy.drtops.domain.onboard.OnboardDeviceRoleAssignmentRepository;
import com.idavy.drtops.domain.onboard.OnboardSystem;
import com.idavy.drtops.domain.onboard.OnboardSystemRepository;
import com.idavy.drtops.domain.terminal.JtTerminal;
import com.idavy.drtops.domain.terminal.JtTerminalRepository;
import com.idavy.drtops.domain.terminal.JtTerminalVehicleBinding;
import com.idavy.drtops.domain.terminal.JtTerminalVehicleBindingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.BeforeEach;
import jakarta.persistence.EntityManager;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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
@Import(AlarmEventStreamIntegrationTest.LocationCheckerConfiguration.class)
class AlarmEventStreamIntegrationTest {
    private static final String GATEWAY_CREDENTIAL = "alarm-event-stream-gateway-credential";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AlarmOutboxPublisher publisher;

    @Autowired
    VehicleAlarmActionService actionService;

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

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    VehicleRepository vehicles;

    @Autowired
    JtTerminalRepository terminals;

    @Autowired
    JtTerminalVehicleBindingRepository bindings;

    @Autowired
    OnboardSystemRepository onboardSystems;

    @Autowired
    OnboardDeviceMembershipRepository memberships;

    @Autowired
    OnboardDeviceRoleAssignmentRepository roles;

    @Autowired
    PlatformTransactionManager transactions;

    @DynamicPropertySource
    static void gatewayCredential(DynamicPropertyRegistry registry) {
        registry.add("jt.gateway.service-credentials.current.version", () -> "1");
        registry.add("jt.gateway.service-credentials.current.hash", () -> sha256(GATEWAY_CREDENTIAL));
    }

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
        UUID actorId = authenticatePersistedReader();
        VehicleAlarm alarm = alarms.saveAndFlush(alarm());
        Instant firstAt = withinReplayWindow();
        VehicleAlarmOutboxEvent first = published(alarm, "ALARM_CREATED", firstAt);
        VehicleAlarmOutboxEvent second = published(alarm, "ALARM_STATUS_CHANGED", firstAt.plusMillis(1));
        String cursor = cursor(firstAt, first.getId());

        CapturedSse captured = capture(stream.subscribe(actorId, cursor));

        String rendered = captured.rendered();
        assertThat(rendered).contains("event:vehicle-alarm", second.getId().toString(), alarm.getPublicId().toString())
                .doesNotContain(first.getId().toString(), alarm.getId().toString(), "terminalId", "payloadDigest", "deduplicationKey");
    }

    @Test
    void buffersANewerLiveEventUntilReplayDeliversTheEarlierDatabaseEvent() throws Exception {
        UUID actorId = authenticatePersistedReader();
        VehicleAlarm alarm = alarms.saveAndFlush(alarm());
        Instant firstAt = withinReplayWindow();
        VehicleAlarmOutboxEvent replayFirst = published(alarm, "ALARM_CREATED", firstAt);
        VehicleAlarmOutboxEvent replaySecond = published(alarm, "ALARM_STATUS_CHANGED", firstAt.plusMillis(1));
        VehicleAlarmOutboxEvent liveThird = VehicleAlarmOutboxEvent.pending(alarm, "ALARM_STATUS_CHANGED");
        CountDownLatch firstReplaySent = new CountDownLatch(1);
        CountDownLatch liveThirdSent = new CountDownLatch(1);
        ExecutorService publisherThread = Executors.newSingleThreadExecutor();
        Future<?> concurrentPublisher = publisherThread.submit(() -> {
            if (!firstReplaySent.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("the first replay event was not sent");
            }
            stream.publish(liveThird);
            liveThirdSent.countDown();
            return null;
        });
        SseEmitter emitter = new SseEmitter(0L);
        CapturedSse captured = capture(emitter, () -> {
            firstReplaySent.countDown();
            awaitLatch(liveThirdSent, "the concurrent live event was not published");
        });
        Object cursor = newCursor(firstAt.minusMillis(1), UUID.fromString("00000000-0000-0000-0000-000000000001"));
        Object subscriber = newSubscriber(actorId, emitter, cursor);
        CopyOnWriteArrayList<Object> subscribers = subscribers();
        subscribers.add(subscriber);
        try {
            replay(subscriber, cursor);
            concurrentPublisher.get(5, TimeUnit.SECONDS);
        } finally {
            subscribers.remove(subscriber);
            publisherThread.shutdownNow();
        }

        String rendered = captured.rendered();
        assertThat(rendered).contains(replayFirst.getId().toString(), replaySecond.getId().toString(), liveThird.getId().toString());
        assertThat(rendered.indexOf(replayFirst.getId().toString()))
                .isLessThan(rendered.indexOf(replaySecond.getId().toString()))
                .isLessThan(rendered.indexOf(liveThird.getId().toString()));
    }

    @Test
    void ordersSameMicrosecondUuidCursorsLikePostgresUnsignedBytes() throws Exception {
        Instant sameMicrosecond = Instant.parse("2026-08-14T10:00:00.000001Z");
        Object lower = newCursor(sameMicrosecond,
                UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff"));
        Object higher = newCursor(sameMicrosecond,
                UUID.fromString("80000000-0000-0000-0000-000000000000"));
        Method compareTo = lower.getClass().getDeclaredMethod("compareTo", lower.getClass());
        compareTo.setAccessible(true);

        assertThat((int) compareTo.invoke(lower, higher)).isNegative();
        assertThat((int) compareTo.invoke(higher, lower)).isPositive();
    }

    @Test
    void stopsCatchUpReplayWhenThePersistedReaderIsDisabledAfterItsFirstEvent() throws Exception {
        UserAccount operator = UserAccount.create("replay-reader", "Replay reader", "hash");
        operator.assignRoles(Set.of(RoleCode.OPERATOR));
        UUID actorId = users.saveAndFlush(operator).getId();
        VehicleAlarm alarm = alarms.saveAndFlush(alarm());
        Instant firstAt = withinReplayWindow();
        published(alarm, "ALARM_CREATED", firstAt);
        published(alarm, "ALARM_STATUS_CHANGED", firstAt.plusMillis(1));

        SseEmitter replayEmitter = new SseEmitter(0L);
        CapturedSse captured = capture(replayEmitter, () -> {
            UserAccount persisted = users.findById(actorId).orElseThrow();
            persisted.disable();
            users.saveAndFlush(persisted);
        });
        Class<?> cursorType = Class.forName(AlarmEventStreamService.class.getName() + "$Cursor");
        var cursorConstructor = cursorType.getDeclaredConstructor(Instant.class, UUID.class);
        cursorConstructor.setAccessible(true);
        Object cursor = cursorConstructor.newInstance(firstAt.minusMillis(1),
                UUID.fromString("00000000-0000-0000-0000-000000000001"));
        Class<?> subscriberType = Class.forName(AlarmEventStreamService.class.getName() + "$Subscriber");
        var subscriberConstructor = subscriberType.getDeclaredConstructor(UUID.class, SseEmitter.class, cursorType);
        subscriberConstructor.setAccessible(true);
        Object subscriber = subscriberConstructor.newInstance(actorId, replayEmitter, cursor);
        Method replay = AlarmEventStreamService.class.getDeclaredMethod("replay", subscriberType, cursorType);
        replay.setAccessible(true);

        SecurityContextHolder.clearContext();
        replay.invoke(stream, subscriber, cursor);

        assertThat(occurrences(captured.rendered(), "event:vehicle-alarm")).isEqualTo(1);
        assertThat(captured.error()).isInstanceOf(VehicleAlarmAuthorizationException.class);
    }

    @Test
    @Transactional
    void replaysImmutableSnapshotsAfterConsecutiveStateChangesAndAStreamRestart() {
        UUID actorId = authenticatePersistedReaderAndHandler();
        VehicleAlarm alarm = alarms.saveAndFlush(alarm());
        Instant firstAt = withinReplayWindow();
        published(alarm, "ALARM_CREATED", firstAt);

        VehicleAlarm acknowledged = actionService.transition(alarm.getId(), alarm.getVersion(), actorId,
                VehicleAlarm.ProcessingStatus.ACKNOWLEDGED, "acknowledged");
        publishLatestPending(firstAt.plusMillis(1));
        VehicleAlarm processing = actionService.transition(alarm.getId(), acknowledged.getVersion(), actorId,
                VehicleAlarm.ProcessingStatus.PROCESSING, "taken over");
        publishLatestPending(firstAt.plusMillis(2));
        actionService.transition(alarm.getId(), processing.getVersion(), actorId,
                VehicleAlarm.ProcessingStatus.RESOLVED, "resolved");
        assertThat(outbox.findAll()).extracting(VehicleAlarmOutboxEvent::getPayload)
                .anySatisfy(payload -> assertThat(payload).contains("\"status\":\"NEW\""))
                .anySatisfy(payload -> assertThat(payload).contains("\"status\":\"ACKNOWLEDGED\""))
                .anySatisfy(payload -> assertThat(payload).contains("\"status\":\"PROCESSING\""));
        entityManager.clear(); // The replay must use persisted event snapshots, not this mutable alarm row.

        CapturedSse captured = capture(stream.subscribe(actorId,
                cursor(firstAt.minusMillis(1), UUID.fromString("00000000-0000-0000-0000-000000000001"))));

        assertThat(captured.rendered()).contains("status=NEW", "status=ACKNOWLEDGED", "status=PROCESSING")
                .doesNotContain("status=RESOLVED");
    }

    @Test
    void asksClientToResyncWhenLastEventIdIsOutsideSevenDayWindow() {
        UUID actorId = authenticateReader();

        CapturedSse captured = capture(stream.subscribe(actorId,
                cursor(Instant.now().minusSeconds(8 * 24 * 60 * 60), UUID.randomUUID())));
        stream.publish(VehicleAlarmOutboxEvent.pending(alarm(), "ALARM_CREATED"));

        assertThat(captured.rendered())
                .contains("event:resync-required", "seven day replay window")
                .doesNotContain("event:vehicle-alarm");
    }

    @Test
    void deliversPersistedOutboxOnceToLiveSseSubscriberAndOnlyThenMarksPublished() {
        UUID actorId = authenticateReader();
        CapturedSse captured = capture(stream.subscribe(actorId, null));
        VehicleAlarm alarm = alarms.saveAndFlush(alarm());
        VehicleAlarmOutboxEvent event = outbox.saveAndFlush(
                VehicleAlarmOutboxEvent.pending(alarm, "ALARM_CREATED"));

        assertThat(publisher.publishPending()).isEqualTo(1);
        assertThat(outbox.findById(event.getId()).orElseThrow().getStatus()).isEqualTo("PUBLISHED");
        assertThat(publisher.publishPending()).isZero();

        assertThat(occurrences(captured.rendered(), "event:vehicle-alarm")).isEqualTo(1);
        assertThat(captured.rendered()).contains(alarm.getPublicId().toString())
                .doesNotContain(alarm.getId().toString());
    }

    @Test
    void doesNotRedisplayAnEventAfterItsFirstSendRollsBackAndThePublisherRetries() {
        UUID actorId = authenticateReader();
        CapturedSse live = capture(stream.subscribe(actorId, null));
        VehicleAlarm alarm = alarms.saveAndFlush(alarm());
        VehicleAlarmOutboxEvent event = outbox.saveAndFlush(
                VehicleAlarmOutboxEvent.pending(alarm, "ALARM_CREATED"));

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThat(publisher.publishPending()).isEqualTo(1);
            status.setRollbackOnly();
        });
        entityManager.clear();
        assertThat(outbox.findById(event.getId()).orElseThrow().getStatus()).isEqualTo("PENDING");
        assertThat(occurrences(live.rendered(), "event:vehicle-alarm"))
                .as("the first dispatch must have reached this live connection before the transaction rolled back")
                .isEqualTo(1);

        assertThat(publisher.publishPending()).isEqualTo(1);
        assertThat(occurrences(live.rendered(), "event:vehicle-alarm")).isEqualTo(1);

        CapturedSse reconnected = capture(stream.subscribe(actorId, cursor(event.getCreatedAt(), event.getId())));
        assertThat(reconnected.rendered()).doesNotContain("event:vehicle-alarm");
    }

    @Test
    void streamsTwentyGatewayIngressesToABearerSseSubscriberWithP95BelowTwoSeconds() throws Exception {
        UUID vehicleId = UUID.randomUUID();
        UUID terminalId = UUID.randomUUID();
        vehicles.saveAndFlush(Vehicle.create(vehicleId, "SSE-" + vehicleId.toString().substring(0, 8),
                "Microbus", 8, "IDLE", "POINT(105.2421 35.2103)", "test", true));
        JtTerminal terminal = terminals.saveAndFlush(JtTerminal.preset(terminalId,
                "SSE-" + terminalId.toString().substring(0, 20), "SSE-" + terminalId,
                "MFG", "MODEL", "JT808-2019", "WGS84", UUID.randomUUID()));
        JtTerminalVehicleBinding binding = JtTerminalVehicleBinding.bind(
                terminal, vehicleId, "SSE P95 test", UUID.randomUUID());
        org.springframework.test.util.ReflectionTestUtils.setField(binding, "validFrom", OffsetDateTime.now().minusDays(1));
        bindings.saveAndFlush(binding);
        UUID configurationActor = UUID.randomUUID();
        OffsetDateTime configuredAt = OffsetDateTime.now().minusDays(1);
        OnboardSystem onboardSystem = onboardSystems.saveAndFlush(OnboardSystem.create(
                vehicleId,
                OnboardSystem.OperatingMode.SAFETY_MONITOR_ONLY,
                configurationActor,
                configuredAt));
        memberships.saveAndFlush(OnboardDeviceMembership.join(
                onboardSystem.getId(),
                terminalId,
                OnboardDeviceMembership.NetworkMode.DIRECT_CELLULAR,
                "SSE P95 GPS membership",
                configurationActor,
                configuredAt));
        roles.saveAndFlush(OnboardDeviceRoleAssignment.assign(
                onboardSystem.getId(),
                terminalId,
                OnboardDeviceRoleAssignment.Role.LOCATION_PRIMARY,
                "SSE P95 GPS role",
                configurationActor,
                configuredAt));

        UserAccount operator = UserAccount.create("sse-p95-reader", "SSE P95 reader", "hash");
        operator.assignRoles(Set.of(RoleCode.OPERATOR));
        String token = jwt.issue(users.saveAndFlush(operator)).value();
        MvcResult sse = mockMvc.perform(get("/api/vehicle-alarms/events")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();
        List<Long> dispatchNanos = new ArrayList<>();

        for (int index = 0; index < 20; index++) {
            String identifier = "P95-" + index + "-" + UUID.randomUUID();
            Instant receivedAt = Instant.now();
            GatewayIngressEnvelope position = acceptedPositionEnvelope(
                    terminalId, onboardSystem.getId(), vehicleId, receivedAt);
            GatewayIngressEnvelope alarm = alarmEnvelope(terminalId, vehicleId, position.idempotencyKey(), identifier, index, receivedAt);
            long started = System.nanoTime();
            mockMvc.perform(post("/internal/jt-gateway/ingress")
                            .header("Authorization", "Bearer " + GATEWAY_CREDENTIAL)
                            .header("X-Service-Credential-Version", "1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(List.of(position, alarm))))
                    .andExpect(status().isOk());
            UUID publicId = jdbc.queryForObject(
                    "select public_id from vehicle_alarms where terminal_alarm_identifier = ?", UUID.class, identifier);
            awaitAtMostTwoSeconds(() -> sseContent(sse).contains(publicId.toString()));
            awaitAtMostTwoSeconds(() -> outboxStatus(publicId).equals("PUBLISHED"));
            dispatchNanos.add(System.nanoTime() - started);
        }

        Collections.sort(dispatchNanos);
        long p95Nanos = dispatchNanos.get((int) Math.ceil(dispatchNanos.size() * 0.95) - 1);
        System.out.println("alarm_gateway_sse_p95_millis=" + Duration.ofNanos(p95Nanos).toMillis());
        assertThat(Duration.ofNanos(p95Nanos)).isLessThan(Duration.ofSeconds(2));
        assertThat(sseContent(sse))
                .contains("event:vehicle-alarm", "\"publicId\"")
                .doesNotContain(terminalId.toString(), "payloadDigest", "deduplicationKey");
    }

    @Test
    void claimsAnEmptyOutboxWithoutADataSourceSyntaxFailure() {
        assertThatCode(publisher::publishPending).doesNotThrowAnyException();
    }

    @Test
    void publishesEachPersistedOutboxRecordOnlyOnceAndMarksItPublishedAfterDispatch() {
        VehicleAlarm alarm = alarms.saveAndFlush(alarm());
        VehicleAlarmOutboxEvent event = outbox.saveAndFlush(
                VehicleAlarmOutboxEvent.pending(alarm, "ALARM_CREATED"));

        assertThat(publisher.publishPending()).isEqualTo(1);
        assertThat(outbox.findById(event.getId()).orElseThrow().getStatus()).isEqualTo("PUBLISHED");
        assertThat(publisher.publishPending()).isZero();
    }

    @Test
    void cleansOnlyPublishedOutboxRowsOlderThanSevenDays() {
        VehicleAlarm alarm = alarms.saveAndFlush(alarm());
        VehicleAlarmOutboxEvent published = outbox.saveAndFlush(
                VehicleAlarmOutboxEvent.pending(alarm, "ALARM_CREATED"));
        published.markPublished(Instant.parse("2026-08-01T00:00:00Z"));
        outbox.saveAndFlush(published);
        jdbc.update("update vehicle_alarm_outbox set created_at = ? where id = ?",
                Instant.parse("2026-08-01T00:00:00Z"), published.getId());
        VehicleAlarmOutboxEvent pending = outbox.saveAndFlush(
                VehicleAlarmOutboxEvent.pending(alarm, "ALARM_STATUS_CHANGED"));
        jdbc.update("update vehicle_alarm_outbox set created_at = ? where id = ?",
                Instant.parse("2026-08-01T00:00:00Z"), pending.getId());

        assertThat(publisher.cleanupPublishedBefore(Instant.parse("2026-08-08T00:00:00Z"))).isEqualTo(1);
        assertThat(outbox.findById(published.getId())).isEmpty();
        assertThat(outbox.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo("PENDING");
    }

    @Test
    void schedulesSevenDayCleanupForPublishedOutboxRowsOnly() throws Exception {
        VehicleAlarm alarm = alarms.saveAndFlush(alarm());
        VehicleAlarmOutboxEvent published = outbox.saveAndFlush(
                VehicleAlarmOutboxEvent.pending(alarm, "ALARM_CREATED"));
        published.markPublished(Instant.now().minus(Duration.ofDays(8)));
        outbox.saveAndFlush(published);
        jdbc.update("update vehicle_alarm_outbox set created_at = ? where id = ?",
                Instant.now().minus(Duration.ofDays(8)), published.getId());
        VehicleAlarmOutboxEvent pending = outbox.saveAndFlush(
                VehicleAlarmOutboxEvent.pending(alarm, "ALARM_STATUS_CHANGED"));
        jdbc.update("update vehicle_alarm_outbox set created_at = ? where id = ?",
                Instant.now().minus(Duration.ofDays(8)), pending.getId());

        Method scheduledCleanup = AlarmOutboxPublisher.class.getDeclaredMethod("scheduledCleanup");
        assertThat(scheduledCleanup.getAnnotation(org.springframework.scheduling.annotation.Scheduled.class)).isNotNull();
        scheduledCleanup.invoke(publisher);

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

    private GatewayIngressEnvelope acceptedPositionEnvelope(
            UUID terminalId,
            UUID onboardSystemId,
            UUID vehicleId,
            Instant receivedAt) throws Exception {
        CanonicalPositionIngress position = new CanonicalPositionIngress(
                terminalId, onboardSystemId, vehicleId, "LOCATION_PRIMARY", "JT808-2019", 1,
                new BigDecimal("105.2384988"), new BigDecimal("35.2103000"), "WGS84", receivedAt, receivedAt,
                0L, 0x02L, new BigDecimal("60.00"), 90, 0, 8, "a".repeat(64));
        return new GatewayIngressEnvelope(1, UUID.randomUUID(), "POSITION", receivedAt,
                objectMapper.writeValueAsString(position));
    }

    private GatewayIngressEnvelope alarmEnvelope(
            UUID terminalId, UUID vehicleId, UUID positionKey, String identifier, int index, Instant receivedAt) throws Exception {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("terminalId", terminalId);
        payload.put("vehicleId", vehicleId);
        payload.put("standard", "T/JSATL12-2017");
        payload.put("module", "ADAS");
        payload.put("typeCode", 1);
        payload.put("alarmType", "FORWARD_COLLISION");
        payload.put("terminalAlarmId", 100_000L + index);
        payload.put("state", "START");
        payload.put("level", 1);
        payload.put("terminalAlarmIdentifier", identifier);
        payload.put("occurredAt", receivedAt);
        payload.put("gatewayReceivedAt", receivedAt);
        payload.put("longitude", new BigDecimal("105.2384988"));
        payload.put("latitude", new BigDecimal("35.2109657"));
        payload.put("speedKph", new BigDecimal("60.00"));
        payload.put("positionIdempotencyKey", positionKey);
        payload.put("locationQualityStatus", "UNASSESSED");
        payload.put("extensionPayloadDigest", ("b" + index).repeat(64).substring(0, 64));
        return new GatewayIngressEnvelope(1, UUID.randomUUID(), "ALARM", receivedAt,
                objectMapper.writeValueAsString(payload));
    }

    private static void awaitAtMostTwoSeconds(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean()).as("scheduled outbox delivery within two seconds").isTrue();
    }

    private static String sseContent(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private String outboxStatus(UUID publicId) {
        return jdbc.queryForObject("""
                select outbox.status
                from vehicle_alarm_outbox outbox
                join vehicle_alarms alarm on alarm.id = outbox.vehicle_alarm_id
                where alarm.public_id = ?
                """, String.class, publicId);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private UUID authenticateReader() {
        UUID actorId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                actorId.toString(), actorId, List.of(new SimpleGrantedAuthority("VEHICLE_ALARM_READ"))));
        return actorId;
    }

    private UUID authenticateReaderAndHandler() {
        UUID actorId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                actorId.toString(), actorId, List.of(new SimpleGrantedAuthority("VEHICLE_ALARM_READ"),
                        new SimpleGrantedAuthority("VEHICLE_ALARM_HANDLE"))));
        return actorId;
    }

    private UUID authenticatePersistedReader() {
        return authenticatePersisted("persisted-stream-reader", List.of("VEHICLE_ALARM_READ"));
    }

    private UUID authenticatePersistedReaderAndHandler() {
        return authenticatePersisted("persisted-stream-handler", List.of("VEHICLE_ALARM_READ", "VEHICLE_ALARM_HANDLE"));
    }

    private UUID authenticatePersisted(String usernamePrefix, List<String> permissions) {
        UserAccount operator = UserAccount.create(usernamePrefix + "-" + UUID.randomUUID(), "Persisted stream reader", "hash");
        operator.assignRoles(Set.of(RoleCode.OPERATOR));
        UUID actorId = users.saveAndFlush(operator).getId();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                actorId.toString(), actorId, permissions.stream().map(SimpleGrantedAuthority::new).toList()));
        return actorId;
    }

    private VehicleAlarmOutboxEvent published(VehicleAlarm alarm, String type, Instant createdAt) {
        VehicleAlarmOutboxEvent event = VehicleAlarmOutboxEvent.pending(alarm, type);
        event.markPublished(createdAt);
        outbox.saveAndFlush(event);
        jdbc.update("update vehicle_alarm_outbox set created_at = ? where id = ?", createdAt, event.getId());
        entityManager.clear();
        return event;
    }

    private void publishLatestPending(Instant createdAt) {
        VehicleAlarmOutboxEvent event = outbox.findAll().stream()
                .filter(candidate -> "PENDING".equals(candidate.getStatus()))
                .max(java.util.Comparator.comparing(VehicleAlarmOutboxEvent::getCreatedAt))
                .orElseThrow();
        event.markPublished(createdAt);
        outbox.saveAndFlush(event);
        jdbc.update("update vehicle_alarm_outbox set created_at = ? where id = ?", createdAt, event.getId());
        entityManager.clear();
    }

    private static String cursor(Instant at, UUID id) {
        return (at.getEpochSecond() * 1_000_000L + at.getNano() / 1_000L) + ":" + id;
    }

    private Object newCursor(Instant at, UUID id) throws Exception {
        Class<?> cursorType = Class.forName(AlarmEventStreamService.class.getName() + "$Cursor");
        var constructor = cursorType.getDeclaredConstructor(Instant.class, UUID.class);
        constructor.setAccessible(true);
        return constructor.newInstance(at, id);
    }

    private Object newSubscriber(UUID actorId, SseEmitter emitter, Object cursor) throws Exception {
        Class<?> cursorType = cursor.getClass();
        Class<?> subscriberType = Class.forName(AlarmEventStreamService.class.getName() + "$Subscriber");
        var constructor = subscriberType.getDeclaredConstructor(UUID.class, SseEmitter.class, cursorType);
        constructor.setAccessible(true);
        return constructor.newInstance(actorId, emitter, cursor);
    }

    @SuppressWarnings("unchecked")
    private CopyOnWriteArrayList<Object> subscribers() throws Exception {
        var field = AlarmEventStreamService.class.getDeclaredField("subscribers");
        field.setAccessible(true);
        return (CopyOnWriteArrayList<Object>) field.get(stream);
    }

    private void replay(Object subscriber, Object cursor) throws Exception {
        Method replay = AlarmEventStreamService.class.getDeclaredMethod("replay", subscriber.getClass(), cursor.getClass());
        replay.setAccessible(true);
        replay.invoke(stream, subscriber, cursor);
    }

    private static void awaitLatch(CountDownLatch latch, String failure) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError(failure);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure, exception);
        }
    }

    private static Instant withinReplayWindow() {
        return Instant.now().minus(Duration.ofDays(1)).truncatedTo(ChronoUnit.MICROS);
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        for (int offset = value.indexOf(needle); offset >= 0; offset = value.indexOf(needle, offset + needle.length())) {
            count++;
        }
        return count;
    }

    private static CapturedSse capture(SseEmitter emitter) {
        return capture(emitter, null);
    }

    private static CapturedSse capture(SseEmitter emitter, Runnable afterFirstVehicleAlarm) {
        try {
            Class<?> handlerType = Class.forName(
                    "org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter$Handler");
            CapturedSse captured = new CapturedSse();
            InvocationHandler invocation = (proxy, method, arguments) -> {
                switch (method.getName()) {
                    case "send" -> {
                        int before = occurrences(captured.rendered(), "event:vehicle-alarm");
                        captured.add(arguments[0]);
                        if (afterFirstVehicleAlarm != null && before == 0
                                && occurrences(captured.rendered(), "event:vehicle-alarm") == 1) {
                            afterFirstVehicleAlarm.run();
                        }
                    }
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

    @TestConfiguration
    static class LocationCheckerConfiguration {
        @Bean @Primary
        ServiceAreaLocationChecker alarmIngressAreaChecker() {
            return (longitude, latitude) -> true;
        }
    }
}
