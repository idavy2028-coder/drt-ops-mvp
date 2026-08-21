package com.idavy.drtops.jtgateway.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.jt.protocol.core.Jt808CoreModule;
import com.idavy.drtops.jt.protocol.core.LocationReportCodec;
import com.idavy.drtops.jtgateway.attachment.AttachmentCommandService;
import com.idavy.drtops.jtgateway.dispatch.ProtocolModuleRegistry;
import com.idavy.drtops.jtgateway.ingress.GatewayIngressBuffer;
import com.idavy.drtops.jtgateway.ingress.GatewayOutboxDispatcher;
import com.idavy.drtops.jtgateway.ingress.GatewayOutboxRepository;
import com.idavy.drtops.jtgateway.ingress.OperationsApiClient;
import com.idavy.drtops.jtgateway.netty.JtGatewayServer;
import com.idavy.drtops.jtgateway.session.AuthenticationDecision;
import com.idavy.drtops.jtgateway.session.AuthenticationRejection;
import com.idavy.drtops.jtgateway.session.RegistrationDecision;
import com.idavy.drtops.jtgateway.session.RegistrationRejection;
import com.idavy.drtops.jtgateway.session.SessionAuditIngress;
import com.idavy.drtops.jtgateway.session.TerminalRegistryPort;
import com.idavy.drtops.jtgateway.session.TerminalRegistrationIdentity;
import com.idavy.drtops.jtgateway.session.TerminalSessionRegistry;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.RestClient;

/**
 * Shared end-to-end harness: a real Netty gateway on a random loopback port, a file-backed H2
 * outbox and a capturing HTTP stub standing at the operations-API boundary. The terminal session
 * registry instance is shared between the Netty runtime and the attachment control plane exactly
 * as the production wiring must do.
 */
final class GatewayTestRig implements AutoCloseable {
    static final String TERMINAL_IDENTITY = "000000000001";
    static final String CAPABLE_STANDARD = "T/JSATL12-2017";

    final UUID terminalId = UUID.randomUUID();
    final UUID vehicleId = UUID.randomUUID();
    final TerminalSessionRegistry sessionRegistry = new TerminalSessionRegistry();
    final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    final DataSource dataSource;
    final GatewayOutboxRepository repository;
    final GatewayIngressBuffer buffer;
    final ProtocolModuleRegistry protocolRegistry;
    final AttachmentCommandService attachmentCommands;
    final CapturingApi api;
    final OperationsApiClient apiClient;
    final GatewayOutboxDispatcher dispatcher;
    final JtGatewayServer server;
    final int port;

    GatewayTestRig(java.nio.file.Path tempDir, boolean capableTerminal) throws IOException {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:file:" + tempDir.resolve("gateway-outbox").toAbsolutePath()
                        + ";MODE=PostgreSQL",
                "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        repository = new GatewayOutboxRepository(dataSource);
        buffer = new GatewayIngressBuffer(repository, objectMapper, Clock.systemUTC());
        protocolRegistry = new ProtocolModuleRegistry(
                new Jt808CoreModule(new LocationReportCodec()),
                buffer, objectMapper, sessionRegistry, Clock.systemUTC());
        api = new CapturingApi();
        apiClient = new OperationsApiClient(
                RestClient.builder(), api.endpoint(), () -> "rig-service-credential", 1);
        dispatcher = new GatewayOutboxDispatcher(
                repository, apiClient, Clock.systemUTC(), 10,
                Duration.ofMillis(50), Duration.ofSeconds(1));
        server = new JtGatewayServer(
                new JtGatewayServer.Configuration(
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                        10, 1_000, 2, 256, 80, 40, Duration.ofSeconds(5)),
                new AllowlistRegistry(capableTerminal), sessionRegistry, protocolRegistry);
        attachmentCommands = new AttachmentCommandService(sessionRegistry);
        port = server.start();
    }

    InetSocketAddress endpoint() {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    }

    /** Pumps the outbox until nothing is pending; returns the number of dispatch rounds used. */
    int pumpUntilDrained(int maxRounds) {
        for (int round = 0; round < maxRounds; round++) {
            dispatcher.dispatchOnce();
            if (repository.pendingCount() == 0) {
                return round + 1;
            }
            sleep(25);
        }
        return maxRounds;
    }

    @Override
    public void close() {
        server.close();
        api.close();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Whitelist stub: approves the simulator identity with a fixed binding and token. */
    private final class AllowlistRegistry implements TerminalRegistryPort {
        private final boolean capable;
        private final byte[] token = "RIG-AUTH-TOKEN".getBytes(StandardCharsets.US_ASCII);

        private AllowlistRegistry(boolean capable) {
            this.capable = capable;
        }

        @Override
        public RegistrationDecision verifyRegistration(TerminalRegistrationIdentity identity) {
            if (!TERMINAL_IDENTITY.equals(identity.terminalNumber())) {
                return RegistrationDecision.rejected(RegistrationRejection.NOT_PREPROVISIONED);
            }
            return RegistrationDecision.approved(
                    terminalId, vehicleId, "WGS84",
                    capable ? CAPABLE_STANDARD : null,
                    capable ? List.of("ADAS", "DMS") : List.of(),
                    1, token, sha256(token));
        }

        @Override
        public AuthenticationDecision verifyAuthentication(
                UUID terminal, int tokenVersion, String presentedTokenSha256) {
            return sha256(token).equals(presentedTokenSha256)
                    ? AuthenticationDecision.allow()
                    : AuthenticationDecision.rejected(AuthenticationRejection.TOKEN_MISMATCH);
        }

        @Override
        public void recordSessionAudit(SessionAuditIngress event) {
            // Session audits are covered by the session package tests; the rig stays silent.
        }
    }

    /** HTTP boundary stub: captures every delivered envelope; can be switched to fail. */
    static final class CapturingApi implements AutoCloseable {
        private final HttpServer server;
        private final List<ReceivedEnvelope> received = new CopyOnWriteArrayList<>();
        private final AtomicBoolean healthy = new AtomicBoolean(true);
        private final ObjectMapper reader = new ObjectMapper().findAndRegisterModules();

        CapturingApi() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/internal/jt-gateway/ingress", exchange -> {
                if (!healthy.get()) {
                    exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(500, 0);
                    exchange.close();
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                boolean credentialPresented = exchange.getRequestHeaders()
                        .containsKey("Authorization");
                try {
                    for (JsonNode envelope : reader.readTree(body)) {
                        received.add(new ReceivedEnvelope(
                                envelope.required("kind").asText(),
                                envelope.required("payloadJson").asText(),
                                envelope.required("idempotencyKey").asText(),
                                credentialPresented));
                    }
                    byte[] ok = "{}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, ok.length);
                    exchange.getResponseBody().write(ok);
                    exchange.close();
                } catch (IOException | RuntimeException malformed) {
                    exchange.sendResponseHeaders(400, 0);
                    exchange.close();
                }
            });
            server.start();
        }

        URI endpoint() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/internal/jt-gateway/ingress");
        }

        void failDeliveries(boolean fail) {
            healthy.set(!fail);
        }

        List<ReceivedEnvelope> received() {
            return List.copyOf(received);
        }

        List<ReceivedEnvelope> receivedOfKind(String kind) {
            return received.stream().filter(envelope -> envelope.kind().equals(kind)).toList();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    record ReceivedEnvelope(String kind, String payloadJson, String idempotencyKey,
            boolean credentialPresented) {
        JsonNode payload(ObjectMapper mapper) throws IOException {
            return mapper.readTree(payloadJson);
        }
    }
}
