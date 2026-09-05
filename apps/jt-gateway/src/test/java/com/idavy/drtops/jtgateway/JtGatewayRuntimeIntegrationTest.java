package com.idavy.drtops.jtgateway;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.jt.protocol.codec.ProtocolVersion;
import com.idavy.drtops.jt.protocol.codec.Jt808Frame;
import com.idavy.drtops.jt.protocol.codec.Jt808FrameDecoder;
import com.idavy.drtops.jt.protocol.codec.Jt808FrameEncoder;
import com.idavy.drtops.jt.protocol.codec.Jt808MessageHeader;
import com.idavy.drtops.jtgateway.ingress.GatewayOutboxRepository;
import com.idavy.drtops.jtgateway.ingress.GatewayIngressBuffer;
import com.idavy.drtops.jtgateway.ingress.GatewayIngressEnvelope;
import com.idavy.drtops.jtgateway.ingress.GatewayOutboxDispatcher;
import com.idavy.drtops.jtgateway.ingress.IngressKind;
import com.idavy.drtops.jtgateway.netty.JtGatewayServer;
import com.idavy.drtops.jtgateway.session.RegistrationDecision;
import com.idavy.drtops.jtgateway.session.RegistrationMaintenancePolicy;
import com.idavy.drtops.jtgateway.session.PrivateVehicleIdentifierCapture;
import com.idavy.drtops.jtgateway.session.TerminalRegistrationIdentity;
import com.idavy.drtops.jtgateway.session.TerminalRegistryPort;
import com.idavy.drtops.jtgateway.session.TerminalSessionRegistry;
import com.idavy.drtops.jtsimulator.SimulatedTerminal;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

class JtGatewayRuntimeIntegrationTest {
    private static final String TERMINAL_IDENTITY = "000000000001";
    private static final String SERVICE_CREDENTIAL = "runtime-test-credential";
    private static final UUID TERMINAL_ID =
            UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final String RECORDER_IDENTITY = "000000000002";
    private static final UUID RECORDER_TERMINAL_ID =
            UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID VEHICLE_ID =
            UUID.fromString("55555555-5555-5555-5555-555555555555");

    @TempDir
    Path temporaryDirectory;

    @Test
    void enabledRuntimeUsesTheProductionGraphAndReleasesItsRandomPortOnContextClose() throws Exception {
        int devicePort = freeLoopbackPort();
        Path database = temporaryDirectory.resolve("runtime-outbox");
        try (OperationsApiStub api = new OperationsApiStub()) {
            ConfigurableApplicationContext context = start(Map.ofEntries(
                    Map.entry("jt.gateway.tcp.enabled", "true"),
                    Map.entry("jt.gateway.tcp.bind-address", "127.0.0.1"),
                    Map.entry("jt.gateway.tcp.port", Integer.toString(devicePort)),
                    Map.entry("jt.gateway.operations-api.base-url", api.baseUrl()),
                    Map.entry("jt.gateway.service-credential.version", "3"),
                    Map.entry("jt.gateway.service-credential.plaintext", SERVICE_CREDENTIAL),
                    Map.entry("jt.gateway.instance", "runtime-test"),
                    Map.entry("jt.gateway.dispatch.fixed-delay", "25"),
                    Map.entry("spring.datasource.url", fileDatabaseUrl(database)),
                    Map.entry("spring.datasource.username", "sa"),
                    Map.entry("spring.datasource.password", "")));
            try {
                assertEquals(1, context.getBeansOfType(JtGatewayServer.class).size());
                int managementPort = ((WebServerApplicationContext) context).getWebServer().getPort();
                await(() -> api.probeCount() > 0, Duration.ofSeconds(3));
                HttpResponse<String> initialReadiness = health(managementPort, "readiness");
                assertEquals(503, initialReadiness.statusCode(), initialReadiness.body());
                assertTrue(initialReadiness.body().contains("operationsApiStatus"));
                assertTrue(initialReadiness.body().contains(
                        "\"operationsApiProbeStatus\":\"UP\""));
                assertTrue(api.probesAreUnauthenticatedHealthGets());
                assertEquals(200, health(managementPort, "liveness").statusCode());
                HttpResponse<String> idleMetrics = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(
                                        "http://127.0.0.1:" + managementPort + "/actuator/prometheus"))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, idleMetrics.statusCode());
                assertTrue(idleMetrics.body().contains(
                        "jt_gateway_operations_api_reachable 0.0"), idleMetrics.body());
                TerminalSessionRegistry sessions = context.getBean(TerminalSessionRegistry.class);
                SimulatedTerminal terminal = new SimulatedTerminal(
                        TERMINAL_IDENTITY, ProtocolVersion.JT808_2013, "SIM-PLATE");
                try {
                    terminal.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), devicePort));
                    terminal.sendRegistration();
                    SimulatedTerminal.ReplyRecord registration = terminal.awaitReply(Duration.ofSeconds(3));
                    assertNotNull(registration);
                    assertEquals(0x8100, registration.messageId());
                    assertEquals(0, registration.result(), api::diagnostic);
                    assertEquals(92, registration.bodyHex().length(),
                            "default registration reply must retain a 43-character token");
                    terminal.sendAuthentication();
                    SimulatedTerminal.ReplyRecord authentication = terminal.awaitReply(Duration.ofSeconds(3));
                    assertNotNull(authentication, api::diagnostic);
                    assertEquals(0, authentication.result());
                    await(() -> sessions.current(TERMINAL_ID).isPresent(), Duration.ofSeconds(1));
                    assertTrue(sessions.current(TERMINAL_ID).isPresent(),
                            "the production Netty server must use the application session registry");
                    assertEquals(200, health(managementPort, "readiness").statusCode());
                    terminal.sendPosition();
                    SimulatedTerminal.ReplyRecord position = terminal.awaitReply(Duration.ofSeconds(3));
                    assertNotNull(position);
                    assertEquals(0, position.result());
                    await(() -> api.ingressCount() == 1, Duration.ofSeconds(5));
                    GatewayOutboxRepository repository =
                            context.getBean(GatewayOutboxRepository.class);
                    await(() -> repository.pendingCount() == 0, Duration.ofSeconds(5));
                    assertEquals(0, repository.pendingCount());
                    assertTrue(api.allRequestsAuthenticated());

                    HttpResponse<String> health = HttpClient.newHttpClient().send(
                            HttpRequest.newBuilder(URI.create(
                                            "http://127.0.0.1:" + managementPort + "/actuator/health"))
                                    .GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, health.statusCode());
                    assertTrue(health.body().contains("jtGateway"));
                    assertTrue(health.body().contains("tcpListening"));
                    assertTrue(health.body().contains("bufferWritable"));
                    assertTrue(health.body().contains("operationsApiReachable"));
                    assertTrue(health.body().contains("outboxDelivering"));
                    assertTrue(health.body().contains("outboxDeadLetter"));
                    assertTrue(health.body().contains("oldestUnresolvedAgeSeconds"));
                    assertFalse(health.body().contains(SERVICE_CREDENTIAL));
                    assertFalse(health.body().contains(TERMINAL_IDENTITY));

                    HttpResponse<String> metrics = HttpClient.newHttpClient().send(
                            HttpRequest.newBuilder(URI.create(
                                            "http://127.0.0.1:" + managementPort + "/actuator/prometheus"))
                                    .GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, metrics.statusCode());
                    assertTrue(metrics.body().contains("jt_gateway_tcp_listening"));
                    assertTrue(metrics.body().contains("jt_gateway_buffer_writable"));
                    assertTrue(metrics.body().contains("jt_gateway_outbox_pending"));
                    assertTrue(metrics.body().contains("jt_gateway_operations_api_reachable"));
                    assertTrue(metrics.body().contains("jt_gateway_operations_delivery_successful"));
                    assertFalse(metrics.body().contains(SERVICE_CREDENTIAL));
                    assertEquals(200, health(managementPort, "readiness").statusCode());
                    context.getBean(JtGatewayServer.class).close();
                    HttpResponse<String> stoppedReadiness = health(managementPort, "readiness");
                    assertEquals(503, stoppedReadiness.statusCode());
                    assertTrue(stoppedReadiness.body().contains("\"tcpListening\":false"));
                } finally {
                    terminal.close();
                }
            } finally {
                context.close();
            }
            assertConnectionRefused(devicePort);
        }

        GatewayOutboxRepository restarted = new GatewayOutboxRepository(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        fileDatabaseUrl(database), "sa", ""));
        assertEquals(3, restarted.totalCount(),
                "location plus registration/authentication audits must survive context shutdown");
    }

    @Test
    void keepsTwoSyntheticDeviceSessionsIndependentWhenTheyShareOneVehicleIdentifier() throws Exception {
        // Mutation caught: indexing sessions by vehicle or vehicle identifier instead of terminalId.
        int devicePort = freeLoopbackPort();
        try (OperationsApiStub api = new OperationsApiStub();
                ConfigurableApplicationContext context = start(Map.ofEntries(
                        Map.entry("jt.gateway.tcp.enabled", "true"),
                        Map.entry("jt.gateway.tcp.bind-address", "127.0.0.1"),
                        Map.entry("jt.gateway.tcp.port", Integer.toString(devicePort)),
                        Map.entry("jt.gateway.operations-api.base-url", api.baseUrl()),
                        Map.entry("jt.gateway.service-credential.version", "3"),
                        Map.entry("jt.gateway.service-credential.plaintext", SERVICE_CREDENTIAL),
                        Map.entry("jt.gateway.instance", "runtime-dual-device-test"),
                        Map.entry("jt.gateway.dispatch.initial-delay", "600000"),
                        Map.entry("spring.datasource.url",
                                "jdbc:h2:mem:runtime_dual_device;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"),
                        Map.entry("spring.datasource.username", "sa"),
                        Map.entry("spring.datasource.password", "")))) {
            TerminalSessionRegistry sessions = context.getBean(TerminalSessionRegistry.class);
            try (SimulatedTerminal dispatch = new SimulatedTerminal(
                    TERMINAL_IDENTITY, ProtocolVersion.JT808_2013, "VEHICLE-A", "SYNTH", "D", "DSP001");
                    SimulatedTerminal recorder = new SimulatedTerminal(
                            RECORDER_IDENTITY, ProtocolVersion.JT808_2013, "VEHICLE-A", "SYNTH", "R", "REC001")) {
                authenticate(dispatch, devicePort);
                authenticate(recorder, devicePort);

                await(() -> sessions.current(TERMINAL_ID).isPresent()
                        && sessions.current(RECORDER_TERMINAL_ID).isPresent(), Duration.ofSeconds(3));
                assertTrue(sessions.current(TERMINAL_ID).isPresent());
                assertTrue(sessions.current(RECORDER_TERMINAL_ID).isPresent());
                assertEquals(2, api.distinctRegisteredTerminalIds());

                dispatch.disconnect();
                await(() -> sessions.current(TERMINAL_ID).isEmpty(), Duration.ofSeconds(3));
                assertTrue(sessions.current(RECORDER_TERMINAL_ID).isPresent(),
                        "disconnecting dispatch must not evict the recorder session");
            }
        }
    }

    @Test
    void recordsRoleViolationWithoutCreatingAttachmentMetadataForSyntheticDispatchTerminal() throws Exception {
        // Mutation caught: allowing a non-VIDEO device to register attachment metadata as business data.
        int devicePort = freeLoopbackPort();
        try (OperationsApiStub api = new OperationsApiStub();
                ConfigurableApplicationContext context = start(Map.ofEntries(
                        Map.entry("jt.gateway.tcp.enabled", "true"),
                        Map.entry("jt.gateway.tcp.bind-address", "127.0.0.1"),
                        Map.entry("jt.gateway.tcp.port", Integer.toString(devicePort)),
                        Map.entry("jt.gateway.operations-api.base-url", api.baseUrl()),
                        Map.entry("jt.gateway.service-credential.version", "3"),
                        Map.entry("jt.gateway.service-credential.plaintext", SERVICE_CREDENTIAL),
                        Map.entry("jt.gateway.instance", "runtime-role-violation-test"),
                        Map.entry("jt.gateway.dispatch.fixed-delay", "25"),
                        Map.entry("spring.datasource.url",
                                "jdbc:h2:mem:runtime_role_violation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"),
                        Map.entry("spring.datasource.username", "sa"),
                        Map.entry("spring.datasource.password", "")))) {
            try (SimulatedTerminal dispatch = new SimulatedTerminal(
                    TERMINAL_IDENTITY, ProtocolVersion.JT808_2013, "VEHICLE-A")) {
                authenticate(dispatch, devicePort);
                dispatch.sendFrame(0x1206, new byte[] {0, 1, 0});
                SimulatedTerminal.ReplyRecord reply = dispatch.awaitReply(Duration.ofSeconds(3));
                assertNotNull(reply);
                assertEquals(0, reply.result());

                await(() -> api.protocolAuditReasons().contains("DEVICE_ROLE_VIOLATION"),
                        Duration.ofSeconds(5));
                assertEquals(List.of("PROTOCOL_AUDIT"), api.ingressKinds());
                assertEquals(List.of("DEVICE_ROLE_VIOLATION"), api.protocolAuditReasons());
            }
        }
    }

    @Test
    void scopesShortAuthenticationTokenToExactConfiguredModels() throws Exception {
        assertAll(
                () -> assertEquals(50, registrationReplyBodyHexLength(
                                "SIM-MODEL", "short_token_exact_model"),
                        "a compatible model must receive a 22-character token"),
                () -> assertEquals(50, registrationReplyBodyHexLength(
                                "OTHER-MODEL,SIM-MODEL", "short_token_model_list"),
                        "an exact model in the compatibility list must receive a short token"),
                () -> assertEquals(92, registrationReplyBodyHexLength(
                                "sim-model", "short_token_case_mismatch"),
                        "model matching must remain case-sensitive and fail closed"));
    }

    @Test
    void configuredLegacyRegistrationLayoutFlowsThroughProductionPipeline() throws Exception {
        String terminalIdentity = "00000000" + TERMINAL_IDENTITY;
        String identityDigest = sha256Hex((ProtocolVersion.JT808_2019.name() + '\0'
                + terminalIdentity).getBytes(StandardCharsets.UTF_8));
        int devicePort = freeLoopbackPort();
        try (OperationsApiStub api = new OperationsApiStub();
                ConfigurableApplicationContext context = start(Map.ofEntries(
                        Map.entry("jt.gateway.tcp.enabled", "true"),
                        Map.entry("jt.gateway.tcp.bind-address", "127.0.0.1"),
                        Map.entry("jt.gateway.tcp.port", Integer.toString(devicePort)),
                        Map.entry("jt.gateway.operations-api.base-url", api.baseUrl()),
                        Map.entry("jt.gateway.service-credential.version", "3"),
                        Map.entry("jt.gateway.service-credential.plaintext", SERVICE_CREDENTIAL),
                        Map.entry("jt.gateway.instance", "runtime-legacy-layout-test"),
                        Map.entry("jt.gateway.registration-body-layout.legacy-jt808-2019-identities-sha256",
                                identityDigest),
                        Map.entry("jt.gateway.dispatch.initial-delay", "600000"),
                        Map.entry("spring.datasource.url",
                                "jdbc:h2:mem:runtime_legacy_layout;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"),
                        Map.entry("spring.datasource.username", "sa"),
                        Map.entry("spring.datasource.password", "")))) {
            Jt808Frame response = exchangeRegistration(
                    devicePort, legacyRegistrationFrame(terminalIdentity));
            try {
                assertEquals(0x8100, response.header().messageId());
                assertEquals(0, response.body().getUnsignedByte(2), api::diagnostic);
            } finally {
                response.body().release();
            }
        }
    }

    @Test
    void disabledRuntimeStartsWithoutApiSecretsAndNeverListensOnTheDevicePort() throws Exception {
        int devicePort = freeLoopbackPort();
        try (ConfigurableApplicationContext context = start(Map.of(
                "jt.gateway.tcp.enabled", "false",
                "jt.gateway.tcp.bind-address", "127.0.0.1",
                "jt.gateway.tcp.port", Integer.toString(devicePort),
                "spring.datasource.url", "jdbc:h2:mem:disabled_runtime;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.username", "sa",
                "spring.datasource.password", ""))) {
            assertTrue(context.getBeansOfType(JtGatewayServer.class).isEmpty());
            assertConnectionRefused(devicePort);
        }
    }

    @Test
    void maintenanceRuntimeBlocksNonTargetAndExposesOnlyAggregateHealth() throws Exception {
        int devicePort = freeLoopbackPort();
        String allowedDigest = "610b4a9a2a4b6caec767df0009f8fa7d29e42de5f9d2ff56dfbfb9fd237f3169";
        try (OperationsApiStub api = new OperationsApiStub();
                ConfigurableApplicationContext context = start(Map.ofEntries(
                        Map.entry("jt.gateway.tcp.enabled", "true"),
                        Map.entry("jt.gateway.tcp.bind-address", "127.0.0.1"),
                        Map.entry("jt.gateway.tcp.port", Integer.toString(devicePort)),
                        Map.entry("jt.gateway.operations-api.base-url", api.baseUrl()),
                        Map.entry("jt.gateway.service-credential.version", "3"),
                        Map.entry("jt.gateway.service-credential.plaintext", SERVICE_CREDENTIAL),
                        Map.entry("jt.gateway.instance", "runtime-maintenance-test"),
                        Map.entry("jt.gateway.registration-maintenance.enabled", "true"),
                        Map.entry("jt.gateway.registration-maintenance.allowed-identity-sha256", allowedDigest),
                        Map.entry("jt.gateway.registration-maintenance.expires-at", "2100-01-01T00:00:00Z"),
                        Map.entry("jt.gateway.registration-maintenance.audit-interval-seconds", "60"),
                        Map.entry("jt.gateway.dispatch.fixed-delay", "25"),
                        Map.entry("spring.datasource.url",
                                "jdbc:h2:mem:runtime_maintenance;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"),
                        Map.entry("spring.datasource.username", "sa"),
                        Map.entry("spring.datasource.password", "")))) {
            int managementPort = ((WebServerApplicationContext) context).getWebServer().getPort();
            assertEquals(1, context.getBeansOfType(RegistrationMaintenancePolicy.class).size());

            SimulatedTerminal blocked = new SimulatedTerminal(
                    "000000000002", ProtocolVersion.JT808_2013, "SIM-BLOCKED");
            SimulatedTerminal allowed = new SimulatedTerminal(
                    TERMINAL_IDENTITY, ProtocolVersion.JT808_2013, "SIM-PLATE");
            try {
                blocked.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), devicePort));
                blocked.sendRegistration();
                SimulatedTerminal.ReplyRecord blockedReply = blocked.awaitReply(Duration.ofSeconds(3));
                assertNotNull(blockedReply);
                assertEquals(0x8100, blockedReply.messageId());
                assertEquals(1, blockedReply.result());

                HttpResponse<String> blockedHealth = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(
                                        "http://127.0.0.1:" + managementPort + "/actuator/health"))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertTrue(blockedHealth.body().contains("\"registrationMaintenanceEnabled\":true"));
                assertTrue(blockedHealth.body().contains("\"registrationMaintenanceBlockedAttemptCount\":1"));
                assertTrue(blockedHealth.body().contains("\"registrationMaintenanceBlockedIdentityCount\":1"));
                assertFalse(blockedHealth.body().contains(allowedDigest));
                assertFalse(blockedHealth.body().contains("000000000002"));

                allowed.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), devicePort));
                allowed.sendRegistration();
                SimulatedTerminal.ReplyRecord allowedReply = allowed.awaitReply(Duration.ofSeconds(3));
                assertNotNull(allowedReply);
                assertEquals(0, allowedReply.result(), api::diagnostic);

                RegistrationMaintenancePolicy.Snapshot snapshot =
                        context.getBean(RegistrationMaintenancePolicy.class).snapshot();
                assertEquals(1, snapshot.allowedAttemptCount());
                assertEquals(1, snapshot.blockedAttemptCount());
                assertEquals(1, snapshot.blockedIdentityCount());
            } finally {
                blocked.close();
                allowed.close();
            }
        }
    }

    @Test
    void privateCapturePersistsOnlyAllowedVehicleIdentifierAndHealthRemainsSanitized() throws Exception {
        int devicePort = freeLoopbackPort();
        String allowedDigest = "610b4a9a2a4b6caec767df0009f8fa7d29e42de5f9d2ff56dfbfb9fd237f3169";
        String identityDigest =
                "27d6fbbe5c7d230a83b72e525751d4e0a33477eeb7817a4af485825a133e1b1e";
        Path privateRoot = temporaryDirectory.resolve("private-diagnostics");
        Path capturePath = privateRoot.resolve("terminal-01-vehicle-identifier.bin");
        try (OperationsApiStub api = new OperationsApiStub();
                ConfigurableApplicationContext context = start(Map.ofEntries(
                        Map.entry("jt.gateway.tcp.enabled", "true"),
                        Map.entry("jt.gateway.tcp.bind-address", "127.0.0.1"),
                        Map.entry("jt.gateway.tcp.port", Integer.toString(devicePort)),
                        Map.entry("jt.gateway.operations-api.base-url", api.baseUrl()),
                        Map.entry("jt.gateway.service-credential.version", "3"),
                        Map.entry("jt.gateway.service-credential.plaintext", SERVICE_CREDENTIAL),
                        Map.entry("jt.gateway.instance", "runtime-private-capture-test"),
                        Map.entry("jt.gateway.registration-maintenance.enabled", "true"),
                        Map.entry("jt.gateway.registration-maintenance.allowed-identity-sha256",
                                allowedDigest),
                        Map.entry("jt.gateway.registration-maintenance.known-identity-fingerprints",
                                "terminal-01:" + identityDigest + ":JT808_2013"),
                        Map.entry("jt.gateway.registration-maintenance.expires-at",
                                "2100-01-01T00:00:00Z"),
                        Map.entry("jt.gateway.private-vehicle-identifier-capture.enabled", "true"),
                        Map.entry("jt.gateway.private-vehicle-identifier-capture.root",
                                privateRoot.toString()),
                        Map.entry("jt.gateway.private-vehicle-identifier-capture.path",
                                capturePath.toString()),
                        Map.entry("jt.gateway.dispatch.fixed-delay", "25"),
                        Map.entry("spring.datasource.url",
                                "jdbc:h2:mem:runtime_private_capture;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"),
                        Map.entry("spring.datasource.username", "sa"),
                        Map.entry("spring.datasource.password", "")))) {
            int managementPort = ((WebServerApplicationContext) context).getWebServer().getPort();
            SimulatedTerminal blocked = new SimulatedTerminal(
                    "000000000002", ProtocolVersion.JT808_2013, "BLOCKED-PLATE");
            SimulatedTerminal allowed = new SimulatedTerminal(
                    TERMINAL_IDENTITY, ProtocolVersion.JT808_2013, "PRIVATE-PLATE");
            try {
                blocked.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), devicePort));
                blocked.sendRegistration();
                assertEquals(1, blocked.awaitReply(Duration.ofSeconds(3)).result());
                assertFalse(Files.exists(capturePath));

                allowed.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), devicePort));
                allowed.sendRegistration();
                assertEquals(0, allowed.awaitReply(Duration.ofSeconds(3)).result(), api::diagnostic);
                assertEquals("PRIVATE-PLATE", Files.readString(capturePath, StandardCharsets.UTF_8));

                HttpResponse<String> response = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(
                                        "http://127.0.0.1:" + managementPort + "/actuator/health"))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                JsonNode health = new ObjectMapper().readTree(response.body());
                assertTrue(health.findValue("privateVehicleIdentifierCaptureEnabled").asBoolean());
                assertTrue(health.findValue("privateVehicleIdentifierCaptured").asBoolean());
                assertEquals("terminal-01",
                        health.findValue("privateVehicleIdentifierCaptureAlias").asText());
                assertEquals(13,
                        health.findValue("privateVehicleIdentifierCharacterCount").asInt());
                assertEquals(13,
                        health.findValue("privateVehicleIdentifierGbkByteCount").asInt());
                assertFalse(response.body().contains("PRIVATE-PLATE"));
                assertFalse(response.body().contains("BLOCKED-PLATE"));

                PrivateVehicleIdentifierCapture.Snapshot snapshot =
                        context.getBean(PrivateVehicleIdentifierCapture.class).snapshot();
                assertTrue(snapshot.captured());
                assertEquals("terminal-01", snapshot.alias());
            } finally {
                blocked.close();
                allowed.close();
            }
        }
    }

    @Test
    void privateCaptureFailsClosedWhenMaintenanceIsDisabled() {
        Path privateRoot = temporaryDirectory.resolve("private-capture-without-maintenance");
        ConfigurableApplicationContext context = null;
        try {
            context = start(Map.ofEntries(
                    Map.entry("jt.gateway.tcp.enabled", "false"),
                    Map.entry("jt.gateway.private-vehicle-identifier-capture.enabled", "true"),
                    Map.entry("jt.gateway.private-vehicle-identifier-capture.root",
                            privateRoot.toString()),
                    Map.entry("jt.gateway.private-vehicle-identifier-capture.path",
                            privateRoot.resolve("capture.bin").toString()),
                    Map.entry("spring.datasource.url",
                            "jdbc:h2:mem:private_capture_without_maintenance;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"),
                    Map.entry("spring.datasource.username", "sa"),
                    Map.entry("spring.datasource.password", "")));
            fail("private capture must not start without the maintenance allowlist");
        } catch (RuntimeException expected) {
            assertNotNull(expected.getMessage());
        } finally {
            if (context != null) {
                context.close();
            }
        }
    }

    @Test
    void maintenanceRuntimeExposesOnlySafeFingerprintMatchDiagnostics() throws Exception {
        int devicePort = freeLoopbackPort();
        String allowedCompositeDigest =
                "a3105682146ab48af676793092f295217c775bc9d087b53344182760a83e193d";
        String terminal01IdentityDigest =
                "27d6fbbe5c7d230a83b72e525751d4e0a33477eeb7817a4af485825a133e1b1e";
        String terminal02IdentityDigest =
                "2a749ecbe7c135a7e8ad68945bd410ae249a9e1d9b05ccc7aa19ea936b417961";
        String knownIdentityFingerprints = String.join(";",
                "terminal-01:" + terminal01IdentityDigest + ":JT808_2019",
                "terminal-02:" + terminal02IdentityDigest + ":JT808_2013");
        try (OperationsApiStub api = new OperationsApiStub();
                ConfigurableApplicationContext context = start(Map.ofEntries(
                        Map.entry("jt.gateway.tcp.enabled", "true"),
                        Map.entry("jt.gateway.tcp.bind-address", "127.0.0.1"),
                        Map.entry("jt.gateway.tcp.port", Integer.toString(devicePort)),
                        Map.entry("jt.gateway.operations-api.base-url", api.baseUrl()),
                        Map.entry("jt.gateway.service-credential.version", "3"),
                        Map.entry("jt.gateway.service-credential.plaintext", SERVICE_CREDENTIAL),
                        Map.entry("jt.gateway.instance", "runtime-fingerprint-diagnostic-test"),
                        Map.entry("jt.gateway.registration-maintenance.enabled", "true"),
                        Map.entry("jt.gateway.registration-maintenance.allowed-identity-sha256",
                                allowedCompositeDigest),
                        Map.entry("jt.gateway.registration-maintenance.known-identity-fingerprints",
                                knownIdentityFingerprints),
                        Map.entry("jt.gateway.registration-maintenance.expires-at",
                                "2100-01-01T00:00:00Z"),
                        Map.entry("jt.gateway.registration-maintenance.audit-interval-seconds", "60"),
                        Map.entry("jt.gateway.dispatch.fixed-delay", "25"),
                        Map.entry("spring.datasource.url",
                                "jdbc:h2:mem:runtime_fingerprint_diagnostic;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"),
                        Map.entry("spring.datasource.username", "sa"),
                        Map.entry("spring.datasource.password", "")))) {
            int managementPort = ((WebServerApplicationContext) context).getWebServer().getPort();
            SimulatedTerminal terminal01WrongProtocol = new SimulatedTerminal(
                    TERMINAL_IDENTITY, ProtocolVersion.JT808_2013, "SIM-TERMINAL-01");
            SimulatedTerminal terminal02 = new SimulatedTerminal(
                    "000000000002", ProtocolVersion.JT808_2013, "SIM-TERMINAL-02");
            try {
                terminal01WrongProtocol.connect(
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), devicePort));
                terminal01WrongProtocol.sendRegistration();
                SimulatedTerminal.ReplyRecord firstReply =
                        terminal01WrongProtocol.awaitReply(Duration.ofSeconds(3));
                assertNotNull(firstReply);
                assertEquals(1, firstReply.result());

                terminal02.connect(
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), devicePort));
                terminal02.sendRegistration();
                SimulatedTerminal.ReplyRecord secondReply =
                        terminal02.awaitReply(Duration.ofSeconds(3));
                assertNotNull(secondReply);
                assertEquals(1, secondReply.result());

                HttpResponse<String> response = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(
                                        "http://127.0.0.1:" + managementPort + "/actuator/health"))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                JsonNode observations = new ObjectMapper().readTree(response.body())
                        .findValue("registrationMaintenanceFingerprintObservations");
                assertNotNull(observations,
                        "RED: health must expose safe fingerprint observations");
                assertTrue(observations.isArray());
                assertEquals(2, observations.size());

                JsonNode terminal01 = fingerprintObservation(observations, "terminal-01");
                assertTrue(terminal01.path("identityMatch").asBoolean());
                assertFalse(terminal01.path("protocolMatch").asBoolean());
                assertEquals(1, terminal01.path("attemptCount").asLong());

                JsonNode terminal02Observation =
                        fingerprintObservation(observations, "terminal-02");
                assertTrue(terminal02Observation.path("identityMatch").asBoolean());
                assertTrue(terminal02Observation.path("protocolMatch").asBoolean());
                assertEquals(1, terminal02Observation.path("attemptCount").asLong());

                assertFalse(response.body().contains(TERMINAL_IDENTITY));
                assertFalse(response.body().contains("000000000002"));
                assertFalse(response.body().contains(allowedCompositeDigest));
                assertFalse(response.body().contains(terminal01IdentityDigest));
                assertFalse(response.body().contains(terminal02IdentityDigest));
            } finally {
                terminal01WrongProtocol.close();
                terminal02.close();
            }
        }
    }

    @Test
    void enabledRuntimeFailsClosedWhenServiceSecurityConfigurationIsMissing() throws Exception {
        int devicePort = freeLoopbackPort();
        RuntimeException failure = assertThrows(RuntimeException.class, () -> start(Map.of(
                "jt.gateway.tcp.enabled", "true",
                "jt.gateway.tcp.bind-address", "127.0.0.1",
                "jt.gateway.tcp.port", Integer.toString(devicePort),
                "spring.datasource.url", "jdbc:h2:mem:missing_security;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.username", "sa",
                "spring.datasource.password", "")));
        assertNotNull(failure.getMessage());
        assertConnectionRefused(devicePort);
    }

    @Test
    void boundsIngressTimeoutKeepsRegistrationIsolatedAndRecoversTheDispatcher() throws Exception {
        int devicePort = freeLoopbackPort();
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try (OperationsApiStub api = new OperationsApiStub();
                ConfigurableApplicationContext context = start(Map.ofEntries(
                        Map.entry("jt.gateway.tcp.enabled", "true"),
                        Map.entry("jt.gateway.tcp.bind-address", "127.0.0.1"),
                        Map.entry("jt.gateway.tcp.port", Integer.toString(devicePort)),
                        Map.entry("jt.gateway.operations-api.base-url", api.baseUrl()),
                        Map.entry("jt.gateway.service-credential.version", "3"),
                        Map.entry("jt.gateway.service-credential.plaintext", SERVICE_CREDENTIAL),
                        Map.entry("jt.gateway.instance", "runtime-timeout-test"),
                        // Keep the timeout bounded below the 2s assertion window without treating
                        // loopback/JIT scheduling latency as a registration-isolation failure.
                        Map.entry("jt.gateway.http.connect-timeout-ms", "500"),
                        Map.entry("jt.gateway.http.read-timeout-ms", "500"),
                        Map.entry("jt.gateway.dispatch.initial-backoff-ms", "1"),
                        Map.entry("jt.gateway.dispatch.initial-delay", "600000"),
                        Map.entry("spring.datasource.url",
                                "jdbc:h2:mem:runtime_timeout;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"),
                        Map.entry("spring.datasource.username", "sa"),
                        Map.entry("spring.datasource.password", "")))) {
            GatewayIngressEnvelope envelope = new GatewayIngressEnvelope(
                    1, UUID.fromString("66666666-6666-6666-6666-666666666666"),
                    IngressKind.ALARM, java.time.Instant.now(), "{\"alarm\":\"synthetic\"}");
            context.getBean(GatewayIngressBuffer.class).append(envelope);
            api.blockNextIngress();
            Future<GatewayOutboxDispatcher.DispatchReport> dispatch = callers.submit(
                    context.getBean(GatewayOutboxDispatcher.class)::dispatchOnce);
            assertTrue(api.awaitBlockedIngress(Duration.ofSeconds(5)));

            Future<RegistrationDecision> registration = callers.submit(() ->
                    context.getBean(TerminalRegistryPort.class).verifyRegistration(
                            new TerminalRegistrationIdentity(
                                    ProtocolVersion.JT808_2013, TERMINAL_IDENTITY,
                                    "SIMMF", "SIM-MODEL", "SIM0001", "SIM-PLATE")));
            assertTrue(registration.get(2, TimeUnit.SECONDS).approved());

            GatewayOutboxDispatcher.DispatchReport timedOut = dispatch.get(2, TimeUnit.SECONDS);
            assertEquals(1, timedOut.retried());
            api.releaseBlockedIngress();
            GatewayOutboxRepository repository = context.getBean(GatewayOutboxRepository.class);
            await(() -> repository.find(envelope.idempotencyKey()).orElseThrow()
                            .nextAttemptAt().compareTo(java.time.Instant.now()) <= 0,
                    Duration.ofSeconds(2));

            GatewayOutboxDispatcher.DispatchReport recovered =
                    context.getBean(GatewayOutboxDispatcher.class).dispatchOnce();
            assertEquals(1, recovered.delivered());
            assertEquals(0, repository.pendingCount());
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void blockedHealthProbeDoesNotDelayScheduledOutboxDelivery() throws Exception {
        int devicePort = freeLoopbackPort();
        try (OperationsApiStub api = new OperationsApiStub()) {
            api.blockNextProbe();
            ConfigurableApplicationContext context = start(Map.ofEntries(
                    Map.entry("jt.gateway.tcp.enabled", "true"),
                    Map.entry("jt.gateway.tcp.bind-address", "127.0.0.1"),
                    Map.entry("jt.gateway.tcp.port", Integer.toString(devicePort)),
                    Map.entry("jt.gateway.operations-api.base-url", api.baseUrl()),
                    Map.entry("jt.gateway.service-credential.version", "3"),
                    Map.entry("jt.gateway.service-credential.plaintext", SERVICE_CREDENTIAL),
                    Map.entry("jt.gateway.instance", "runtime-scheduler-test"),
                    Map.entry("jt.gateway.http.read-timeout-ms", "5000"),
                    Map.entry("jt.gateway.health.api-probe-initial-delay-ms", "0"),
                    Map.entry("jt.gateway.health.api-probe-fixed-delay-ms", "600000"),
                    Map.entry("jt.gateway.dispatch.initial-delay", "0"),
                    Map.entry("jt.gateway.dispatch.fixed-delay", "25"),
                    Map.entry("spring.datasource.url",
                            "jdbc:h2:mem:scheduler_isolation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"),
                    Map.entry("spring.datasource.username", "sa"),
                    Map.entry("spring.datasource.password", "")));
            try {
                assertTrue(api.awaitBlockedProbe(Duration.ofSeconds(2)));
                GatewayIngressEnvelope envelope = new GatewayIngressEnvelope(
                        1, UUID.fromString("77777777-7777-7777-7777-777777777777"),
                        IngressKind.LOCATION, java.time.Instant.now(),
                        "{\"latitude\":35.0,\"longitude\":105.0}");
                assertEquals(GatewayIngressBuffer.WriteResult.STORED,
                        context.getBean(GatewayIngressBuffer.class).append(envelope));

                assertTrue(api.awaitIngress(Duration.ofSeconds(1)),
                        "a blocked probe must not occupy the serial dispatcher scheduler");
                assertEquals(0, context.getBean(GatewayOutboxRepository.class).pendingCount());
            } finally {
                api.releaseBlockedProbe();
                context.close();
            }
        }
    }

    private ConfigurableApplicationContext start(Map<String, String> taskProperties) {
        List<String> arguments = new ArrayList<>();
        arguments.add("--server.port=0");
        arguments.add("--server.address=127.0.0.1");
        arguments.add("--management.endpoint.health.show-details=always");
        arguments.add("--spring.main.banner-mode=off");
        taskProperties.forEach((name, value) -> arguments.add("--" + name + "=" + value));
        return new SpringApplicationBuilder(JtGatewayApplication.class)
                .run(arguments.toArray(String[]::new));
    }

    private int registrationReplyBodyHexLength(
            String compatibilityModels, String databaseName) throws Exception {
        int devicePort = freeLoopbackPort();
        try (OperationsApiStub api = new OperationsApiStub();
                ConfigurableApplicationContext context = start(Map.ofEntries(
                        Map.entry("jt.gateway.tcp.enabled", "true"),
                        Map.entry("jt.gateway.tcp.bind-address", "127.0.0.1"),
                        Map.entry("jt.gateway.tcp.port", Integer.toString(devicePort)),
                        Map.entry("jt.gateway.operations-api.base-url", api.baseUrl()),
                        Map.entry("jt.gateway.service-credential.version", "3"),
                        Map.entry("jt.gateway.service-credential.plaintext", SERVICE_CREDENTIAL),
                        Map.entry("jt.gateway.instance", "runtime-token-profile-test"),
                        Map.entry("jt.gateway.registration-authentication.compatibility-models",
                                compatibilityModels),
                        Map.entry("jt.gateway.dispatch.initial-delay", "600000"),
                        Map.entry("spring.datasource.url",
                                "jdbc:h2:mem:" + databaseName
                                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1"),
                        Map.entry("spring.datasource.username", "sa"),
                        Map.entry("spring.datasource.password", "")))) {
            try (SimulatedTerminal terminal = new SimulatedTerminal(
                    TERMINAL_IDENTITY, ProtocolVersion.JT808_2013, "SIM-PLATE")) {
                terminal.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), devicePort));
                terminal.sendRegistration();
                SimulatedTerminal.ReplyRecord registration =
                        terminal.awaitReply(Duration.ofSeconds(3));
                assertNotNull(registration);
                assertEquals(0x8100, registration.messageId());
                assertEquals(0, registration.result(), api::diagnostic);

                terminal.sendAuthentication();
                SimulatedTerminal.ReplyRecord authentication =
                        terminal.awaitReply(Duration.ofSeconds(3));
                assertNotNull(authentication);
                assertEquals(0, authentication.result());
                return registration.bodyHex().length();
            }
        }
    }

    private static void authenticate(SimulatedTerminal terminal, int devicePort) {
        terminal.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), devicePort));
        terminal.sendRegistration();
        SimulatedTerminal.ReplyRecord registration = terminal.awaitReply(Duration.ofSeconds(3));
        assertNotNull(registration);
        assertEquals(0x8100, registration.messageId());
        assertEquals(0, registration.result());
        terminal.sendAuthentication();
        SimulatedTerminal.ReplyRecord authentication = terminal.awaitReply(Duration.ofSeconds(3));
        assertNotNull(authentication);
        assertEquals(0x8001, authentication.messageId());
        assertEquals(0, authentication.result());
    }

    private static String fileDatabaseUrl(Path database) {
        return "jdbc:h2:file:" + database.toAbsolutePath().toString().replace('\\', '/')
                + ";MODE=PostgreSQL;DB_CLOSE_ON_EXIT=FALSE";
    }

    private static Jt808Frame legacyRegistrationFrame(String terminalIdentity) {
        ByteBuf body = Unpooled.buffer();
        body.writeShort(62).writeShort(621);
        writeFixedAscii(body, "MFG01", 5);
        writeFixedAscii(body, "SIM-MODEL", 20);
        writeFixedAscii(body, "TERM001", 7);
        body.writeByte(1);
        body.writeCharSequence("SIM-PLATE", StandardCharsets.US_ASCII);
        Jt808MessageHeader header = new Jt808MessageHeader(
                0x0100, body.readableBytes() | 0x4000, body.readableBytes(), 0, false,
                ProtocolVersion.JT808_2019, 1, terminalIdentity, 1, null, null);
        return new Jt808Frame(header, body, (byte) 0);
    }

    private static Jt808Frame exchangeRegistration(int port, Jt808Frame request) throws Exception {
        byte[] encoded;
        EmbeddedChannel encoder = new EmbeddedChannel(new Jt808FrameEncoder());
        try {
            assertTrue(encoder.writeOutbound(request));
            ByteBuf bytes = encoder.readOutbound();
            try {
                encoded = new byte[bytes.readableBytes()];
                bytes.readBytes(encoded);
            } finally {
                bytes.release();
            }
        } finally {
            encoder.finishAndReleaseAll();
        }

        ByteArrayOutputStream responseBytes = new ByteArrayOutputStream();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1000);
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(encoded);
            socket.getOutputStream().flush();
            boolean started = false;
            while (true) {
                int value = socket.getInputStream().read();
                if (value < 0) {
                    throw new IOException("registration connection closed without a reply");
                }
                if (value == 0x7e) {
                    if (started) {
                        responseBytes.write(value);
                        break;
                    }
                    started = true;
                }
                if (started) {
                    responseBytes.write(value);
                }
            }
        }

        EmbeddedChannel decoder = new EmbeddedChannel(new Jt808FrameDecoder());
        try {
            assertTrue(decoder.writeInbound(Unpooled.wrappedBuffer(responseBytes.toByteArray())));
            Jt808Frame response = decoder.readInbound();
            assertNotNull(response);
            return response;
        } finally {
            decoder.finishAndReleaseAll();
        }
    }

    private static void writeFixedAscii(ByteBuf target, String value, int length) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        target.writeBytes(bytes);
        target.writeZero(length - bytes.length);
    }

    private static String sha256Hex(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static int freeLoopbackPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static void assertConnectionRefused(int port) {
        assertThrows(IOException.class, () -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500);
            }
        });
    }

    private static JsonNode fingerprintObservation(JsonNode observations, String alias) {
        for (JsonNode observation : observations) {
            if (alias.equals(observation.path("alias").asText())) {
                return observation;
            }
        }
        throw new AssertionError("missing safe fingerprint observation for " + alias);
    }

    private static HttpResponse<String> health(int managementPort, String group) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                                "http://127.0.0.1:" + managementPort + "/actuator/health/" + group))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), "condition was not met within " + timeout);
    }

    private static final class OperationsApiStub implements AutoCloseable {
        private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        private final HttpServer server;
        private final List<Boolean> authenticated = new CopyOnWriteArrayList<>();
        private final List<Boolean> safeProbes = new CopyOnWriteArrayList<>();
        private final List<String> requestPaths = new CopyOnWriteArrayList<>();
        private final List<JsonNode> ingress = new CopyOnWriteArrayList<>();
        private final ExecutorService httpWorkers = Executors.newCachedThreadPool();
        private final AtomicBoolean blockIngress = new AtomicBoolean();
        private final AtomicBoolean blockProbe = new AtomicBoolean();
        private final CountDownLatch ingressBlocked = new CountDownLatch(1);
        private final CountDownLatch releaseIngress = new CountDownLatch(1);
        private final CountDownLatch probeBlocked = new CountDownLatch(1);
        private final CountDownLatch releaseProbe = new CountDownLatch(1);
        private final CountDownLatch ingressReceived = new CountDownLatch(1);
        private final Map<UUID, String> registeredTokenDigests = new ConcurrentHashMap<>();
        private final Map<UUID, DeviceContext> verifiedContexts = new ConcurrentHashMap<>();

        private OperationsApiStub() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/internal/jt-gateway/registrations/verify", exchange -> {
                JsonNode request = read(exchange);
                DeviceContext context = contextFor(
                        request.required("terminalPhone").asText(),
                        request.required("protocolVersion").asText());
                verifiedContexts.put(context.terminalId(), context);
                respond(exchange, 200, approvedRegistrationResponse(context));
            });
            server.createContext("/internal/jt-gateway/registrations/", exchange -> {
                JsonNode request = read(exchange);
                UUID terminalId = terminalIdFromCompletionPath(exchange.getRequestURI().getPath());
                registeredTokenDigests.put(terminalId, request.required("tokenSha256").asText());
                respond(exchange, 200, "{\"data\":{\"completed\":true}}");
            });
            server.createContext("/internal/jt-gateway/authentications/verify", exchange -> {
                JsonNode request = read(exchange);
                UUID terminalId = UUID.fromString(request.required("terminalId").asText());
                DeviceContext context = verifiedContexts.get(terminalId);
                boolean approved = context != null
                        && registeredTokenDigests.containsKey(terminalId)
                        && registeredTokenDigests.get(terminalId).equals(request.required("tokenSha256").asText())
                        && request.required("tokenVersion").asInt() == 3;
                respond(exchange, 200, authenticationResponse(
                        approved, context,
                        request.required("connectionId").asText(),
                        request.required("gatewayInstance").asText()));
            });
            server.createContext("/internal/jt-gateway/session-leases/renew", exchange -> {
                JsonNode request = read(exchange);
                java.time.Instant renewedAt = java.time.Instant.now();
                respond(exchange, 200, "{\"data\":{" +
                        "\"owner\":" + request.toString() + "," +
                        "\"authenticatedAt\":\"" + renewedAt.minusSeconds(30) + "\"," +
                        "\"lastValidMessageAt\":\"" + renewedAt + "\"," +
                        "\"expiresAt\":\"" + renewedAt.plusSeconds(180) + "\"}}");
            });
            server.createContext("/internal/jt-gateway/session-leases/release", exchange -> {
                read(exchange);
                respond(exchange, 200, "{\"data\":{\"status\":\"RELEASED\"}}");
            });
            server.createContext("/internal/jt-gateway/audit-events", exchange -> {
                JsonNode request = read(exchange);
                String idempotencyKey = request.required("idempotencyKey").asText();
                respond(exchange, 200, "{\"data\":{\"idempotencyKey\":\""
                        + idempotencyKey + "\",\"status\":\"ACCEPTED\"}}");
            });
            server.createContext("/actuator/health", exchange -> {
                if (blockProbe.compareAndSet(true, false)) {
                    probeBlocked.countDown();
                    try {
                        releaseProbe.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        exchange.close();
                        return;
                    }
                }
                respond(exchange, 200, "{\"status\":\"UP\"}");
            });
            server.createContext("/internal/jt-gateway/ingress", exchange -> {
                if (blockIngress.compareAndSet(true, false)) {
                    ingressBlocked.countDown();
                    try {
                        releaseIngress.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        exchange.close();
                        return;
                    }
                }
                JsonNode batch = read(exchange);
                batch.forEach(ingress::add);
                ingressReceived.countDown();
                com.fasterxml.jackson.databind.node.ArrayNode results = mapper.createArrayNode();
                batch.forEach(envelope -> results.addObject()
                        .put("idempotencyKey", envelope.required("idempotencyKey").asText())
                        .put("status", "ACCEPTED")
                        .putArray("reasonCodes"));
                com.fasterxml.jackson.databind.node.ObjectNode response = mapper.createObjectNode();
                response.set("data", results);
                respond(exchange, 200, response.toString());
            });
            server.setExecutor(httpWorkers);
            server.start();
        }

        private static String approvedRegistrationResponse(DeviceContext context) {
            return """
                    {"data":{"approved":true,"contractVersion":2,
                     "terminalId":"%s",
                     "onboardSystemId":"66666666-6666-6666-6666-666666666666",
                     "vehicleId":"55555555-5555-5555-5555-555555555555",
                     "onboardConfigurationVersion":4,
                     "roles":%s,
                     "sourceCoordinateSystem":"WGS84",
                     "protocolProfile":{"transportProfile":"%s","businessProfile":"NONE",
                      "safetyProfile":"JSATL12_2017","mediaProfile":"%s",
                      "enabledActiveSafetyModules":["ADAS"],
                      "activePositionIntervalSeconds":30,"idlePositionIntervalSeconds":60},
                     "activeSafetyStandard":"T/JSATL12-2017",
                     "activeSafetyModules":["ADAS"],"tokenVersion":3,
                     "context":{"contractVersion":2,"terminalId":"%s",
                      "onboardSystemId":"66666666-6666-6666-6666-666666666666",
                      "vehicleId":"55555555-5555-5555-5555-555555555555",
                      "onboardConfigurationVersion":4,
                      "roles":%s,
                      "sourceCoordinateSystem":"WGS84",
                      "protocolProfile":{"transportProfile":"%s","businessProfile":"NONE",
                       "safetyProfile":"JSATL12_2017","mediaProfile":"%s",
                       "enabledActiveSafetyModules":["ADAS"],
                       "activePositionIntervalSeconds":30,"idlePositionIntervalSeconds":60},
                      "activeSafetyStandard":"T/JSATL12-2017",
                      "activeSafetyModules":["ADAS"],"tokenVersion":3},
                     "warnings":[],"reasonCode":null}}
                    """.formatted(context.terminalId(), context.rolesJson(),
                    context.transportProfile(), context.mediaProfile(),
                    context.terminalId(), context.rolesJson(),
                    context.transportProfile(), context.mediaProfile());
        }

        private static String authenticationResponse(
                boolean approved,
                DeviceContext context,
                String connectionId,
                String gatewayInstance) {
            if (!approved) {
                return "{\"data\":{\"approved\":false,"
                        + "\"context\":null,"
                        + "\"reasonCode\":\"AUTHENTICATION_REJECTED\"}}";
            }
            java.time.Instant leaseAt = java.time.Instant.now();
            return """
                    {"data":{"approved":true,
                     "context":{"contractVersion":2,"terminalId":"%s",
                      "onboardSystemId":"66666666-6666-6666-6666-666666666666",
                      "vehicleId":"55555555-5555-5555-5555-555555555555",
                      "onboardConfigurationVersion":4,
                      "roles":%s,
                      "sourceCoordinateSystem":"WGS84",
                      "protocolProfile":{"transportProfile":"%s","businessProfile":"NONE",
                       "safetyProfile":"JSATL12_2017","mediaProfile":"%s",
                       "enabledActiveSafetyModules":["ADAS"],
                       "activePositionIntervalSeconds":30,"idlePositionIntervalSeconds":60},
                      "activeSafetyStandard":"T/JSATL12-2017",
                      "activeSafetyModules":["ADAS"],"tokenVersion":3},
                     "lease":{"owner":{"terminalId":"%s","gatewayInstance":"%s",
                      "connectionId":"%s","tokenVersion":3,"leaseGeneration":1},
                      "authenticatedAt":"%s",
                      "lastValidMessageAt":"%s",
                      "expiresAt":"%s"},
                     "reasonCode":null}}
                    """.formatted(context.terminalId(), context.rolesJson(),
                    context.transportProfile(), context.mediaProfile(),
                    context.terminalId(), gatewayInstance, connectionId,
                    leaseAt, leaseAt, leaseAt.plusSeconds(180));
        }

        private static DeviceContext contextFor(String terminalPhone, String transportProfile) {
            return RECORDER_IDENTITY.equals(terminalPhone)
                    ? new DeviceContext(RECORDER_TERMINAL_ID,
                    "[\"LOCATION_BACKUP\",\"ACTIVE_SAFETY\",\"VIDEO\"]",
                    transportProfile, "JT1078_2016")
                    : new DeviceContext(TERMINAL_ID,
                    "[\"LOCATION_PRIMARY\",\"ACTIVE_SAFETY\"]",
                    transportProfile, "NONE");
        }

        private static UUID terminalIdFromCompletionPath(String path) {
            String prefix = "/internal/jt-gateway/registrations/";
            String terminalId = path.substring(prefix.length(), path.lastIndexOf("/complete"));
            return UUID.fromString(terminalId);
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private int ingressCount() {
            return ingress.size();
        }

        private int distinctRegisteredTerminalIds() {
            return registeredTokenDigests.size();
        }

        private List<String> ingressKinds() {
            return ingress.stream().map(envelope -> envelope.required("kind").asText()).toList();
        }

        private List<String> protocolAuditReasons() {
            return ingress.stream()
                    .filter(envelope -> "PROTOCOL_AUDIT".equals(envelope.required("kind").asText()))
                    .map(envelope -> {
                        try {
                            return mapper.readTree(envelope.required("payloadJson").asText())
                                    .required("reasonCode").asText();
                        } catch (IOException malformed) {
                            throw new IllegalStateException(malformed);
                        }
                    }).toList();
        }

        private int probeCount() {
            return safeProbes.size();
        }

        private boolean probesAreUnauthenticatedHealthGets() {
            return !safeProbes.isEmpty() && safeProbes.stream().allMatch(Boolean::booleanValue);
        }

        private void blockNextIngress() {
            blockIngress.set(true);
        }

        private void blockNextProbe() {
            blockProbe.set(true);
        }

        private boolean awaitBlockedIngress(Duration timeout) throws InterruptedException {
            return ingressBlocked.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        private void releaseBlockedIngress() {
            releaseIngress.countDown();
        }

        private boolean awaitBlockedProbe(Duration timeout) throws InterruptedException {
            return probeBlocked.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        private void releaseBlockedProbe() {
            releaseProbe.countDown();
        }

        private boolean awaitIngress(Duration timeout) throws InterruptedException {
            return ingressReceived.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        private boolean allRequestsAuthenticated() {
            return !authenticated.isEmpty() && authenticated.stream().allMatch(Boolean::booleanValue);
        }

        private String diagnostic() {
            return "paths=" + requestPaths + ", authenticated=" + authenticated
                    + ", completionRecorded=" + !registeredTokenDigests.isEmpty();
        }

        private JsonNode read(HttpExchange exchange) throws IOException {
            recordAuthentication(exchange);
            return mapper.readTree(exchange.getRequestBody());
        }

        private void respond(HttpExchange exchange, int status, String json) throws IOException {
            recordAuthentication(exchange);
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }

        private void recordAuthentication(HttpExchange exchange) {
            String path = exchange.getRequestURI().getPath();
            requestPaths.add(path);
            if ("/actuator/health".equals(path)) {
                safeProbes.add("GET".equals(exchange.getRequestMethod())
                        && exchange.getRequestHeaders().getFirst("Authorization") == null
                        && exchange.getRequestHeaders().getFirst(
                                "X-Service-Credential-Version") == null);
                return;
            }
            authenticated.add(("Bearer " + SERVICE_CREDENTIAL).equals(
                            exchange.getRequestHeaders().getFirst("Authorization"))
                    && "3".equals(exchange.getRequestHeaders().getFirst(
                            "X-Service-Credential-Version")));
        }

        @Override
        public void close() {
            releaseIngress.countDown();
            releaseProbe.countDown();
            server.stop(0);
            httpWorkers.shutdownNow();
        }

        private record DeviceContext(
                UUID terminalId,
                String rolesJson,
                String transportProfile,
                String mediaProfile) { }
    }
}
