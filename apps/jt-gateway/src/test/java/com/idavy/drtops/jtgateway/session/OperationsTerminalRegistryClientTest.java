package com.idavy.drtops.jtgateway.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.io.IOException;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    private static final UUID TERMINAL_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SYSTEM_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID VEHICLE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String PRESENTED_DIGEST = "c".repeat(64);

    @Test
    void validatesAndDefensivelyCopiesTheImmutableTerminalSessionContext() {
        Set<String> roles = new HashSet<>(Set.of("LOCATION_BACKUP", "ACTIVE_SAFETY"));
        List<String> modules = new ArrayList<>(List.of("ADAS", "DMS"));

        TerminalSessionContext context = new TerminalSessionContext(
                TERMINAL_ID, SYSTEM_ID, VEHICLE_ID, roles, "WGS84",
                "T/JSATL12-2017", modules, 7);
        roles.clear();
        modules.clear();

        assertEquals(Set.of("LOCATION_BACKUP", "ACTIVE_SAFETY"), context.roles());
        assertEquals(List.of("ADAS", "DMS"), context.activeSafetyModules());
        assertThrows(UnsupportedOperationException.class, () -> context.roles().add("VIDEO"));
        assertThrows(UnsupportedOperationException.class,
                () -> context.activeSafetyModules().add("BSD"));
        assertThrows(NullPointerException.class, () -> new TerminalSessionContext(
                null, SYSTEM_ID, VEHICLE_ID, Set.of("VIDEO"), "WGS84",
                null, List.of(), 1));
        assertThrows(IllegalArgumentException.class, () -> new TerminalSessionContext(
                TERMINAL_ID, SYSTEM_ID, VEHICLE_ID, Set.of(" "), "WGS84",
                null, List.of(), 1));
        assertThrows(IllegalArgumentException.class, () -> new TerminalSessionContext(
                TERMINAL_ID, SYSTEM_ID, VEHICLE_ID, Set.of("VIDEO"), "EPSG:4326",
                null, List.of(), 1));
        assertThrows(IllegalArgumentException.class, () -> new TerminalSessionContext(
                TERMINAL_ID, SYSTEM_ID, VEHICLE_ID, Set.of("VIDEO"), "GCJ02",
                null, List.of(), 0));
    }

    @Test
    void mapsNestedRegistrationContextBeforeIssuingAndCompletingTheToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OperationsTerminalRegistryClient registry = newRegistry(builder, "nested_registration_context");
        server.expect(requestTo(
                        "http://operations.invalid/internal/jt-gateway/registrations/verify"))
                .andRespond(withSuccess(approvedRegistrationJson(TERMINAL_ID),
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(
                        "http://operations.invalid/internal/jt-gateway/registrations/"
                                + TERMINAL_ID + "/complete"))
                .andExpect(jsonPath("$.tokenVersion").value(7))
                .andExpect(jsonPath("$.tokenSha256").isString())
                .andExpect(jsonPath("$.gatewayInstance").value("gateway-registration-test"))
                .andRespond(withSuccess());

        RegistrationDecision decision = registry.verifyRegistration(syntheticIdentity());

        assertTrue(decision.approved());
        assertEquals(new TerminalSessionContext(
                        TERMINAL_ID, SYSTEM_ID, VEHICLE_ID,
                        Set.of("LOCATION_BACKUP", "ACTIVE_SAFETY", "VIDEO"),
                        "WGS84", "T/JSATL12-2017", List.of("ADAS", "DMS"), 7),
                decision.context());
        assertEquals(TERMINAL_ID, decision.terminalId());
        assertEquals(VEHICLE_ID, decision.vehicleId());
        byte[] issuedToken = decision.consumeAuthenticationToken();
        try {
            assertTrue(issuedToken.length > 0);
            assertFalse(decision.hasAvailableAuthenticationToken());
            assertThrows(IllegalStateException.class, decision::consumeAuthenticationToken);
        } finally {
            Arrays.fill(issuedToken, (byte) 0);
            decision.destroyAuthenticationToken();
        }
        assertTrue(decision.authenticationTokenDestroyed());
        server.verify();
    }

    @Test
    void rejectsMissingOrInconsistentRegistrationContextBeforeTokenCompletion() {
        for (String response : List.of(
                """
                {"data":{"approved":true,
                 "terminalId":"11111111-1111-1111-1111-111111111111",
                 "onboardSystemId":"22222222-2222-2222-2222-222222222222",
                 "vehicleId":"33333333-3333-3333-3333-333333333333",
                 "sourceCoordinateSystem":"WGS84","tokenVersion":7}}
                """,
                approvedRegistrationJson(
                        UUID.fromString("44444444-4444-4444-4444-444444444444")))) {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            OperationsTerminalRegistryClient registry = newRegistry(
                    builder, "invalid_registration_context_" + UUID.randomUUID());
            server.expect(requestTo(
                            "http://operations.invalid/internal/jt-gateway/registrations/verify"))
                    .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

            RegistrationDecision decision = registry.verifyRegistration(syntheticIdentity());

            assertFalse(decision.approved());
            assertEquals(RegistrationRejection.NOT_PREPROVISIONED, decision.rejection());
            assertNull(decision.context());
            assertFalse(decision.hasAvailableAuthenticationToken());
            assertTrue(decision.authenticationTokenDestroyed());
            server.verify();
        }
    }

    @Test
    void mapsEquivalentTerminalAndIdentityAuthenticationContextsWithoutSendingUuid() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OperationsTerminalRegistryClient registry = newRegistry(builder, "identity_authentication_context");
        server.expect(requestTo(
                        "http://operations.invalid/internal/jt-gateway/authentications/verify"))
                .andExpect(jsonPath("$.terminalId").value(TERMINAL_ID.toString()))
                .andExpect(jsonPath("$.tokenVersion").value(7))
                .andRespond(withSuccess(approvedAuthenticationJson(), MediaType.APPLICATION_JSON));
        server.expect(requestTo(
                        "http://operations.invalid/internal/jt-gateway/authentications/verify-by-identity"))
                .andExpect(jsonPath("$.protocolVersion").value("JT808_2019"))
                .andExpect(jsonPath("$.terminalPhone").value("00000000000000000001"))
                .andExpect(jsonPath("$.tokenSha256").value(PRESENTED_DIGEST))
                .andExpect(jsonPath("$.gatewayInstance").value("gateway-registration-test"))
                .andExpect(jsonPath("$.terminalId").doesNotExist())
                .andRespond(withSuccess(approvedAuthenticationJson(), MediaType.APPLICATION_JSON));

        AuthenticationDecision byTerminal = registry.verifyAuthentication(
                TERMINAL_ID, 7, PRESENTED_DIGEST);
        AuthenticationDecision byIdentity = registry.verifyAuthenticationByIdentity(
                com.idavy.drtops.jt.protocol.codec.ProtocolVersion.JT808_2019,
                "00000000000000000001", PRESENTED_DIGEST);

        assertTrue(byTerminal.approved());
        assertTrue(byIdentity.approved());
        assertEquals(byTerminal.context(), byIdentity.context());
        assertEquals(SYSTEM_ID, byIdentity.context().onboardSystemId());
        server.verify();
    }

    @Test
    void rejectsApprovedAuthenticationWithoutAValidContext() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OperationsTerminalRegistryClient registry = newRegistry(builder, "missing_authentication_context");
        server.expect(requestTo(
                        "http://operations.invalid/internal/jt-gateway/authentications/verify"))
                .andRespond(withSuccess(
                        "{\"data\":{\"approved\":true}}", MediaType.APPLICATION_JSON));

        AuthenticationDecision decision = registry.verifyAuthentication(
                TERMINAL_ID, 7, PRESENTED_DIGEST);

        assertFalse(decision.approved());
        assertNull(decision.context());
        assertEquals(AuthenticationRejection.TOKEN_MISMATCH, decision.rejection());
        server.verify();
    }

    @Test
    void rejectsApprovedRegistrationWithAReasonBeforeIssuingOrCompletingToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OperationsTerminalRegistryClient registry = newRegistry(
                builder, "registration_approved_with_reason");
        server.expect(requestTo(
                        "http://operations.invalid/internal/jt-gateway/registrations/verify"))
                .andRespond(withSuccess("""
                        {"data":{"approved":true,
                         "terminalId":"11111111-1111-1111-1111-111111111111",
                         "onboardSystemId":"22222222-2222-2222-2222-222222222222",
                         "vehicleId":"33333333-3333-3333-3333-333333333333",
                         "roles":["LOCATION_BACKUP","ACTIVE_SAFETY","VIDEO"],
                         "sourceCoordinateSystem":"WGS84",
                         "activeSafetyStandard":"T/JSATL12-2017",
                         "activeSafetyModules":["ADAS","DMS"],"tokenVersion":7,
                         "context":{"terminalId":"11111111-1111-1111-1111-111111111111",
                          "onboardSystemId":"22222222-2222-2222-2222-222222222222",
                          "vehicleId":"33333333-3333-3333-3333-333333333333",
                          "roles":["LOCATION_BACKUP","ACTIVE_SAFETY","VIDEO"],
                          "sourceCoordinateSystem":"WGS84",
                          "activeSafetyStandard":"T/JSATL12-2017",
                          "activeSafetyModules":["ADAS","DMS"],"tokenVersion":7},
                         "warnings":[],"reasonCode":"SHOULD_NOT_COEXIST"}}
                        """, MediaType.APPLICATION_JSON));

        RegistrationDecision decision = registry.verifyRegistration(syntheticIdentity());

        assertFalse(decision.approved());
        assertEquals(RegistrationRejection.NOT_PREPROVISIONED, decision.rejection());
        assertFalse(decision.hasAvailableAuthenticationToken());
        assertTrue(decision.authenticationTokenDestroyed());
        assertFalse(decision.toString().contains("SHOULD_NOT_COEXIST"));
        server.verify();
    }

    @Test
    void rejectsApprovedAuthenticationWithAReasonDespiteAValidContext() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OperationsTerminalRegistryClient registry = newRegistry(
                builder, "authentication_approved_with_reason");
        server.expect(requestTo(
                        "http://operations.invalid/internal/jt-gateway/authentications/verify"))
                .andRespond(withSuccess("""
                        {"data":{"approved":true,
                         "context":{"terminalId":"11111111-1111-1111-1111-111111111111",
                          "onboardSystemId":"22222222-2222-2222-2222-222222222222",
                          "vehicleId":"33333333-3333-3333-3333-333333333333",
                          "roles":["LOCATION_BACKUP","ACTIVE_SAFETY","VIDEO"],
                          "sourceCoordinateSystem":"WGS84",
                          "activeSafetyStandard":"T/JSATL12-2017",
                          "activeSafetyModules":["ADAS","DMS"],"tokenVersion":7},
                         "reasonCode":"SHOULD_NOT_COEXIST"}}
                        """, MediaType.APPLICATION_JSON));

        AuthenticationDecision decision = registry.verifyAuthentication(
                TERMINAL_ID, 7, PRESENTED_DIGEST);

        assertFalse(decision.approved());
        assertNull(decision.context());
        assertEquals(AuthenticationRejection.TOKEN_MISMATCH, decision.rejection());
        assertFalse(decision.toString().contains("SHOULD_NOT_COEXIST"));
        server.verify();
    }

    @Test
    void preservesSafeFieldSpecificRegistrationRejectionFromOperationsApi() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:registration_reason_outbox;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OperationsTerminalRegistryClient registry = new OperationsTerminalRegistryClient(
                builder, "http://operations.invalid", CREDENTIAL, 3,
                "gateway-registration-test", new SecureRandom(),
                new OperationsApiStatus(Clock.systemUTC()),
                new GatewayIngressBuffer(
                        new GatewayOutboxRepository(dataSource), mapper, Clock.systemUTC()),
                mapper);
        server.expect(requestTo(
                        "http://operations.invalid/internal/jt-gateway/registrations/verify"))
                .andExpect(jsonPath("$.protocolVersion").value("JT808_2019"))
                .andRespond(withSuccess("""
                        {"data":{"approved":false,"reasonCode":"PROTOCOL_VERSION_MISMATCH"}}
                        """, MediaType.APPLICATION_JSON));

        RegistrationDecision decision = registry.verifyRegistration(new TerminalRegistrationIdentity(
                com.idavy.drtops.jt.protocol.codec.ProtocolVersion.JT808_2019,
                "00000000000000000001", "MFG01", "MODEL-X", "T-001", "浙A10001"));

        assertFalse(decision.approved());
        assertEquals(RegistrationRejection.PROTOCOL_VERSION_MISMATCH, decision.rejection());
        server.verify();
    }

    @Test
    void classifiesBadRegistrationVerifyRequestWithoutExposingResponseBody() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:registration_verify_bad_request;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OperationsTerminalRegistryClient registry = new OperationsTerminalRegistryClient(
                builder, "http://operations.invalid", CREDENTIAL, 3,
                "gateway-registration-test", new SecureRandom(),
                new OperationsApiStatus(Clock.systemUTC()),
                new GatewayIngressBuffer(
                        new GatewayOutboxRepository(dataSource), mapper, Clock.systemUTC()),
                mapper);
        String syntheticSensitiveBody = "SYNTHETIC-PRIVATE-REGISTRATION";
        server.expect(requestTo(
                        "http://operations.invalid/internal/jt-gateway/registrations/verify"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .body("{\"error\":\"" + syntheticSensitiveBody + "\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        RegistrationDecision decision = registry.verifyRegistration(new TerminalRegistrationIdentity(
                com.idavy.drtops.jt.protocol.codec.ProtocolVersion.JT808_2019,
                "00000000000000000001", "MFG01", "MODEL-X", "T-001", "TEST-A10001"));

        assertFalse(decision.approved());
        assertEquals("REGISTRATION_VERIFY_BAD_REQUEST", decision.rejection().name());
        assertFalse(decision.toString().contains(syntheticSensitiveBody));
        server.verify();
    }

    @Test
    void classifiesRegistrationCompletionConflictSeparatelyFromVerification() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:registration_complete_conflict;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OperationsTerminalRegistryClient registry = new OperationsTerminalRegistryClient(
                builder, "http://operations.invalid", CREDENTIAL, 3,
                "gateway-registration-test", new SecureRandom(),
                new OperationsApiStatus(Clock.systemUTC()),
                new GatewayIngressBuffer(
                        new GatewayOutboxRepository(dataSource), mapper, Clock.systemUTC()),
                mapper);
        server.expect(requestTo(
                        "http://operations.invalid/internal/jt-gateway/registrations/verify"))
                .andRespond(withSuccess(
                        approvedRegistrationJson(TERMINAL_ID), MediaType.APPLICATION_JSON));
        server.expect(requestTo(
                        "http://operations.invalid/internal/jt-gateway/registrations/11111111-1111-1111-1111-111111111111/complete"))
                .andRespond(withStatus(HttpStatus.CONFLICT));

        RegistrationDecision decision = registry.verifyRegistration(new TerminalRegistrationIdentity(
                com.idavy.drtops.jt.protocol.codec.ProtocolVersion.JT808_2019,
                "00000000000000000001", "MFG01", "MODEL-X", "T-001", "TEST-A10001"));

        assertFalse(decision.approved());
        assertEquals("REGISTRATION_COMPLETE_CONFLICT", decision.rejection().name());
        server.verify();
    }

    @Test
    void classifiesUnauthorizedRegistrationVerification() {
        assertVerifyFailure(HttpStatus.UNAUTHORIZED, "REGISTRATION_VERIFY_UNAUTHORIZED");
        assertVerifyFailure(HttpStatus.FORBIDDEN, "REGISTRATION_VERIFY_UNAUTHORIZED");
    }

    @Test
    void classifiesConflictingRegistrationVerification() {
        assertVerifyFailure(HttpStatus.CONFLICT, "REGISTRATION_VERIFY_CONFLICT");
    }

    @Test
    void classifiesUnavailableRegistrationVerification() {
        assertVerifyFailure(HttpStatus.SERVICE_UNAVAILABLE, "REGISTRATION_VERIFY_UNAVAILABLE");
    }

    @Test
    void classifiesBadRegistrationCompletionRequest() {
        assertCompletionFailure(HttpStatus.BAD_REQUEST, "REGISTRATION_COMPLETE_BAD_REQUEST");
    }

    @Test
    void classifiesUnauthorizedRegistrationCompletion() {
        assertCompletionFailure(HttpStatus.UNAUTHORIZED, "REGISTRATION_COMPLETE_UNAUTHORIZED");
        assertCompletionFailure(HttpStatus.FORBIDDEN, "REGISTRATION_COMPLETE_UNAUTHORIZED");
    }

    @Test
    void classifiesUnavailableRegistrationCompletion() {
        assertCompletionFailure(HttpStatus.SERVICE_UNAVAILABLE, "REGISTRATION_COMPLETE_UNAVAILABLE");
    }

    @Test
    void classifiesRegistrationVerificationTransportFailureWithoutLeakingExceptionText() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OperationsTerminalRegistryClient registry = newRegistry(builder, "registration_verify_transport");
        String syntheticSensitiveMessage = "SYNTHETIC-TRANSPORT-PRIVATE";
        server.expect(requestTo(
                        "http://operations.invalid/internal/jt-gateway/registrations/verify"))
                .andRespond(request -> { throw new IOException(syntheticSensitiveMessage); });

        RegistrationDecision decision = registry.verifyRegistration(syntheticIdentity());

        assertEquals("REGISTRATION_VERIFY_UNAVAILABLE", decision.rejection().name());
        assertFalse(decision.toString().contains(syntheticSensitiveMessage));
        server.verify();
    }

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

    private static void assertVerifyFailure(HttpStatus status, String expectedReason) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OperationsTerminalRegistryClient registry = newRegistry(
                builder, "registration_verify_" + status.value() + "_" + UUID.randomUUID());
        server.expect(requestTo(
                        "http://operations.invalid/internal/jt-gateway/registrations/verify"))
                .andRespond(withStatus(status));

        RegistrationDecision decision = registry.verifyRegistration(syntheticIdentity());

        assertFalse(decision.approved());
        assertEquals(expectedReason, decision.rejection().name());
        server.verify();
    }

    private static void assertCompletionFailure(HttpStatus status, String expectedReason) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OperationsTerminalRegistryClient registry = newRegistry(
                builder, "registration_complete_" + status.value() + "_" + UUID.randomUUID());
        server.expect(requestTo(
                        "http://operations.invalid/internal/jt-gateway/registrations/verify"))
                .andRespond(withSuccess(
                        approvedRegistrationJson(TERMINAL_ID), MediaType.APPLICATION_JSON));
        server.expect(requestTo(
                        "http://operations.invalid/internal/jt-gateway/registrations/11111111-1111-1111-1111-111111111111/complete"))
                .andRespond(withStatus(status));

        RegistrationDecision decision = registry.verifyRegistration(syntheticIdentity());

        assertFalse(decision.approved());
        assertEquals(expectedReason, decision.rejection().name());
        server.verify();
    }

    private static OperationsTerminalRegistryClient newRegistry(
            RestClient.Builder builder, String databaseName) {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + databaseName.replace('-', '_')
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        return new OperationsTerminalRegistryClient(
                builder, "http://operations.invalid", CREDENTIAL, 3,
                "gateway-registration-test", new SecureRandom(),
                new OperationsApiStatus(Clock.systemUTC()),
                new GatewayIngressBuffer(
                        new GatewayOutboxRepository(dataSource), mapper, Clock.systemUTC()),
                mapper);
    }

    private static String approvedRegistrationJson(UUID nestedTerminalId) {
        return """
                {"data":{"approved":true,
                 "terminalId":"11111111-1111-1111-1111-111111111111",
                 "onboardSystemId":"22222222-2222-2222-2222-222222222222",
                 "vehicleId":"33333333-3333-3333-3333-333333333333",
                 "roles":["LOCATION_BACKUP","ACTIVE_SAFETY","VIDEO"],
                 "sourceCoordinateSystem":"WGS84",
                 "activeSafetyStandard":"T/JSATL12-2017",
                 "activeSafetyModules":["ADAS","DMS"],"tokenVersion":7,
                 "context":{"terminalId":"%s",
                  "onboardSystemId":"22222222-2222-2222-2222-222222222222",
                  "vehicleId":"33333333-3333-3333-3333-333333333333",
                  "roles":["LOCATION_BACKUP","ACTIVE_SAFETY","VIDEO"],
                  "sourceCoordinateSystem":"WGS84",
                  "activeSafetyStandard":"T/JSATL12-2017",
                  "activeSafetyModules":["ADAS","DMS"],"tokenVersion":7},
                 "warnings":[]}}
                """.formatted(nestedTerminalId);
    }

    private static String approvedAuthenticationJson() {
        return """
                {"data":{"approved":true,
                 "context":{"terminalId":"11111111-1111-1111-1111-111111111111",
                  "onboardSystemId":"22222222-2222-2222-2222-222222222222",
                  "vehicleId":"33333333-3333-3333-3333-333333333333",
                  "roles":["LOCATION_BACKUP","ACTIVE_SAFETY","VIDEO"],
                  "sourceCoordinateSystem":"WGS84",
                  "activeSafetyStandard":"T/JSATL12-2017",
                  "activeSafetyModules":["ADAS","DMS"],"tokenVersion":7}}}
                """;
    }

    private static TerminalRegistrationIdentity syntheticIdentity() {
        return new TerminalRegistrationIdentity(
                com.idavy.drtops.jt.protocol.codec.ProtocolVersion.JT808_2019,
                "00000000000000000001", "MFG01", "MODEL-X", "T-001", "TEST-A10001");
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
