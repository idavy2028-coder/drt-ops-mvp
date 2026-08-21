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
import com.idavy.drtops.jtgateway.netty.JtGatewayServer;
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
                    assertTrue(sessions.current(TERMINAL_ID).isPresent(),
                            "the production Netty server must use the application session registry");
                    terminal.sendPosition();
                    SimulatedTerminal.ReplyRecord position = terminal.awaitReply(Duration.ofSeconds(3));
                    assertNotNull(position);
                    assertEquals(0, position.result());
                    await(() -> api.ingressCount() == 1, Duration.ofSeconds(5));
                    assertEquals(0, context.getBean(GatewayOutboxRepository.class).pendingCount());
                    assertTrue(api.allRequestsAuthenticated());

                    int managementPort = ((WebServerApplicationContext) context).getWebServer().getPort();
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
        assertEquals(1, restarted.totalCount(), "the file-backed outbox must survive context shutdown");
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
        private final List<String> requestPaths = new CopyOnWriteArrayList<>();
        private final List<JsonNode> ingress = new CopyOnWriteArrayList<>();
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
            server.createContext("/internal/jt-gateway/audit-events", exchange ->
                    respond(exchange, 200, "{\"data\":{\"recorded\":true}}"));
            server.createContext("/internal/jt-gateway/ingress", exchange -> {
                JsonNode batch = read(exchange);
                batch.forEach(ingress::add);
                com.fasterxml.jackson.databind.node.ArrayNode results = mapper.createArrayNode();
                batch.forEach(envelope -> results.addObject()
                        .put("idempotencyKey", envelope.required("idempotencyKey").asText())
                        .put("status", "ACCEPTED")
                        .putArray("reasonCodes"));
                com.fasterxml.jackson.databind.node.ObjectNode response = mapper.createObjectNode();
                response.set("data", results);
                respond(exchange, 200, response.toString());
            });
            server.start();
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private int ingressCount() {
            return ingress.size();
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
            requestPaths.add(exchange.getRequestURI().getPath());
            authenticated.add(("Bearer " + SERVICE_CREDENTIAL).equals(
                            exchange.getRequestHeaders().getFirst("Authorization"))
                    && "3".equals(exchange.getRequestHeaders().getFirst(
                            "X-Service-Credential-Version")));
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
