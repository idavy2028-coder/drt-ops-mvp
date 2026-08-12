package com.idavy.drtops.jtgateway.ingress;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class GatewayOutboxDispatcherTest {
    private static final Instant NOW = Instant.parse("2026-08-12T05:00:00Z");
    private static final URI OPERATIONS_ENDPOINT =
            URI.create("http://operations.invalid/internal/v1/jt/ingress");
    private static final String TEST_CREDENTIAL = "test-credential-not-a-secret";

    @Test
    void deliversUrgentIngressBeforeAtMostFiftyLocationsPerSecond() {
        var fixture = fixture(8);
        for (int index = 0; index < 60; index++) {
            fixture.buffer.append(envelope(index, IngressKind.LOCATION));
        }
        fixture.buffer.append(envelope(100, IngressKind.ALARM));
        fixture.buffer.append(envelope(101, IngressKind.ATTACHMENT_CONTROL));

        GatewayOutboxDispatcher.DispatchReport first = fixture.dispatcher.dispatchOnce();

        assertEquals(2, fixture.client.batches.size());
        assertEquals(List.of(IngressKind.ALARM, IngressKind.ATTACHMENT_CONTROL),
                fixture.client.batches.get(0).stream().map(GatewayIngressEnvelope::kind).toList());
        assertEquals(50, fixture.client.batches.get(1).size());
        assertTrue(fixture.client.batches.get(1).stream()
                .allMatch(item -> item.kind() == IngressKind.LOCATION));
        assertEquals(52, first.delivered());
        assertEquals(10, fixture.repository.pendingCount());
        assertEquals(52, fixture.repository.deliveredCount());
        assertTrue(fixture.repository.find(envelopeKey(100)).orElseThrow().payloadJson() == null);

        assertEquals(0, fixture.dispatcher.dispatchOnce().attempted());
        fixture.clock.advance(Duration.ofSeconds(1));
        assertEquals(10, fixture.dispatcher.dispatchOnce().delivered());
        assertEquals(0, fixture.repository.pendingCount());
    }

    @Test
    void doesNotStarveAttachmentControlWhileAlarmAndLocationTrafficRemainSaturated() {
        var fixture = fixture(8);
        for (int index = 0; index < 60; index++) {
            fixture.buffer.append(envelope(600 + index, IngressKind.ALARM));
        }
        UUID attachmentKey = envelopeKey(700);
        fixture.buffer.append(envelope(700, IngressKind.ATTACHMENT_CONTROL));
        for (int index = 0; index < 50; index++) {
            fixture.buffer.append(envelope(800 + index, IngressKind.LOCATION));
        }

        fixture.dispatcher.dispatchOnce();
        assertEquals(GatewayOutboxRepository.DeliveryStatus.PENDING,
                fixture.repository.find(attachmentKey).orElseThrow().status());

        for (int index = 0; index < 50; index++) {
            fixture.buffer.append(envelope(900 + index, IngressKind.ALARM));
            fixture.buffer.append(envelope(1_000 + index, IngressKind.LOCATION));
        }
        fixture.clock.advance(Duration.ofSeconds(1));

        fixture.dispatcher.dispatchOnce();

        assertEquals(GatewayOutboxRepository.DeliveryStatus.DELIVERED,
                fixture.repository.find(attachmentKey).orElseThrow().status());
        assertTrue(fixture.client.batches.get(2).stream()
                .anyMatch(item -> item.idempotencyKey().equals(attachmentKey)));
        assertEquals(50, fixture.client.batches.get(3).size());
        assertTrue(fixture.client.batches.get(3).stream()
                .allMatch(item -> item.kind() == IngressKind.LOCATION));
    }

    @Test
    void coordinatesTheLocationBatchWindowAcrossDispatcherInstances() {
        var fixture = fixture(8);
        for (int index = 0; index < 100; index++) {
            fixture.buffer.append(envelope(2_000 + index, IngressKind.LOCATION));
        }
        CapturingDeliveryClient restartedClient = new CapturingDeliveryClient();
        GatewayOutboxDispatcher restartedDispatcher = new GatewayOutboxDispatcher(
                fixture.repository,
                restartedClient,
                fixture.clock,
                8,
                Duration.ofSeconds(1),
                Duration.ofMinutes(5));

        assertEquals(50, fixture.dispatcher.dispatchOnce().delivered());
        assertEquals(0, restartedDispatcher.dispatchOnce().attempted());
        assertTrue(restartedClient.batches.isEmpty());

        fixture.clock.advance(Duration.ofSeconds(1));

        assertEquals(50, restartedDispatcher.dispatchOnce().delivered());
        assertEquals(1, restartedClient.batches.size());
    }

    @Test
    void rollsBackSuccessfulBatchStateAndRecoversTheExpiredClaim() {
        assertBatchStateUpdateIsAtomic(GatewayOutboxDispatcher.DeliveryResult.success());
    }

    @Test
    void rollsBackFailedBatchStateAndRecoversTheExpiredClaim() {
        assertBatchStateUpdateIsAtomic(
                GatewayOutboxDispatcher.DeliveryResult.retryable("HTTP_503"));
    }

    @Test
    void rejectsSuccessfulCompletionFromAnExpiredLeaseAfterReclaim() {
        assertExpiredLeaseIsFenced(true);
    }

    @Test
    void rejectsFailedCompletionFromAnExpiredLeaseAfterReclaim() {
        assertExpiredLeaseIsFenced(false);
    }

    @Test
    void startsBackoffWhenTheFailedResponseReturns() {
        var fixture = fixture(8);
        UUID key = envelopeKey(3_200);
        fixture.buffer.append(envelope(3_200, IngressKind.ALARM));
        GatewayOutboxDispatcher dispatcher = dispatcherWithDelayedResult(
                fixture,
                GatewayOutboxDispatcher.DeliveryResult.retryable("HTTP_503"));

        dispatcher.dispatchOnce();

        GatewayOutboxRepository.OutboxEntry pending =
                fixture.repository.find(key).orElseThrow();
        assertEquals(NOW.plusSeconds(3), pending.nextAttemptAt());
    }

    @Test
    void recordsDeliveryWhenTheSuccessfulResponseReturns() {
        var fixture = fixture(8);
        UUID key = envelopeKey(3_201);
        fixture.buffer.append(envelope(3_201, IngressKind.ALARM));
        GatewayOutboxDispatcher dispatcher = dispatcherWithDelayedResult(
                fixture, GatewayOutboxDispatcher.DeliveryResult.success());

        dispatcher.dispatchOnce();

        GatewayOutboxRepository.OutboxEntry delivered =
                fixture.repository.find(key).orElseThrow();
        assertEquals(NOW.plusSeconds(2), delivered.deliveredAt());
    }

    @Test
    void completesAClaimWhenTheClockHasNanosecondPrecision() {
        var fixture = fixture(8);
        UUID key = envelopeKey(3_202);
        fixture.clock.advance(Duration.ofNanos(123_456_700));
        fixture.buffer.append(envelope(3_202, IngressKind.ALARM));

        assertDoesNotThrow(fixture.dispatcher::dispatchOnce);

        assertEquals(GatewayOutboxRepository.DeliveryStatus.DELIVERED,
                fixture.repository.find(key).orElseThrow().status());
    }

    @Test
    void retriesWithExponentialBackoffThenKeepsDeadLetterForSevenDays() {
        var fixture = fixture(3);
        UUID key = envelopeKey(200);
        fixture.buffer.append(envelope(200, IngressKind.ALARM));
        fixture.client.results.add(GatewayOutboxDispatcher.DeliveryResult.retryable("HTTP_503"));
        fixture.client.results.add(GatewayOutboxDispatcher.DeliveryResult.retryable("HTTP_429"));
        fixture.client.results.add(GatewayOutboxDispatcher.DeliveryResult.retryable("HTTP_503"));

        fixture.dispatcher.dispatchOnce();
        GatewayOutboxRepository.OutboxEntry first = fixture.repository.find(key).orElseThrow();
        assertEquals(GatewayOutboxRepository.DeliveryStatus.PENDING, first.status());
        assertEquals(1, first.attemptCount());
        assertEquals(NOW.plusSeconds(1), first.nextAttemptAt());
        assertEquals("HTTP_503", first.lastErrorCode());
        assertEquals(0, fixture.dispatcher.dispatchOnce().attempted());

        fixture.clock.advance(Duration.ofSeconds(1));
        fixture.dispatcher.dispatchOnce();
        GatewayOutboxRepository.OutboxEntry second = fixture.repository.find(key).orElseThrow();
        assertEquals(2, second.attemptCount());
        assertEquals(NOW.plusSeconds(3), second.nextAttemptAt());

        fixture.clock.advance(Duration.ofSeconds(2));
        fixture.dispatcher.dispatchOnce();
        GatewayOutboxRepository.OutboxEntry dead = fixture.repository.find(key).orElseThrow();
        assertEquals(GatewayOutboxRepository.DeliveryStatus.DEAD_LETTER, dead.status());
        assertEquals(3, dead.attemptCount());
        assertNotNull(dead.payloadJson());

        assertEquals(0, fixture.repository.purgeExpiredDeadLetters(
                fixture.clock.instant().plus(Duration.ofDays(7))));
        assertEquals(1, fixture.repository.purgeExpiredDeadLetters(
                fixture.clock.instant().plus(Duration.ofDays(7)).plusNanos(1)));
        assertTrue(fixture.repository.find(key).isEmpty());
    }

    @Test
    void confirmsAndDeletesPayloadOnlyAfterSuccessfulDelivery() {
        var fixture = fixture(8);
        UUID key = envelopeKey(300);
        fixture.buffer.append(envelope(300, IngressKind.ALARM));
        fixture.client.results.add(GatewayOutboxDispatcher.DeliveryResult.retryable("HTTP_401"));
        fixture.client.results.add(GatewayOutboxDispatcher.DeliveryResult.success());

        fixture.dispatcher.dispatchOnce();
        GatewayOutboxRepository.OutboxEntry retained = fixture.repository.find(key).orElseThrow();
        assertEquals(GatewayOutboxRepository.DeliveryStatus.PENDING, retained.status());
        assertNotNull(retained.payloadJson());

        fixture.clock.advance(Duration.ofSeconds(1));
        fixture.dispatcher.dispatchOnce();
        GatewayOutboxRepository.OutboxEntry delivered = fixture.repository.find(key).orElseThrow();
        assertEquals(GatewayOutboxRepository.DeliveryStatus.DELIVERED, delivered.status());
        assertNull(delivered.payloadJson());
        assertNotNull(delivered.deliveredAt());
    }

    @Test
    void sendsBearerAndCredentialVersionWithoutLoggingTheCredential() {
        Logger logger = (Logger) LoggerFactory.getLogger(OperationsApiClient.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            OperationsApiClient client = operationsClient(builder);
            GatewayIngressEnvelope envelope = envelope(400, IngressKind.ALARM);
            server.expect(requestTo(OPERATIONS_ENDPOINT.toString()))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TEST_CREDENTIAL))
                    .andExpect(header("X-Service-Credential-Version", "1"))
                    .andExpect(jsonPath("$[0].idempotencyKey")
                            .value(envelope.idempotencyKey().toString()))
                    .andExpect(jsonPath("$[0].payloadJson").value(envelope.payloadJson()))
                    .andRespond(withStatus(HttpStatus.ACCEPTED));

            GatewayOutboxDispatcher.DeliveryResult result = client.deliver(List.of(envelope));

            assertTrue(result.successful());
            assertTrue(client.operationsApiReachable());
            assertTrue(events.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .noneMatch(message -> message.contains(TEST_CREDENTIAL)));
            server.verify();
        } finally {
            logger.detachAppender(events);
        }
    }

    @Test
    void retainsAndBacksOffForHttpErrorsUntilA2xxResponseConfirmsDelivery() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OperationsApiClient client = operationsClient(builder);
        var fixture = fixture(8, client);
        UUID key = envelopeKey(500);
        fixture.buffer.append(envelope(500, IngressKind.ALARM));

        expectStatus(server, HttpStatus.UNAUTHORIZED);
        expectStatus(server, HttpStatus.TOO_MANY_REQUESTS);
        expectStatus(server, HttpStatus.SERVICE_UNAVAILABLE);
        expectStatus(server, HttpStatus.NO_CONTENT);
        fixture.dispatcher.dispatchOnce();
        assertPending(fixture.repository, key, 1, NOW.plusSeconds(1), "HTTP_401");
        assertFalse(client.operationsApiReachable());

        fixture.clock.advance(Duration.ofSeconds(1));
        fixture.dispatcher.dispatchOnce();
        assertPending(fixture.repository, key, 2, NOW.plusSeconds(3), "HTTP_429");

        fixture.clock.advance(Duration.ofSeconds(2));
        fixture.dispatcher.dispatchOnce();
        assertPending(fixture.repository, key, 3, NOW.plusSeconds(7), "HTTP_503");

        fixture.clock.advance(Duration.ofSeconds(4));
        fixture.dispatcher.dispatchOnce();
        GatewayOutboxRepository.OutboxEntry delivered = fixture.repository.find(key).orElseThrow();
        assertEquals(GatewayOutboxRepository.DeliveryStatus.DELIVERED, delivered.status());
        assertNull(delivered.payloadJson());
        assertTrue(client.operationsApiReachable());
        server.verify();
    }

    private static Fixture<CapturingDeliveryClient> fixture(int maxAttempts) {
        return fixture(maxAttempts, new CapturingDeliveryClient());
    }

    private static <T extends GatewayOutboxDispatcher.DeliveryClient> Fixture<T> fixture(
            int maxAttempts, T deliveryClient) {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:dispatcher_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        MutableClock clock = new MutableClock(NOW);
        GatewayOutboxRepository repository = new GatewayOutboxRepository(dataSource);
        GatewayIngressBuffer buffer = new GatewayIngressBuffer(
                repository, new ObjectMapper(), clock);
        GatewayOutboxDispatcher dispatcher = new GatewayOutboxDispatcher(
                repository,
                deliveryClient,
                clock,
                maxAttempts,
                Duration.ofSeconds(1),
                Duration.ofMinutes(5));
        return new Fixture(dataSource, repository, buffer, dispatcher, deliveryClient, clock);
    }

    private static OperationsApiClient operationsClient(RestClient.Builder builder) {
        return new OperationsApiClient(
                builder, OPERATIONS_ENDPOINT, () -> TEST_CREDENTIAL, 1);
    }

    private static void assertBatchStateUpdateIsAtomic(
            GatewayOutboxDispatcher.DeliveryResult firstResult) {
        var fixture = fixture(8);
        UUID firstKey = envelopeKey(3_000);
        UUID secondKey = envelopeKey(3_001);
        fixture.buffer.append(envelope(3_000, IngressKind.ALARM));
        fixture.buffer.append(envelope(3_001, IngressKind.ALARM));
        AtomicInteger calls = new AtomicInteger();
        GatewayOutboxDispatcher.DeliveryClient sabotagingClient = batch -> {
            if (calls.getAndIncrement() == 0) {
                new JdbcTemplate(fixture.dataSource).update("""
                        UPDATE gateway_outbox SET status = 'PENDING'
                        WHERE idempotency_key = ?
                        """, secondKey);
                return firstResult;
            }
            return GatewayOutboxDispatcher.DeliveryResult.success();
        };
        GatewayOutboxDispatcher dispatcher = new GatewayOutboxDispatcher(
                fixture.repository,
                sabotagingClient,
                fixture.clock,
                8,
                Duration.ofSeconds(1),
                Duration.ofMinutes(5));

        assertThrows(OptimisticLockingFailureException.class, dispatcher::dispatchOnce);
        assertEquals(GatewayOutboxRepository.DeliveryStatus.DELIVERING,
                fixture.repository.find(firstKey).orElseThrow().status());
        assertEquals(GatewayOutboxRepository.DeliveryStatus.PENDING,
                fixture.repository.find(secondKey).orElseThrow().status());

        fixture.clock.advance(Duration.ofMinutes(1));
        assertEquals(2, dispatcher.dispatchOnce().delivered());
        assertEquals(GatewayOutboxRepository.DeliveryStatus.DELIVERED,
                fixture.repository.find(firstKey).orElseThrow().status());
        assertEquals(GatewayOutboxRepository.DeliveryStatus.DELIVERED,
                fixture.repository.find(secondKey).orElseThrow().status());
    }

    private static void assertExpiredLeaseIsFenced(boolean successfulCompletion) {
        var fixture = fixture(8);
        UUID key = envelopeKey(3_100 + (successfulCompletion ? 0 : 1));
        fixture.buffer.append(new GatewayIngressEnvelope(
                1, key, IngressKind.ALARM, NOW, "{\"sequence\":3100}"));
        GatewayOutboxRepository.OutboxEntry expired = fixture.repository.claimEligible(
                NOW, GatewayOutboxRepository.Priority.HIGH, 1).getFirst();

        fixture.clock.advance(Duration.ofMinutes(1));
        GatewayOutboxRepository.OutboxEntry current = fixture.repository.claimEligible(
                fixture.clock.instant(), GatewayOutboxRepository.Priority.HIGH, 1).getFirst();
        assertNotEquals(expired.nextAttemptAt(), current.nextAttemptAt());

        if (successfulCompletion) {
            assertThrows(OptimisticLockingFailureException.class,
                    () -> fixture.repository.markDelivered(
                            List.of(expired), fixture.clock.instant()));
        } else {
            GatewayOutboxRepository.FailureUpdate staleFailure =
                    new GatewayOutboxRepository.FailureUpdate(
                            expired,
                            expired.attemptCount() + 1,
                            fixture.clock.instant().plusSeconds(1),
                            false,
                            "HTTP_503");
            assertThrows(OptimisticLockingFailureException.class,
                    () -> fixture.repository.markFailed(List.of(staleFailure)));
        }
        GatewayOutboxRepository.OutboxEntry stillClaimed =
                fixture.repository.find(key).orElseThrow();
        assertEquals(GatewayOutboxRepository.DeliveryStatus.DELIVERING, stillClaimed.status());
        assertEquals(current.nextAttemptAt(), stillClaimed.nextAttemptAt());

        fixture.repository.markDelivered(List.of(current), fixture.clock.instant());
        assertEquals(GatewayOutboxRepository.DeliveryStatus.DELIVERED,
                fixture.repository.find(key).orElseThrow().status());
    }

    private static GatewayOutboxDispatcher dispatcherWithDelayedResult(
            Fixture<?> fixture, GatewayOutboxDispatcher.DeliveryResult result) {
        GatewayOutboxDispatcher.DeliveryClient delayedClient = batch -> {
            fixture.clock.advance(Duration.ofSeconds(2));
            return result;
        };
        return new GatewayOutboxDispatcher(
                fixture.repository,
                delayedClient,
                fixture.clock,
                8,
                Duration.ofSeconds(1),
                Duration.ofMinutes(5));
    }

    private static void expectStatus(MockRestServiceServer server, HttpStatus status) {
        server.expect(requestTo(OPERATIONS_ENDPOINT.toString()))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(status));
    }

    private static void assertPending(
            GatewayOutboxRepository repository,
            UUID key,
            int attemptCount,
            Instant nextAttemptAt,
            String errorCode) {
        GatewayOutboxRepository.OutboxEntry entry = repository.find(key).orElseThrow();
        assertEquals(GatewayOutboxRepository.DeliveryStatus.PENDING, entry.status());
        assertEquals(attemptCount, entry.attemptCount());
        assertEquals(nextAttemptAt, entry.nextAttemptAt());
        assertEquals(errorCode, entry.lastErrorCode());
        assertNotNull(entry.payloadJson());
    }

    private static GatewayIngressEnvelope envelope(int number, IngressKind kind) {
        return new GatewayIngressEnvelope(
                1,
                envelopeKey(number),
                kind,
                NOW.plusMillis(number),
                "{\"sequence\":" + number + "}");
    }

    private static UUID envelopeKey(int number) {
        return UUID.fromString("20000000-0000-0000-0000-" + String.format("%012d", number));
    }

    private record Fixture<T extends GatewayOutboxDispatcher.DeliveryClient>(
            DataSource dataSource,
            GatewayOutboxRepository repository,
            GatewayIngressBuffer buffer,
            GatewayOutboxDispatcher dispatcher,
            T client,
            MutableClock clock) {
    }

    private static final class CapturingDeliveryClient
            implements GatewayOutboxDispatcher.DeliveryClient {
        private final List<List<GatewayIngressEnvelope>> batches = new ArrayList<>();
        private final List<GatewayOutboxDispatcher.DeliveryResult> results = new ArrayList<>();

        @Override
        public GatewayOutboxDispatcher.DeliveryResult deliver(List<GatewayIngressEnvelope> batch) {
            batches.add(List.copyOf(batch));
            return results.isEmpty()
                    ? GatewayOutboxDispatcher.DeliveryResult.success()
                    : results.removeFirst();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }
}
