package com.idavy.drtops.jtgateway.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.jtgateway.ingress.GatewayIngressBuffer;
import com.idavy.drtops.jtgateway.ingress.GatewayOutboxDispatcher;
import com.idavy.drtops.jtgateway.ingress.GatewayOutboxRepository;
import com.idavy.drtops.jtgateway.ingress.IngressKind;
import com.idavy.drtops.jtgateway.ingress.OperationsApiClient;
import com.idavy.drtops.jtgateway.ingress.OperationsApiStatus;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OperationsTerminalRegistryClientTest {
    private static final Instant NOW = Instant.parse("2026-08-21T10:00:00Z");
    private static final String CREDENTIAL = "synthetic-audit-service-credential";
    private static final URI INGRESS = URI.create("http://operations.invalid/internal/jt-gateway/ingress");
    private static final URI AUDIT = URI.create("http://operations.invalid/internal/jt-gateway/audit-events");

    @Test
    void persistsSessionAuditThenRetriesHttpFailureAndRecoversWithoutSensitiveMaterial() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:session_audit_outbox;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        GatewayOutboxRepository repository = new GatewayOutboxRepository(dataSource);
        GatewayIngressBuffer buffer = new GatewayIngressBuffer(repository, mapper, clock);
        OperationsApiStatus status = new OperationsApiStatus(clock);
        OperationsTerminalRegistryClient registry = new OperationsTerminalRegistryClient(
                RestClient.builder(), "http://unavailable.invalid", CREDENTIAL, 3,
                "gateway-audit-test", new SecureRandom(), status, buffer, mapper);

        registry.recordSessionAudit(new SessionAuditIngress(
                SessionAuditType.AUTHENTICATION_REJECTED,
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "****9012", "198.51.100.10:1234", "TOKEN_MISMATCH", NOW));

        assertEquals(1, repository.pendingCount());
        assertTrue(repository.claimEligible(NOW, GatewayOutboxRepository.Priority.HIGH, 1).isEmpty(),
                "session audits must not share the ordinary high-priority ingress lane");
        GatewayOutboxRepository.OutboxEntry pending = repository.find(
                repository.claimSessionAudits(NOW, 1).getFirst().idempotencyKey()).orElseThrow();
        assertEquals(IngressKind.SESSION_AUDIT, pending.kind());
        assertEquals(pending.idempotencyKey().toString(),
                mapper.readTree(pending.payloadJson()).required("idempotencyKey").asText());
        assertFalse(pending.payloadJson().contains("123456789012"));
        assertFalse(pending.payloadJson().contains("secret-token"));
        repository.recoverInterruptedDeliveries(NOW);

        RestClient.Builder deliveryBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(deliveryBuilder).build();
        OperationsApiClient delivery = new OperationsApiClient(
                deliveryBuilder, INGRESS, AUDIT, () -> CREDENTIAL, 3, status);
        GatewayOutboxDispatcher dispatcher = new GatewayOutboxDispatcher(
                repository, delivery, clock, 8, Duration.ofSeconds(1), Duration.ofMinutes(5));
        server.expect(requestTo(AUDIT.toString()))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + CREDENTIAL))
                .andExpect(jsonPath("$.idempotencyKey").value(pending.idempotencyKey().toString()))
                .andExpect(jsonPath("$.eventType").value("PROTOCOL_REJECTED"))
                .andExpect(jsonPath("$.result").value("REJECTED"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(AUDIT.toString()))
                .andExpect(jsonPath("$.idempotencyKey").value(pending.idempotencyKey().toString()))
                .andRespond(withSuccess("""
                        {"data":{"idempotencyKey":"%s","status":"REPLAYED"}}
                        """.formatted(pending.idempotencyKey()), MediaType.APPLICATION_JSON));

        assertEquals(1, dispatcher.dispatchOnce().retried());
        assertEquals(1, repository.pendingCount());
        clock.advance(Duration.ofSeconds(1));

        assertEquals(1, dispatcher.dispatchOnce().delivered());
        assertEquals(0, repository.pendingCount());
        assertTrue(delivery.lastDeliverySuccessful());
        server.verify();
    }

    @Test
    void refusesToDeliverWhenTheAuditResponseHasTheWrongKeyOrANonSuccessStatus() {
        RestClient.Builder deliveryBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(deliveryBuilder).build();
        OperationsApiClient delivery = new OperationsApiClient(
                deliveryBuilder, INGRESS, AUDIT, () -> CREDENTIAL, 3,
                new OperationsApiStatus(Clock.systemUTC()));
        UUID key = UUID.randomUUID();
        com.idavy.drtops.jtgateway.ingress.GatewayIngressEnvelope audit =
                new com.idavy.drtops.jtgateway.ingress.GatewayIngressEnvelope(
                        1, key, IngressKind.SESSION_AUDIT, NOW,
                        "{\"idempotencyKey\":\"" + key + "\",\"eventType\":\"OFFLINE\"}");
        server.expect(requestTo(AUDIT.toString()))
                .andRespond(withSuccess("""
                        {"data":{"idempotencyKey":"%s","status":"ACCEPTED"}}
                        """.formatted(UUID.randomUUID()), MediaType.APPLICATION_JSON));
        server.expect(requestTo(AUDIT.toString()))
                .andRespond(withSuccess("""
                        {"data":{"idempotencyKey":"%s","status":"REJECTED"}}
                        """.formatted(key), MediaType.APPLICATION_JSON));

        GatewayOutboxDispatcher.DeliveryResult wrongKey = delivery.deliver(java.util.List.of(audit));
        GatewayOutboxDispatcher.DeliveryResult rejected = delivery.deliver(java.util.List.of(audit));

        assertFalse(wrongKey.successful());
        assertEquals("API_AUDIT_RESPONSE_INVALID", wrongKey.errorCode());
        assertFalse(rejected.successful());
        assertEquals("API_AUDIT_RESPONSE_INVALID", rejected.errorCode());
        server.verify();
    }

    @Test
    void injectsTheDurableOutboxUuidIntoALegacySessionAuditPayload() {
        RestClient.Builder deliveryBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(deliveryBuilder).build();
        OperationsApiClient delivery = new OperationsApiClient(
                deliveryBuilder, INGRESS, AUDIT, () -> CREDENTIAL, 3,
                new OperationsApiStatus(Clock.systemUTC()));
        UUID key = UUID.randomUUID();
        com.idavy.drtops.jtgateway.ingress.GatewayIngressEnvelope legacyAudit =
                new com.idavy.drtops.jtgateway.ingress.GatewayIngressEnvelope(
                        1, key, IngressKind.SESSION_AUDIT, NOW,
                        "{\"eventType\":\"OFFLINE\",\"result\":\"APPLIED\"}");
        server.expect(requestTo(AUDIT.toString()))
                .andExpect(jsonPath("$.idempotencyKey").value(key.toString()))
                .andRespond(withSuccess("""
                        {"data":{"idempotencyKey":"%s","status":"ACCEPTED"}}
                        """.formatted(key), MediaType.APPLICATION_JSON));

        GatewayOutboxDispatcher.DeliveryResult result = delivery.deliver(java.util.List.of(legacyAudit));

        assertTrue(result.successful());
        server.verify();
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }
}
