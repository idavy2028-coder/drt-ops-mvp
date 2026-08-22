package com.idavy.drtops.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import com.idavy.drtops.domain.location.JtGatewayIngressReceiptRepository;
import com.idavy.drtops.domain.location.ServiceAreaLocationChecker;
import com.idavy.drtops.domain.location.VehicleLocationEventRepository;
import com.idavy.drtops.domain.terminal.JtGatewayAuditEventRepository;
import com.idavy.drtops.domain.terminal.JtTerminal;
import com.idavy.drtops.domain.terminal.JtTerminalRepository;
import com.idavy.drtops.domain.terminal.JtTerminalVehicleBinding;
import com.idavy.drtops.domain.terminal.JtTerminalVehicleBindingRepository;
import com.idavy.drtops.domain.terminal.TerminalManagementService;
import com.idavy.drtops.jt.protocol.codec.ProtocolVersion;
import com.idavy.drtops.jt.protocol.core.Jt808CoreModule;
import com.idavy.drtops.jt.protocol.core.LocationReportCodec;
import com.idavy.drtops.jtgateway.dispatch.ProtocolModuleRegistry;
import com.idavy.drtops.jtgateway.ingress.GatewayIngressBuffer;
import com.idavy.drtops.jtgateway.ingress.GatewayOutboxDispatcher;
import com.idavy.drtops.jtgateway.ingress.GatewayOutboxRepository;
import com.idavy.drtops.jtgateway.ingress.OperationsApiClient;
import com.idavy.drtops.jtgateway.ingress.OperationsApiStatus;
import com.idavy.drtops.jtgateway.netty.JtGatewayServer;
import com.idavy.drtops.jtgateway.session.OperationsTerminalRegistryClient;
import com.idavy.drtops.jtgateway.session.TerminalSessionRegistry;
import com.idavy.drtops.jtsimulator.SimulatedTerminal;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:jt_gateway_api_contract;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false"
})
@Import(JtGatewayApiContractEndToEndTest.TestBeans.class)
class JtGatewayApiContractEndToEndTest {
    private static final int ONE_MIB = 1_048_576;
    private static final int MAX_INGRESS_STRING_LENGTH = 262_144;
    private static final String SERVICE_CREDENTIAL = "synthetic-jt-api-contract-credential";
    private static final String TERMINAL_IDENTITY = "000000000001";
    private static final String TERMINAL_CODE = "SIM0001";
    private static final String VEHICLE_PLATE = "SIM-PLATE";
    private static final UUID TERMINAL_ID = UUID.fromString("81000000-0000-0000-0000-000000000001");
    private static final UUID VEHICLE_ID = UUID.fromString("82000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR_ID = UUID.fromString("83000000-0000-0000-0000-000000000001");
    private static final Instant GATEWAY_TIME = Instant.parse("2026-01-15T02:00:00Z");

    @LocalServerPort
    int apiPort;

    @TempDir
    Path tempDir;

    @Autowired ObjectMapper objectMapper;
    @Autowired VehicleRepository vehicles;
    @Autowired JtTerminalRepository terminals;
    @Autowired JtTerminalVehicleBindingRepository bindings;
    @Autowired VehicleLocationEventRepository locations;
    @Autowired JtGatewayIngressReceiptRepository receipts;
    @Autowired JtGatewayAuditEventRepository audits;
    @Autowired TerminalManagementService terminalManagement;
    @Autowired JdbcTemplate apiJdbc;

    @DynamicPropertySource
    static void gatewayCredential(DynamicPropertyRegistry registry) {
        registry.add("jt.gateway.service-credentials.current.version", () -> "1");
        registry.add("jt.gateway.service-credentials.current.hash",
                () -> sha256(SERVICE_CREDENTIAL.getBytes(StandardCharsets.UTF_8)));
    }

    @BeforeEach
    void setUpSyntheticTerminal() {
        apiJdbc.update("delete from vehicle_alarm_outbox");
        apiJdbc.update("delete from vehicle_alarms");
        receipts.deleteAll();
        audits.deleteAll();
        locations.deleteAll();
        bindings.deleteAll();
        terminals.deleteAll();
        vehicles.deleteAll();
        apiJdbc.update("delete from audit_logs");

        vehicles.saveAndFlush(Vehicle.create(
                VEHICLE_ID, VEHICLE_PLATE, "Synthetic bus", 8, "IDLE",
                "POINT(118.0000000 32.0000000)", "Synthetic fleet", true));
        JtTerminal terminal = JtTerminal.preset(
                TERMINAL_ID, TERMINAL_IDENTITY, TERMINAL_CODE, "SIMMF", "SIM-MODEL",
                "JT808_2013", "WGS84", ACTOR_ID);
        terminal.configureCapabilities("T/JSATL12-2017", "[\"ADAS\",\"DMS\"]", false);
        terminals.saveAndFlush(terminal);
        JtTerminalVehicleBinding binding = JtTerminalVehicleBinding.bind(
                terminal, VEHICLE_ID, "synthetic contract binding", ACTOR_ID);
        ReflectionTestUtils.setField(
                binding, "validFrom", GATEWAY_TIME.minusSeconds(60).atOffset(ZoneOffset.UTC));
        bindings.saveAndFlush(binding);
    }

    @Test
    void deliversRegistrationAuthenticationLocationAlarmAndProtocolAuditThroughTheRealApi() throws Exception {
        try (GatewayRig rig = new GatewayRig(tempDir.resolve("complete-flow"), apiBaseUri());
             SimulatedTerminal simulator = registerAndAuthenticate(rig)) {
            assertGeneralAck(simulator, simulator.sendFrame(0x0200, fixtureBody("S01")), 0x0200);
            assertGeneralAck(simulator, simulator.sendFrame(0x1210, new byte[] {0x30, 0x30}), 0x1210);
            await(() -> rig.repository.totalCount() >= 5);

            try {
                rig.dispatchUntilSettled(20);
            } catch (RuntimeException failure) {
                throw new AssertionError(
                        "gateway outbox " + rig.outboxRows() + "; API receipts " + apiReceiptRows(), failure);
            }

            GatewayOutboxRepository.OperationalSnapshot snapshot =
                    rig.repository.operationalSnapshot(rig.clock.instant());
            assertThat(snapshot.pending()).as("gateway outbox %s; API receipts %s",
                    rig.outboxRows(), apiReceiptRows()).isZero();
            assertThat(snapshot.delivering()).isZero();
            assertThat(snapshot.deadLetter()).isZero();
            assertThat(rig.repository.deliveredCount()).isEqualTo(rig.repository.totalCount());
            assertThat(locations.findAll()).hasSize(1);
            assertThat(apiJdbc.queryForObject("select count(*) from vehicle_alarms", Integer.class)).isEqualTo(1);
            assertThat(apiJdbc.queryForObject("select count(*) from vehicle_alarm_outbox", Integer.class)).isEqualTo(1);
            assertThat(audits.findAll().stream()
                    .filter(audit -> "ACTIVE_SAFETY_ATTACHMENT_METADATA_REJECTED"
                            .equals(audit.getReasonCode())))
                    .hasSize(1);
        }
    }

    @Test
    void keepsRejectedAttachmentMetadataInTheGatewayOutbox() throws Exception {
        try (GatewayRig rig = new GatewayRig(tempDir.resolve("attachment-rejected"), apiBaseUri());
             SimulatedTerminal simulator = registerAndAuthenticate(rig)) {
            assertGeneralAck(simulator, simulator.sendFrame(0x0200, fixtureBody("S01")), 0x0200);
            assertGeneralAck(simulator, simulator.sendFrame(0x1210, fixtureBody("M01")), 0x1210);
            await(() -> rig.repository.totalCount() >= 5);

            rig.dispatcher.dispatchOnce();

            UUID attachmentKey = rig.jdbc.queryForObject(
                    "select idempotency_key from gateway_outbox where kind = 'ATTACHMENT_METADATA'",
                    UUID.class);
            assertNotNull(attachmentKey);
            GatewayOutboxRepository.OutboxEntry attachment = rig.repository.find(attachmentKey).orElseThrow();
            assertThat(attachment.status()).isEqualTo(GatewayOutboxRepository.DeliveryStatus.PENDING);
            assertThat(attachment.deliveredAt()).isNull();
            assertThat(attachment.lastErrorCode()).as("gateway outbox %s; API receipts %s",
                    rig.outboxRows(), apiReceiptRows())
                    .isEqualTo("API_ITEM_REJECTED");
            assertThat(attachment.payloadJson()).isNotNull();
            assertThat(receipts.findById(attachmentKey).orElseThrow().getFinalStatus()).isEqualTo("REJECTED");
            assertThat(apiJdbc.queryForObject(
                    "select count(*) from vehicle_alarm_attachments", Integer.class)).isZero();
        }
    }

    @Test
    void retriesAResponseLostSessionAuditWithTheSameOutboxUuidAndGetsReplayed() throws Exception {
        try (ResponseLossProxy proxy = new ResponseLossProxy(apiBaseUri(), objectMapper);
             GatewayRig rig = new GatewayRig(tempDir.resolve("response-loss"), proxy.baseUri());
             SimulatedTerminal simulator = registerAndAuthenticate(rig)) {
            await(() -> rig.repository.totalCount() >= 2);

            GatewayOutboxDispatcher.DispatchReport first = rig.dispatcher.dispatchOnce();

            assertThat(first.attempted()).isEqualTo(2);
            assertThat(first.delivered()).isZero();
            assertThat(first.retried()).isEqualTo(2);
            UUID droppedKey = proxy.droppedKey();
            assertNotNull(droppedKey);
            assertThat(audits.findByIdempotencyKey(droppedKey)).isPresent();

            rig.clock.advance(Duration.ofMillis(25));
            GatewayOutboxDispatcher.DispatchReport replay = rig.dispatcher.dispatchOnce();

            assertThat(replay.delivered()).isEqualTo(2);
            assertThat(proxy.replayedDroppedKey()).isTrue();
            assertThat(audits.findAll().stream()
                    .filter(audit -> droppedKey.equals(audit.getIdempotencyKey())))
                    .hasSize(1);
            assertThat(rig.repository.deliveredCount()).isEqualTo(rig.repository.totalCount());
        }
    }

    @Test
    void rejectsAnOversizedContentLengthIngressBeforeJacksonOrBusinessWrites() throws Exception {
        String body = ingressWithPayload(UUID.randomUUID(), "x".repeat(ONE_MIB));

        HttpResponse<String> response = postGateway(
                "/internal/jt-gateway/ingress", HttpRequest.BodyPublishers.ofString(body));

        assertThat(response.statusCode()).isEqualTo(413);
        assertNoIngressWrites();
    }

    @Test
    void rejectsAnOversizedChunkedIngressBeforeJacksonOrBusinessWrites() throws Exception {
        byte[] body = ingressWithPayload(UUID.randomUUID(), "x".repeat(ONE_MIB))
                .getBytes(StandardCharsets.UTF_8);
        HttpRequest.BodyPublisher chunked = HttpRequest.BodyPublishers.ofInputStream(
                () -> new ByteArrayInputStream(body));
        assertThat(chunked.contentLength()).isEqualTo(-1);

        HttpResponse<String> response = postGateway("/internal/jt-gateway/ingress", chunked);

        assertThat(response.statusCode()).isEqualTo(413);
        assertNoIngressWrites();
    }

    @Test
    void rejectsExcessiveIngressJsonNestingBeforeBusinessWrites() throws Exception {
        UUID key = UUID.randomUUID();
        String nested = "[".repeat(40) + "0" + "]".repeat(40);
        String body = """
                [{"schemaVersion":1,"idempotencyKey":"%s","kind":"UNKNOWN",
                  "gatewayReceivedAt":"2026-01-15T02:00:00Z","payloadJson":"{}","extra":%s}]
                """.formatted(key, nested);

        HttpResponse<String> response = postGateway(
                "/internal/jt-gateway/ingress", HttpRequest.BodyPublishers.ofString(body));

        assertThat(response.statusCode()).isEqualTo(413);
        assertNoIngressWrites();
    }

    @Test
    void rejectsAnExcessiveIngressJsonStringBeforeBusinessWrites() throws Exception {
        String body = ingressWithPayload(
                UUID.randomUUID(), "x".repeat(MAX_INGRESS_STRING_LENGTH + 1));

        HttpResponse<String> response = postGateway(
                "/internal/jt-gateway/ingress", HttpRequest.BodyPublishers.ofString(body));

        assertThat(response.statusCode()).isEqualTo(413);
        assertNoIngressWrites();
    }

    @Test
    void acceptsAnIngressJsonStringAtTheConfiguredBoundary() throws Exception {
        UUID key = UUID.randomUUID();
        String body = ingressWithPayload(key, "x".repeat(MAX_INGRESS_STRING_LENGTH));

        HttpResponse<String> response = postGateway(
                "/internal/jt-gateway/ingress", HttpRequest.BodyPublishers.ofString(body));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(receipts.findById(key)).isPresent().get()
                .extracting(com.idavy.drtops.domain.location.JtGatewayIngressReceipt::getFinalStatus)
                .isEqualTo("REJECTED");
    }

    @Test
    void acceptsAWithinBudgetFiftyItemIngressBatch() throws Exception {
        com.fasterxml.jackson.databind.node.ArrayNode batch = objectMapper.createArrayNode();
        for (int index = 0; index < 50; index++) {
            com.fasterxml.jackson.databind.node.ObjectNode item = objectMapper.createObjectNode();
            item.put("schemaVersion", 1);
            item.put("idempotencyKey", UUID.randomUUID().toString());
            item.put("kind", "ATTACHMENT_METADATA");
            item.put("gatewayReceivedAt", GATEWAY_TIME.toString());
            item.put("payloadJson", "{}");
            batch.add(item);
        }

        HttpResponse<String> response = postGateway(
                "/internal/jt-gateway/ingress",
                HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(batch)));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode data = objectMapper.readTree(response.body()).required("data");
        assertThat(data).hasSize(50);
        assertThat(java.util.stream.StreamSupport.stream(data.spliterator(), false)
                .map(item -> item.required("status").asText()).toList())
                .containsOnly("REJECTED");
        assertThat(receipts.findAll()).hasSize(50);
    }

    @Test
    void doesNotApplyIngressStringLimitsToAnotherGatewayApi() throws Exception {
        UUID key = UUID.randomUUID();
        String body = """
                {"idempotencyKey":"%s","eventType":"OFFLINE","result":"APPLIED",
                 "occurredAt":"2026-01-15T02:00:00Z","gatewayInstance":"synthetic-budget-test",
                 "padding":"%s"}
                """.formatted(key, "x".repeat(MAX_INGRESS_STRING_LENGTH + 1));

        HttpResponse<String> response = postGateway(
                "/internal/jt-gateway/audit-events", HttpRequest.BodyPublishers.ofString(body));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(audits.findByIdempotencyKey(key)).isPresent();
    }

    private SimulatedTerminal registerAndAuthenticate(GatewayRig rig) {
        SimulatedTerminal simulator = new SimulatedTerminal(
                TERMINAL_IDENTITY, ProtocolVersion.JT808_2013, VEHICLE_PLATE);
        simulator.connect(rig.endpoint());
        int registrationSerial = simulator.sendRegistration();
        SimulatedTerminal.ReplyRecord registration = simulator.awaitReply(Duration.ofSeconds(3));
        assertNotNull(registration);
        assertEquals(0x8100, registration.messageId());
        assertEquals(0, registration.result());
        assertEquals(registrationSerial, registration.requestSerialNo());

        JtTerminal registered = terminals.findById(TERMINAL_ID).orElseThrow();
        terminalManagement.activate(
                TERMINAL_CODE, registered.getVersion(), "synthetic activation", ACTOR_ID);

        assertGeneralAck(simulator, simulator.sendAuthentication(), 0x0102);
        return simulator;
    }

    private static void assertGeneralAck(SimulatedTerminal simulator, int serial, int requestMessageId) {
        SimulatedTerminal.ReplyRecord reply = simulator.awaitReply(Duration.ofSeconds(3));
        assertNotNull(reply);
        assertEquals(0x8001, reply.messageId());
        assertEquals(requestMessageId, reply.requestMessageId());
        assertEquals(serial, reply.requestSerialNo());
        assertEquals(0, reply.result());
    }

    private URI apiBaseUri() {
        return URI.create("http://127.0.0.1:" + apiPort);
    }

    private HttpResponse<String> postGateway(String path, HttpRequest.BodyPublisher body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(apiBaseUri().resolve(path))
                .version(HttpClient.Version.HTTP_1_1)
                .header("Authorization", "Bearer " + SERVICE_CREDENTIAL)
                .header("X-Service-Credential-Version", "1")
                .header("Content-Type", "application/json")
                .POST(body)
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String ingressWithPayload(UUID key, String payload) {
        return """
                [{"schemaVersion":1,"idempotencyKey":"%s","kind":"UNKNOWN",
                  "gatewayReceivedAt":"2026-01-15T02:00:00Z","payloadJson":"%s"}]
                """.formatted(key, payload);
    }

    private void assertNoIngressWrites() {
        assertThat(receipts.findAll()).isEmpty();
        assertThat(audits.findAll()).isEmpty();
        assertThat(locations.findAll()).isEmpty();
        assertThat(apiJdbc.queryForObject("select count(*) from vehicle_alarms", Integer.class)).isZero();
        assertThat(apiJdbc.queryForObject("select count(*) from vehicle_alarm_outbox", Integer.class)).isZero();
    }

    private List<java.util.Map<String, Object>> apiReceiptRows() {
        return apiJdbc.queryForList("""
                select idempotency_key, final_status, cast(reason_codes as varchar) as reason_codes,
                       ingress_kind, terminal_id, vehicle_id
                from jt_gateway_ingress_receipts order by created_at, idempotency_key
                """);
    }

    private byte[] fixtureBody(String sampleId) throws IOException {
        try (InputStream source = getClass().getResourceAsStream("/protocol-fixtures/simulator-frames.json")) {
            if (source == null) {
                throw new IOException("simulator fixtures are missing");
            }
            for (JsonNode sample : objectMapper.readTree(source).required("samples")) {
                if (sampleId.equals(sample.required("sampleId").asText())) {
                    return HexFormat.of().parseHex(sample.required("bodyHex").asText());
                }
            }
        }
        throw new IOException("unknown synthetic fixture " + sampleId);
    }

    private static void await(CheckedCondition condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.satisfied()) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(condition.satisfied()).as("condition before timeout").isTrue();
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @FunctionalInterface
    interface CheckedCondition {
        boolean satisfied() throws Exception;
    }

    static final class GatewayRig implements AutoCloseable {
        final MutableClock clock = new MutableClock(GATEWAY_TIME);
        final HikariDataSource dataSource;
        final JdbcTemplate jdbc;
        final GatewayOutboxRepository repository;
        final GatewayIngressBuffer buffer;
        final GatewayOutboxDispatcher dispatcher;
        final JtGatewayServer server;
        final int port;

        GatewayRig(Path dataDirectory, URI operationsBaseUri) throws IOException {
            Files.createDirectories(dataDirectory);
            String databasePath = dataDirectory.resolve("gateway-outbox").toAbsolutePath()
                    .toString().replace('\\', '/');
            HikariConfig dataSourceConfiguration = new HikariConfig();
            dataSourceConfiguration.setJdbcUrl(
                    "jdbc:h2:file:" + databasePath + ";MODE=PostgreSQL;DB_CLOSE_ON_EXIT=FALSE");
            dataSourceConfiguration.setUsername("sa");
            dataSourceConfiguration.setPassword("");
            dataSourceConfiguration.setMinimumIdle(1);
            dataSourceConfiguration.setMaximumPoolSize(2);
            dataSourceConfiguration.setPoolName("jt-api-contract-" + UUID.randomUUID());
            dataSource = new HikariDataSource(dataSourceConfiguration);
            Path migrationDirectory = Path.of(System.getProperty("user.dir"))
                    .resolve("../jt-gateway/src/main/resources/db/migration")
                    .normalize().toAbsolutePath();
            if (!Files.isDirectory(migrationDirectory)) {
                throw new IOException("gateway migration directory is missing");
            }
            Flyway.configure().dataSource(dataSource)
                    .locations("filesystem:" + migrationDirectory.toString().replace('\\', '/'))
                    .load().migrate();
            jdbc = new JdbcTemplate(dataSource);
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            repository = new GatewayOutboxRepository(dataSource);
            buffer = new GatewayIngressBuffer(repository, mapper, clock);
            OperationsApiStatus status = new OperationsApiStatus(clock);
            RestClient.Builder builder = RestClient.builder();
            OperationsTerminalRegistryClient registry = new OperationsTerminalRegistryClient(
                    builder, operationsBaseUri.toString(), SERVICE_CREDENTIAL, 1,
                    "synthetic-gateway", new java.security.SecureRandom(), status, buffer, mapper);
            OperationsApiClient operations = new OperationsApiClient(
                    builder,
                    operationsBaseUri.resolve("/internal/jt-gateway/ingress"),
                    operationsBaseUri.resolve("/internal/jt-gateway/audit-events"),
                    () -> SERVICE_CREDENTIAL, 1, status);
            dispatcher = new GatewayOutboxDispatcher(
                    repository, operations, clock, 8, Duration.ofMillis(25), Duration.ofSeconds(1));
            TerminalSessionRegistry sessions = new TerminalSessionRegistry();
            ProtocolModuleRegistry protocol = new ProtocolModuleRegistry(
                    new Jt808CoreModule(new LocationReportCodec()), buffer, mapper, sessions, clock);
            server = new JtGatewayServer(
                    new JtGatewayServer.Configuration(
                            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                            4, 100, 2, 256, 80, 40, Duration.ofSeconds(5)),
                    registry, sessions, protocol);
            port = server.start();
        }

        InetSocketAddress endpoint() {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
        }

        void dispatchUntilSettled(int maximumRounds) {
            for (int round = 0; round < maximumRounds; round++) {
                dispatcher.dispatchOnce();
                GatewayOutboxRepository.OperationalSnapshot snapshot =
                        repository.operationalSnapshot(clock.instant());
                if (snapshot.pending() == 0 && snapshot.delivering() == 0 && snapshot.deadLetter() == 0) {
                    return;
                }
                clock.advance(Duration.ofMillis(25));
            }
        }

        List<java.util.Map<String, Object>> outboxRows() {
            return jdbc.queryForList("""
                    select kind, status, attempt_count, last_error_code
                    from gateway_outbox order by created_at, idempotency_key
                    """);
        }

        @Override
        public void close() {
            try {
                server.close();
            } finally {
                dataSource.close();
            }
        }
    }

    static final class ResponseLossProxy implements AutoCloseable {
        private final URI upstream;
        private final ObjectMapper mapper;
        private final HttpClient client = HttpClient.newHttpClient();
        private final HttpServer server;
        private final ExecutorService workers = Executors.newCachedThreadPool();
        private final AtomicBoolean dropNextAuditResponse = new AtomicBoolean(true);
        private final AtomicReference<UUID> droppedKey = new AtomicReference<>();
        private final AtomicBoolean replayedDroppedKey = new AtomicBoolean();

        ResponseLossProxy(URI upstream, ObjectMapper mapper) throws IOException {
            this.upstream = upstream;
            this.mapper = mapper;
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", this::forward);
            server.setExecutor(workers);
            server.start();
        }

        URI baseUri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        UUID droppedKey() {
            return droppedKey.get();
        }

        boolean replayedDroppedKey() {
            return replayedDroppedKey.get();
        }

        private void forward(HttpExchange exchange) throws IOException {
            byte[] body = exchange.getRequestBody().readAllBytes();
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(
                                upstream.resolve(exchange.getRequestURI().toString()))
                        .method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(body));
                copyHeader(exchange, request, "Authorization");
                copyHeader(exchange, request, "X-Service-Credential-Version");
                copyHeader(exchange, request, "Content-Type");
                HttpResponse<byte[]> response = client.send(
                        request.build(), HttpResponse.BodyHandlers.ofByteArray());
                boolean audit = exchange.getRequestURI().getPath().endsWith("/audit-events");
                UUID key = audit ? UUID.fromString(mapper.readTree(body).required("idempotencyKey").asText()) : null;
                if (audit && dropNextAuditResponse.compareAndSet(true, false)) {
                    droppedKey.set(key);
                    exchange.close();
                    return;
                }
                if (audit && key.equals(droppedKey.get())) {
                    JsonNode result = mapper.readTree(response.body()).path("data");
                    replayedDroppedKey.set("REPLAYED".equals(result.path("status").asText()));
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(response.statusCode(), response.body().length);
                exchange.getResponseBody().write(response.body());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                exchange.sendResponseHeaders(502, -1);
            } finally {
                exchange.close();
            }
        }

        private static void copyHeader(
                HttpExchange exchange, HttpRequest.Builder request, String name) {
            String value = exchange.getRequestHeaders().getFirst(name);
            if (value != null) {
                request.header(name, value);
            }
        }

        @Override
        public void close() {
            server.stop(0);
            workers.shutdownNow();
        }
    }

    static final class MutableClock extends Clock {
        private Instant current;

        MutableClock(Instant current) {
            this.current = current;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        ServiceAreaLocationChecker alwaysInsidePublishedArea() {
            return (longitude, latitude) -> true;
        }
    }
}
