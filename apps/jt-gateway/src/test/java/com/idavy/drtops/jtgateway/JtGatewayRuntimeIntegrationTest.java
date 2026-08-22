package com.idavy.drtops.jtgateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.jt.protocol.codec.ProtocolVersion;
import com.idavy.drtops.jtgateway.ingress.GatewayOutboxRepository;
import com.idavy.drtops.jtgateway.ingress.GatewayIngressBuffer;
import com.idavy.drtops.jtgateway.ingress.GatewayIngressEnvelope;
import com.idavy.drtops.jtgateway.ingress.GatewayOutboxDispatcher;
import com.idavy.drtops.jtgateway.ingress.IngressKind;
import com.idavy.drtops.jtgateway.netty.JtGatewayServer;
import com.idavy.drtops.jtgateway.session.RegistrationDecision;
import com.idavy.drtops.jtgateway.session.TerminalRegistrationIdentity;
import com.idavy.drtops.jtgateway.session.TerminalRegistryPort;
import com.idavy.drtops.jtgateway.session.TerminalSessionRegistry;
import com.idavy.drtops.jtsimulator.SimulatedTerminal;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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

class JtGatewayRuntimeIntegrationTest {
    private static final String TERMINAL_IDENTITY = "000000000001";
    private static final String SERVICE_CREDENTIAL = "runtime-test-credential";
    private static final UUID TERMINAL_ID =
            UUID.fromString("44444444-4444-4444-4444-444444444444");
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
                    terminal.sendAuthentication();
                    SimulatedTerminal.ReplyRecord authentication = terminal.awaitReply(Duration.ofSeconds(3));
                    assertNotNull(authentication);
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
                        Map.entry("jt.gateway.http.connect-timeout-ms", "100"),
                        Map.entry("jt.gateway.http.read-timeout-ms", "100"),
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

    private static String fileDatabaseUrl(Path database) {
        return "jdbc:h2:file:" + database.toAbsolutePath().toString().replace('\\', '/')
                + ";MODE=PostgreSQL;DB_CLOSE_ON_EXIT=FALSE";
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
        private volatile String registeredTokenDigest;

        private OperationsApiStub() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/internal/jt-gateway/registrations/verify", exchange -> respond(
                    exchange, 200, """
                            {"data":{"approved":true,
                            "terminalId":"44444444-4444-4444-4444-444444444444",
                            "vehicleId":"55555555-5555-5555-5555-555555555555",
                            "sourceCoordinateSystem":"WGS84",
                            "activeSafetyStandard":"T/JSATL12-2017",
                            "activeSafetyModules":["ADAS"],"tokenVersion":3,"reasonCode":null}}
                            """));
            server.createContext("/internal/jt-gateway/registrations/", exchange -> {
                JsonNode request = read(exchange);
                registeredTokenDigest = request.required("tokenSha256").asText();
                respond(exchange, 200, "{\"data\":{\"completed\":true}}");
            });
            server.createContext("/internal/jt-gateway/authentications/verify", exchange -> {
                JsonNode request = read(exchange);
                boolean approved = registeredTokenDigest != null
                        && registeredTokenDigest.equals(request.required("tokenSha256").asText());
                respond(exchange, 200, "{\"data\":{\"approved\":" + approved
                        + ",\"reasonCode\":" + (approved ? "null" : "\"AUTHENTICATION_REJECTED\"") + "}}");
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

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private int ingressCount() {
            return ingress.size();
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
                    + ", completionRecorded=" + (registeredTokenDigest != null);
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
    }
}
