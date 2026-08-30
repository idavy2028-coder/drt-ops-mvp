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
import com.idavy.drtops.jtgateway.session.TerminalSessionContext;
import com.idavy.drtops.jtgateway.session.TerminalSessionRegistry;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
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
    final UUID onboardSystemId = UUID.randomUUID();
    final UUID vehicleId = UUID.randomUUID();
    final TerminalSessionRegistry sessionRegistry = new TerminalSessionRegistry();
    final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    final DataSource dataSource;
    final Connection databaseKeeper;
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
        this(tempDir, capableTerminal, (startedApi, openedKeeper) -> {});
    }

    GatewayTestRig(
            java.nio.file.Path tempDir,
            boolean capableTerminal,
            InitializationHook initializationHook) throws IOException {
        DataSource createdDataSource = new DriverManagerDataSource(
                "jdbc:h2:file:" + tempDir.resolve("gateway-outbox").toAbsolutePath()
                        + ";MODE=PostgreSQL",
                "sa", "");
        Connection openedKeeper = null;
        CapturingApi startedApi = null;
        JtGatewayServer startedServer = null;
        try {
            try {
                openedKeeper = createdDataSource.getConnection();
            } catch (SQLException unavailable) {
                throw new IOException("cannot open gateway test database keeper", unavailable);
            }
            Flyway.configure().dataSource(createdDataSource)
                    .locations("classpath:db/migration").load().migrate();
            GatewayOutboxRepository createdRepository =
                    new GatewayOutboxRepository(createdDataSource);
            GatewayIngressBuffer createdBuffer =
                    new GatewayIngressBuffer(createdRepository, objectMapper, Clock.systemUTC());
            ProtocolModuleRegistry createdProtocolRegistry = new ProtocolModuleRegistry(
                    new Jt808CoreModule(new LocationReportCodec()),
                    createdBuffer, objectMapper, sessionRegistry, Clock.systemUTC());
            startedApi = new CapturingApi();
            initializationHook.afterApiStarted(startedApi, openedKeeper);
            OperationsApiClient createdApiClient = new OperationsApiClient(
                    RestClient.builder(), startedApi.endpoint(), () -> "rig-service-credential", 1);
            GatewayOutboxDispatcher createdDispatcher = new GatewayOutboxDispatcher(
                    createdRepository, createdApiClient, Clock.systemUTC(), 10,
                    Duration.ofMillis(50), Duration.ofSeconds(1));
            startedServer = new JtGatewayServer(
                    new JtGatewayServer.Configuration(
                            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                            10, 1_000, 2, 256, 80, 40, Duration.ofSeconds(5)),
                    new AllowlistRegistry(capableTerminal), sessionRegistry, createdProtocolRegistry);
            AttachmentCommandService createdAttachmentCommands =
                    new AttachmentCommandService(sessionRegistry);
            int startedPort = startedServer.start();

            dataSource = createdDataSource;
            databaseKeeper = openedKeeper;
            repository = createdRepository;
            buffer = createdBuffer;
            protocolRegistry = createdProtocolRegistry;
            attachmentCommands = createdAttachmentCommands;
            api = startedApi;
            apiClient = createdApiClient;
            dispatcher = createdDispatcher;
            server = startedServer;
            port = startedPort;
        } catch (IOException | RuntimeException initializationFailure) {
            closeResources(
                    initializationFailure,
                    startedServer,
                    startedApi,
                    databaseKeeperResource(openedKeeper));
            throw initializationFailure;
        }
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
        Throwable failure = closeResources(
                null, server, api, databaseKeeperResource(databaseKeeper));
        if (failure != null) {
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw new IllegalStateException("failed to close gateway test resources", failure);
        }
    }

    static Throwable closeResources(
            Throwable primary,
            AutoCloseable serverResource,
            AutoCloseable apiResource,
            AutoCloseable keeperResource) {
        Throwable failure = primary;
        AutoCloseable[] resources = {serverResource, apiResource, keeperResource};
        for (AutoCloseable resource : resources) {
            if (resource == null) {
                continue;
            }
            try {
                resource.close();
            } catch (Exception closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        return failure;
    }

    private static AutoCloseable databaseKeeperResource(Connection keeper) {
        if (keeper == null) {
            return null;
        }
        return () -> {
            try {
                keeper.close();
            } catch (SQLException databaseFailure) {
                throw new IllegalStateException(
                        "failed to close gateway test database keeper", databaseFailure);
            }
        };
    }

    @FunctionalInterface
    interface InitializationHook {
        void afterApiStarted(CapturingApi api, Connection keeper) throws IOException;
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
        private final byte[] token = "RIG-AUTH-TOKEN".getBytes(StandardCharsets.US_ASCII);
        private final TerminalSessionContext context;

        private AllowlistRegistry(boolean capable) {
            this.context = new TerminalSessionContext(
                    terminalId,
                    onboardSystemId,
                    vehicleId,
                    capable
                            ? Set.of("LOCATION_PRIMARY", "ACTIVE_SAFETY", "VIDEO")
                            : Set.of("LOCATION_PRIMARY"),
                    "WGS84",
                    capable ? CAPABLE_STANDARD : null,
                    capable ? List.of("ADAS", "DMS") : List.of(),
                    1);
        }

        @Override
        public RegistrationDecision verifyRegistration(TerminalRegistrationIdentity identity) {
            if (!TERMINAL_IDENTITY.equals(identity.terminalNumber())) {
                return RegistrationDecision.rejected(RegistrationRejection.NOT_PREPROVISIONED);
            }
            return RegistrationDecision.approved(
                    context, token, sha256(token));
        }

        @Override
        public AuthenticationDecision verifyAuthentication(
                UUID terminal, int tokenVersion, String presentedTokenSha256) {
            return terminalId.equals(terminal)
                            && context.tokenVersion() == tokenVersion
                            && sha256(token).equals(presentedTokenSha256)
                    ? AuthenticationDecision.allow(context)
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
            this(address -> {});
        }

        CapturingApi(StartupHook startupHook) throws IOException {
            HttpServer createdServer = HttpServer.create();
            try {
                createdServer.createContext("/internal/jt-gateway/ingress", exchange -> {
                    if (!healthy.get()) {
                        exchange.getRequestBody().readAllBytes();
                        exchange.sendResponseHeaders(500, 0);
                        exchange.close();
                        return;
                    }
                    String body = new String(
                            exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    boolean credentialPresented = exchange.getRequestHeaders()
                            .containsKey("Authorization");
                    try {
                        JsonNode batch = reader.readTree(body);
                        com.fasterxml.jackson.databind.node.ArrayNode results = reader.createArrayNode();
                        for (JsonNode envelope : batch) {
                            received.add(new ReceivedEnvelope(
                                    envelope.required("kind").asText(),
                                    envelope.required("payloadJson").asText(),
                                    envelope.required("idempotencyKey").asText(),
                                    credentialPresented));
                            results.addObject()
                                    .put("idempotencyKey", envelope.required("idempotencyKey").asText())
                                    .put("status", "ACCEPTED")
                                    .putArray("reasonCodes");
                        }
                        com.fasterxml.jackson.databind.node.ObjectNode response = reader.createObjectNode();
                        response.set("data", results);
                        byte[] ok = response.toString().getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, ok.length);
                        exchange.getResponseBody().write(ok);
                        exchange.close();
                    } catch (IOException | RuntimeException malformed) {
                        exchange.sendResponseHeaders(400, 0);
                        exchange.close();
                    }
                });
                createdServer.bind(
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
                createdServer.start();
                startupHook.afterServerStarted(createdServer.getAddress());
                server = createdServer;
            } catch (IOException | RuntimeException startupFailure) {
                try {
                    createdServer.stop(0);
                } catch (RuntimeException closeFailure) {
                    startupFailure.addSuppressed(closeFailure);
                }
                throw startupFailure;
            }
        }

        @FunctionalInterface
        interface StartupHook {
            void afterServerStarted(InetSocketAddress address) throws IOException;
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
