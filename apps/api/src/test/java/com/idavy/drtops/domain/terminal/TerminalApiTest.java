package com.idavy.drtops.domain.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasKey;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import com.idavy.drtops.domain.onboard.OnboardDeviceMembershipRepository;
import com.idavy.drtops.domain.onboard.OnboardDeviceProtocolProfile;
import com.idavy.drtops.domain.onboard.OnboardDeviceProtocolProfileRepository;
import com.idavy.drtops.domain.onboard.OnboardTestFixtures;
import com.idavy.drtops.integration.jtgateway.JtGatewayControlClient;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.UUID;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.security.test.context.support.WithMockUser;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import com.idavy.drtops.auth.Permission;
import com.idavy.drtops.auth.RoleCode;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:terminal_api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE, printOnlyOnFailure = false)
@WithMockUser(username = "11111111-1111-1111-1111-111111111111",
        authorities = {"TERMINAL_READ", "TERMINAL_MANAGE"})
@Import({TerminalApiTest.ControlClientConfiguration.class, OnboardTestFixtures.class})
class TerminalApiTest {

    private static final UUID VEHICLE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CORRECTION_VEHICLE_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final String SERVICE_CREDENTIAL = UUID.randomUUID().toString();
    private static final String TOKEN_HASH = sha256(UUID.randomUUID().toString());

    @Autowired
    MockMvc mockMvc;

    @Autowired
    TerminalManagementService service;

    @Autowired
    JtTerminalRepository terminalRepository;

    @Autowired
    JtTerminalVehicleBindingRepository bindingRepository;

    @Autowired
    JtGatewayAuditEventRepository gatewayAuditRepository;

    @Autowired
    AuditLogRepository auditLogRepository;

    @Autowired
    VehicleRepository vehicleRepository;

    @Autowired
    OnboardDeviceMembershipRepository onboardMembershipRepository;

    @Autowired
    OnboardDeviceProtocolProfileRepository onboardProfileRepository;

    @Autowired
    OnboardTestFixtures onboardFixtures;

    @Autowired
    FakeControlClient controlClient;

    @Autowired
    Clock terminalClock;

    @DynamicPropertySource
    static void gatewayCredential(DynamicPropertyRegistry registry) {
        registry.add("jt.gateway.service-credentials.current.version", () -> "7");
        registry.add("jt.gateway.service-credentials.current.hash", () -> sha256(SERVICE_CREDENTIAL));
    }

    @BeforeEach
    void setUp() {
        gatewayAuditRepository.deleteAll();
        onboardFixtures.clear();
        controlClient.available = true;
        controlClient.requests.clear();
        vehicleRepository.save(Vehicle.create(
                VEHICLE_ID, "浙A20001", "Microbus", 8, "IDLE",
                "POINT(120.155 30.274)", "测试车队", true));
        vehicleRepository.save(Vehicle.create(
                CORRECTION_VEHICLE_ID, "浙A20002", "Microbus", 8, "IDLE",
                "POINT(120.155 30.274)", "P6-2 REAL TERMINAL ACCEPTANCE", false));
    }

    @Test
    void keepsInjectedClockAfterFixtureWritesWithinABoundedPositiveOffset() {
        onboardFixtures.configureRecorderSystem("clock-relative", "CLOCK-RELATIVE");
        JtTerminal terminal = terminalRepository.findByTerminalCode("clock-relative")
                .orElseThrow();
        assertThat(onboardMembershipRepository.findActiveByTerminalId(terminal.getId()))
                .isPresent();
        Instant fixtureFinishedAt = Instant.now();

        assertThat(terminalClock.instant())
                .isAfterOrEqualTo(fixtureFinishedAt)
                .isBeforeOrEqualTo(fixtureFinishedAt.plus(Duration.ofMinutes(10)));
    }

    @Test
    void correctsIdentityThroughAWriteOnlyApiWithoutReturningSensitiveValues() throws Exception {
        JtTerminal terminal = service.preset(new TerminalManagementService.PresetCommand(
                "PHONE-API-OLD", "T-API-OLD", "MFG01", "MODEL-X",
                "JT808_2019", "GCJ02", UUID.fromString("11111111-1111-1111-1111-111111111111"), "设备预置"));
        service.bind(terminal.getTerminalCode(), CORRECTION_VEHICLE_ID,
                terminal.getVersion(), "首配车辆", UUID.fromString("11111111-1111-1111-1111-111111111111"));
        terminal = terminalRepository.findById(terminal.getId()).orElseThrow();

        String request = """
                {"expectedVersion":%d,"terminalPhone":"00000000000000000001",
                 "terminalCode":"T-API-NEW","manufacturerId":"MFG-NEW",
                 "model":"MODEL-NEW","protocolVersion":"JT/T 808-2019",
                 "sourceCoordinateSystem":"WGS84","vehicleIdentifier":"浙A20002-NEW",
                 "reason":"PRE_ACCEPTANCE_IDENTITY_CORRECTION"}
                """.formatted(terminal.getVersion());

        mockMvc.perform(post("/api/terminals/T-API-OLD/identity-correction/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changedFields").isArray())
                .andExpect(jsonPath("$.data.version").value(terminal.getVersion()));
        assertThat(terminalRepository.findByTerminalCode("T-API-OLD")).isPresent();

        mockMvc.perform(post("/api/terminals/T-API-OLD/identity-correction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changedFields").isArray())
                .andExpect(jsonPath("$.data.version").isNumber())
                .andExpect(jsonPath("$.data", not(hasKey("terminalPhone"))))
                .andExpect(jsonPath("$.data", not(hasKey("terminalCode"))))
                .andExpect(jsonPath("$.data", not(hasKey("vehicleIdentifier"))));
    }

    @Test
    void managesTerminalByPublicCodeAndNeverReturnsInternalIdentityOrDigest() throws Exception {
        String createResponse = mockMvc.perform(post("/api/terminals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(presetRequest("T-API-001", "PHONE-9001")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.terminalCode").value("T-API-001"))
                .andExpect(jsonPath("$.data.terminalPhoneMasked").value("****9001"))
                .andExpect(jsonPath("$.data", not(hasKey("id"))))
                .andExpect(jsonPath("$.data", not(hasKey("authTokenHash"))))
                .andReturn().getResponse().getContentAsString();

        long version = ((Number) JsonPath.read(createResponse, "$.data.version")).longValue();
        mockMvc.perform(post("/api/terminals/T-API-001/bind")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vehicleId":"%s","expectedVersion":%d,"reason":"首配车辆"}
                                """.formatted(VEHICLE_ID, version)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.terminalCode").value("T-API-001"));

        mockMvc.perform(get("/api/terminals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].terminalPhoneMasked").value("****9001"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(TOKEN_HASH))));
    }

    @Test
    void configuresAValidatedCapabilityProfileAndCarriesItFromPersistenceIntoRegistration() throws Exception {
        String createResponse = mockMvc.perform(post("/api/terminals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(presetRequest("T-CAP-001", "PHONE-CAP-001")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long version = ((Number) JsonPath.read(createResponse, "$.data.version")).longValue();

        String configuredResponse = mockMvc.perform(post("/api/terminals/T-CAP-001/capabilities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":%d,"activeSafetyStandard":"T/JSATL12-2017",
                                 "activeSafetyModules":["ADAS","DMS"],"jt1078Enabled":true,
                                 "reason":"配置苏标主动安全能力"}
                                """.formatted(version)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeSafetyStandard").value("T/JSATL12-2017"))
                .andExpect(jsonPath("$.data.activeSafetyModules[0]").value("ADAS"))
                .andExpect(jsonPath("$.data.activeSafetyModules[1]").value("DMS"))
                .andExpect(jsonPath("$.data.jt1078Enabled").value(true))
                .andReturn().getResponse().getContentAsString();
        long configuredVersion = ((Number) JsonPath.read(configuredResponse, "$.data.version")).longValue();

        mockMvc.perform(post("/api/terminals/T-CAP-001/bind")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vehicleId":"%s","expectedVersion":%d,"reason":"能力终端绑定车辆"}
                                """.formatted(VEHICLE_ID, configuredVersion)))
                .andExpect(status().isOk());

        TerminalManagementService.RegistrationDecision decision = service.verifyRegistration(
                "PHONE-CAP-001", "T-CAP-001", "MFG01", "MODEL-X",
                vehicleRepository.findById(VEHICLE_ID).orElseThrow().getPlateNumber(), "JT808_2019");
        assertThat(decision.approved()).isTrue();
        assertThat(decision.activeSafetyStandard()).isEqualTo("T/JSATL12-2017");
        assertThat(decision.activeSafetyModules()).containsExactly("ADAS", "DMS");
        JtTerminal terminal = terminalRepository.findByTerminalCode("T-CAP-001").orElseThrow();
        assertThat(auditLogRepository.findByEntityId(terminal.getId()))
                .anySatisfy(audit -> assertThat(audit.getAction())
                        .isEqualTo("JT_TERMINAL_CAPABILITY_PROFILE_CONFIGURED"));
    }

    @Test
    void registersAKnownUnimplementedGuangdongProfileWithoutEnablingJsatl12Decoding() throws Exception {
        String createResponse = mockMvc.perform(post("/api/terminals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(presetRequest("T-CAP-GD-001", "PHONE-CAP-GD-001")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long version = ((Number) JsonPath.read(createResponse, "$.data.version")).longValue();

        String configuredResponse = mockMvc.perform(post("/api/terminals/T-CAP-GD-001/capabilities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":%d,"activeSafetyStandard":"T/GD-ACTIVE-SAFETY",
                                 "activeSafetyModules":["ADAS"],"jt1078Enabled":false,
                                 "reason":"登记粤标扩展能力但不启用解析"}
                                """.formatted(version)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeSafetyStandard").value("T/GD-ACTIVE-SAFETY"))
                .andExpect(jsonPath("$.data.activeSafetyModules[0]").value("ADAS"))
                .andReturn().getResponse().getContentAsString();
        long configuredVersion = ((Number) JsonPath.read(configuredResponse, "$.data.version")).longValue();

        mockMvc.perform(post("/api/terminals/T-CAP-GD-001/bind")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vehicleId":"%s","expectedVersion":%d,"reason":"粤标能力终端绑定车辆"}
                                """.formatted(VEHICLE_ID, configuredVersion)))
                .andExpect(status().isOk());

        internalPost("/internal/jt-gateway/registrations/verify", """
                {"terminalPhone":"PHONE-CAP-GD-001","terminalCode":"T-CAP-GD-001",
                 "manufacturerId":"MFG01","model":"MODEL-X","vehicleIdentifier":"浙A20001",
                 "protocolVersion":"JT808_2019"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approved").value(true))
                .andExpect(jsonPath("$.data.activeSafetyStandard").value("T/GD-ACTIVE-SAFETY"))
                .andExpect(jsonPath("$.data.activeSafetyModules[0]").value("ADAS"));
    }

    @Test
    void rejectsInvalidOrStaleCapabilityProfileChanges() throws Exception {
        String createResponse = mockMvc.perform(post("/api/terminals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(presetRequest("T-CAP-INVALID", "PHONE-CAP-002")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long version = ((Number) JsonPath.read(createResponse, "$.data.version")).longValue();

        mockMvc.perform(post("/api/terminals/T-CAP-INVALID/capabilities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":%d,"activeSafetyStandard":"T/JSATL12-2017",
                                 "activeSafetyModules":["ADAS","UNKNOWN"],"jt1078Enabled":false,
                                 "reason":"非法能力组合"}
                                """.formatted(version)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/terminals/T-CAP-INVALID/capabilities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":%d,"activeSafetyStandard":"T/JSATL12-2017",
                                 "activeSafetyModules":["ADAS"],"jt1078Enabled":false,
                                 "reason":"过期版本修改"}
                                """.formatted(version + 1)))
                .andExpect(status().isConflict());
        JtTerminal terminal = terminalRepository.findByTerminalCode("T-CAP-INVALID").orElseThrow();
        assertThat(terminal.getActiveSafetyStandard()).isNull();
        assertThat(terminal.getActiveSafetyModules()).isEqualTo("[]");
    }

    @Test
    void returnsAReadOnlyTerminalDetailWithoutSensitiveGatewayFields() throws Exception {
        JtTerminal terminal = presetAndBind("T-API-DETAIL", "PHONE-9012");
        JtGatewayAuditEvent event = JtGatewayAuditEvent.record(
                terminal.getId(), VEHICLE_ID, JtGatewayAuditEvent.EventType.ONLINE,
                JtGatewayAuditEvent.Result.APPLIED, "SESSION_ESTABLISHED", "JT808_2019", 2,
                TOKEN_HASH, "203.0.113.7:8800", java.time.OffsetDateTime.parse("2026-08-12T08:00:00Z"), "gateway-a");
        gatewayAuditRepository.save(event);

        String response = mockMvc.perform(get("/api/terminals/T-API-DETAIL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.terminalCode").value("T-API-DETAIL"))
                .andExpect(jsonPath("$.data.terminalPhoneMasked").value("****9012"))
                .andExpect(jsonPath("$.data.onlineStatus").value("NEVER_SEEN"))
                .andExpect(jsonPath("$.data.lastAuthenticatedAt").isEmpty())
                .andExpect(jsonPath("$.data.lastHeartbeatAt").isEmpty())
                .andExpect(jsonPath("$.data.lastLocationAt").isEmpty())
                .andExpect(jsonPath("$.data.currentBinding").doesNotExist())
                .andExpect(jsonPath("$.data.bindingHistory").isEmpty())
                .andExpect(jsonPath("$.data.securityAudits[0].eventType").value("ONLINE"))
                .andExpect(jsonPath("$.data.securityAudits[0].reasonCode").value("SESSION_ESTABLISHED"))
                .andExpect(jsonPath("$.data", not(hasKey("id"))))
                .andExpect(jsonPath("$.data", not(hasKey("authTokenHash"))))
                .andExpect(jsonPath("$.data", not(hasKey("authTokenVersion"))))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain(terminal.getId().toString(), VEHICLE_ID.toString(), "PHONE-9012",
                TOKEN_HASH, "203.0.113.7:8800");
    }

    @Test
    void neverReconstructsACompleteShortTerminalPhone() throws Exception {
        mockMvc.perform(post("/api/terminals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(presetRequest("T-API-SHORT", "ABCD")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.terminalPhoneMasked").value("****"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("ABCD"))));
    }

    @Test
    void verifiesCompleteIdentityAndAcceptsOnlyGatewayGeneratedDigestOnCompletion() throws Exception {
        JtTerminal terminal = presetAndBind("T-API-002", "PHONE-9002");

        String verification = internalPost("/internal/jt-gateway/registrations/verify", """
                {"terminalPhone":"PHONE-9002","terminalCode":"T-API-002",
                 "manufacturerId":"MFG01","model":"MODEL-X","vehicleIdentifier":"浙A20001",
                 "protocolVersion":"JT808_2019"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approved").value(true))
                .andExpect(jsonPath("$.data.terminalId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        UUID terminalId = UUID.fromString(JsonPath.read(verification, "$.data.terminalId"));
        assertThat(terminalId).isEqualTo(terminal.getId());

        internalPost("/internal/jt-gateway/registrations/verify", """
                {"terminalPhone":"PHONE-OTHER","terminalCode":"T-API-002",
                 "manufacturerId":"MFG01","model":"MODEL-X","vehicleIdentifier":"浙A20001",
                 "protocolVersion":"JT808_2019"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approved").value(false))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("PHONE-OTHER"))));

        internalPost("/internal/jt-gateway/registrations/" + terminalId + "/complete", """
                {"tokenVersion":1,"tokenSha256":"%s","gatewayInstance":"gateway-a"}
                """.formatted(TOKEN_HASH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completed").value(true))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(TOKEN_HASH))));

        JtTerminal registered = terminalRepository.findById(terminalId).orElseThrow();
        mockMvc.perform(post("/api/terminals/T-API-002/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(action(registered.getVersion(), "正式启用")))
                .andExpect(status().isOk());

        internalPost("/internal/jt-gateway/authentications/verify", """
                {"terminalId":"%s","tokenVersion":1,"tokenSha256":"%s","gatewayInstance":"gateway-a"}
                """.formatted(terminalId, TOKEN_HASH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approved").value(true));
        internalPost("/internal/jt-gateway/authentications/verify", """
                {"terminalId":"%s","tokenVersion":1,"tokenSha256":"%s","gatewayInstance":"gateway-a"}
                """.formatted(terminalId, sha256(UUID.randomUUID().toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approved").value(false));
    }

    @Test
    void verifiesAndCompletesTwoCompositeDevicesWithoutLegacyBindings() throws Exception {
        onboardFixtures.configureDualDeviceSystem(
                "dispatch-01", "recorder-01", "VEHICLE-COMPOSITE");

        String dispatchBody = internalPost("/internal/jt-gateway/registrations/verify", """
                {"terminalPhone":"PHONE-DISPATCH","terminalCode":"dispatch-01",
                 "manufacturerId":"SYNTH","model":"SYNTHETIC",
                 "vehicleIdentifier":"VEHICLE-COMPOSITE","protocolVersion":"JT808_2019"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approved").value(true))
                .andExpect(jsonPath("$.data.terminalId").isNotEmpty())
                .andExpect(jsonPath("$.data.vehicleId").isNotEmpty())
                .andExpect(jsonPath("$.data.onboardSystemId").isNotEmpty())
                .andExpect(jsonPath("$.data.context.roles").isArray())
                .andReturn().getResponse().getContentAsString();
        String recorderBody = internalPost("/internal/jt-gateway/registrations/verify", """
                {"terminalPhone":"PHONE-RECORDER","terminalCode":"recorder-01",
                 "manufacturerId":"SYNTH","model":"SYNTHETIC",
                 "vehicleIdentifier":"VEHICLE-COMPOSITE","protocolVersion":"JT808_2019"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approved").value(true))
                .andExpect(jsonPath("$.data.terminalId").isNotEmpty())
                .andExpect(jsonPath("$.data.onboardSystemId").isNotEmpty())
                .andExpect(jsonPath("$.data.context.roles").isArray())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.read(dispatchBody, "$.data.onboardSystemId").toString())
                .isEqualTo(JsonPath.read(recorderBody, "$.data.onboardSystemId").toString());
        assertThat(JsonPath.read(dispatchBody, "$.data.terminalId").toString())
                .isNotEqualTo(JsonPath.read(recorderBody, "$.data.terminalId").toString());
        assertThat(dispatchBody).doesNotContain(
                "PHONE-DISPATCH", "dispatch-01", "VEHICLE-COMPOSITE", "SYNTHETIC");
        assertThat(recorderBody).doesNotContain(
                "PHONE-RECORDER", "recorder-01", "VEHICLE-COMPOSITE", "SYNTHETIC");

        UUID recorderId = UUID.fromString(JsonPath.read(recorderBody, "$.data.terminalId"));
        internalPost("/internal/jt-gateway/registrations/" + recorderId + "/complete", """
                {"tokenVersion":1,"tokenSha256":"%s","gatewayInstance":"gateway-a"}
                """.formatted(TOKEN_HASH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completed").value(true))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(TOKEN_HASH))));
        assertThat(terminalRepository.findById(recorderId).orElseThrow().getLastRegisteredAt())
                .isNotNull();
        assertThat(bindingRepository.findByTerminalIdAndStatus(
                recorderId, JtTerminalVehicleBinding.Status.ACTIVE)).isEmpty();
    }

    @Test
    void identityAndTerminalIdAuthenticationReturnEquivalentCompositeContext() throws Exception {
        onboardFixtures.configureRecorderSystem("recorder-01", "VEHICLE-AUTH-CONTEXT");
        JtTerminal terminal = terminalRepository.findByTerminalCode("recorder-01").orElseThrow();
        terminal.completeRegistration(1, TOKEN_HASH);
        terminal.activate(true);
        terminalRepository.saveAndFlush(terminal);

        String byId = internalPost("/internal/jt-gateway/authentications/verify", """
                {"terminalId":"%s","tokenVersion":1,"tokenSha256":"%s",
                 "gatewayInstance":"gateway-a"}
                """.formatted(terminal.getId(), TOKEN_HASH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approved").value(true))
                .andExpect(jsonPath("$.data.context.onboardSystemId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String byIdentity = internalPost(
                        "/internal/jt-gateway/authentications/verify-by-identity", """
                {"protocolVersion":"JT808_2019","terminalPhone":"PHONE-RECORDER",
                 "tokenSha256":"%s","gatewayInstance":"gateway-a"}
                """.formatted(TOKEN_HASH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approved").value(true))
                .andExpect(jsonPath("$.data.context.onboardSystemId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.read(byId, "$.data.context").toString())
                .isEqualTo(JsonPath.read(byIdentity, "$.data.context").toString());
        assertThat(byId).doesNotContain(TOKEN_HASH, "PHONE-RECORDER", "recorder-01", "SYNTHETIC");
        assertThat(byIdentity).doesNotContain(
                TOKEN_HASH, "PHONE-RECORDER", "recorder-01", "SYNTHETIC");
    }

    @Test
    void rejectsIdentityMismatchAndFailedAuthenticationWithoutEchoingSecretsOrUpdatingTime()
            throws Exception {
        onboardFixtures.configureDualDeviceSystem(
                "dispatch-01", "recorder-01", "VEHICLE-SECRET");
        String mismatch = internalPost("/internal/jt-gateway/registrations/verify", """
                {"terminalPhone":"PHONE-RECORDER","terminalCode":"dispatch-01",
                 "manufacturerId":"SYNTH","model":"SYNTHETIC",
                 "vehicleIdentifier":"VEHICLE-SECRET","protocolVersion":"JT808_2019"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approved").value(false))
                .andExpect(jsonPath("$.data.reasonCode").value("TERMINAL_IDENTITY_MISMATCH"))
                .andReturn().getResponse().getContentAsString();
        assertThat(mismatch).doesNotContain(
                "PHONE-RECORDER", "dispatch-01", "VEHICLE-SECRET", "SYNTHETIC");

        JtTerminal recorder = terminalRepository.findByTerminalCode("recorder-01").orElseThrow();
        recorder.completeRegistration(1, TOKEN_HASH);
        recorder.activate(true);
        terminalRepository.saveAndFlush(recorder);
        String rejected = internalPost(
                        "/internal/jt-gateway/authentications/verify-by-identity", """
                {"protocolVersion":"JT808_2019","terminalPhone":"PHONE-RECORDER",
                 "tokenSha256":"%s","gatewayInstance":"gateway-a"}
                """.formatted(sha256("wrong-secret")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approved").value(false))
                .andExpect(jsonPath("$.data.context").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(rejected).doesNotContain(
                "wrong-secret", "PHONE-RECORDER", "recorder-01",
                recorder.getId().toString(), "SYNTHETIC");
        assertThat(terminalRepository.findById(recorder.getId()).orElseThrow()
                .getLastAuthenticatedAt()).isNull();

        var membership = onboardMembershipRepository.findActiveByTerminalId(recorder.getId())
                .orElseThrow();
        membership.remove("test membership removal",
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                membership.getValidFrom().plusSeconds(1));
        onboardMembershipRepository.saveAndFlush(membership);
        internalPost("/internal/jt-gateway/authentications/verify", """
                {"terminalId":"%s","tokenVersion":1,"tokenSha256":"%s",
                 "gatewayInstance":"gateway-a"}
                """.formatted(recorder.getId(), TOKEN_HASH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approved").value(false));
        assertThat(terminalRepository.findById(recorder.getId()).orElseThrow()
                .getLastAuthenticatedAt()).isNull();
    }

    @Test
    void recordsTheSuccessfulAuthenticationTime() throws Exception {
        JtTerminal terminal = registerBindAndActivate("T-AUTH-TIME", "PHONE-AUTH-TIME");
        Instant beforeAuthentication = terminalClock.instant();

        internalPost("/internal/jt-gateway/authentications/verify", """
                {"terminalId":"%s","tokenVersion":1,"tokenSha256":"%s","gatewayInstance":"gateway-a"}
                """.formatted(terminal.getId(), TOKEN_HASH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approved").value(true));
        Instant afterAuthentication = terminalClock.instant();

        assertThat(terminalRepository.findById(terminal.getId()).orElseThrow().getLastAuthenticatedAt())
                .satisfies(authenticatedAt -> assertThat(authenticatedAt.toInstant())
                        .isAfterOrEqualTo(beforeAuthentication)
                        .isBeforeOrEqualTo(afterAuthentication));
    }

    @Test
    void leavesAuthenticationTimeEmptyWhenTheTokenIsRejected() throws Exception {
        JtTerminal terminal = registerBindAndActivate("T-AUTH-REJECT", "PHONE-AUTH-REJECT");

        internalPost("/internal/jt-gateway/authentications/verify", """
                {"terminalId":"%s","tokenVersion":1,"tokenSha256":"%s","gatewayInstance":"gateway-a"}
                """.formatted(terminal.getId(), sha256(UUID.randomUUID().toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approved").value(false));

        assertThat(terminalRepository.findById(terminal.getId()).orElseThrow().getLastAuthenticatedAt())
                .isNull();
    }

    @Test
    void acceptsThenReplaysTheSameGatewayAuditIdempotencyKeyWithoutEchoingTerminalIdentity() throws Exception {
        JtTerminal terminal = presetAndBind("T-API-003", "PHONE-9003");
        UUID idempotencyKey = UUID.randomUUID();
        internalPost("/internal/jt-gateway/audit-events", """
                {"idempotencyKey":"%s","terminalId":"%s","vehicleId":"%s","eventType":"ONLINE","result":"APPLIED",
                 "reasonCode":"SESSION_ESTABLISHED","protocolVersion":"JT808_2019",
                 "occurredAt":"2026-08-12T08:00:00Z","gatewayInstance":"gateway-a"}
                """.formatted(idempotencyKey, terminal.getId(), VEHICLE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.idempotencyKey").value(idempotencyKey.toString()))
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"));
        internalPost("/internal/jt-gateway/audit-events", """
                {"idempotencyKey":"%s","terminalId":"%s","vehicleId":"%s","eventType":"ONLINE","result":"APPLIED",
                 "reasonCode":"SESSION_ESTABLISHED","protocolVersion":"JT808_2019",
                 "occurredAt":"2026-08-12T08:00:00Z","gatewayInstance":"gateway-a"}
                """.formatted(idempotencyKey, terminal.getId(), VEHICLE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.idempotencyKey").value(idempotencyKey.toString()))
                .andExpect(jsonPath("$.data.status").value("REPLAYED"));

        assertThat(gatewayAuditRepository.findAll())
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getEventType()).isEqualTo(JtGatewayAuditEvent.EventType.ONLINE);
                    assertThat(event.getResult()).isEqualTo(JtGatewayAuditEvent.Result.APPLIED);
                    assertThat(event.getTerminalId()).isEqualTo(terminal.getId());
                });
    }

    @Test
    void requiresAnAuditIdempotencyKey() throws Exception {
        internalPost("/internal/jt-gateway/audit-events", """
                {"eventType":"OFFLINE","result":"APPLIED","reasonCode":"SOCKET_CLOSED",
                 "occurredAt":"2026-08-12T08:00:00Z","gatewayInstance":"gateway-a"}
                """).andExpect(status().isBadRequest());

        assertThat(gatewayAuditRepository.findAll()).isEmpty();
    }

    @Test
    void normalizesConcurrentAuditRetriesToOneAcceptedAndOneReplayedResponse() throws Exception {
        UUID idempotencyKey = UUID.randomUUID();
        String body = """
                {"idempotencyKey":"%s","eventType":"OFFLINE","result":"APPLIED",
                 "reasonCode":"SOCKET_CLOSED","occurredAt":"2026-08-12T08:00:00Z",
                 "gatewayInstance":"gateway-a"}
                """.formatted(idempotencyKey);
        java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
        try (java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.List<java.util.concurrent.Future<String>> responses = new java.util.ArrayList<>();
            for (int index = 0; index < 2; index++) {
                responses.add(workers.submit(() -> {
                    start.await();
                    return internalPost("/internal/jt-gateway/audit-events", body)
                            .andExpect(status().isOk())
                            .andReturn().getResponse().getContentAsString();
                }));
            }
            assertThat(responses.stream().map(future -> {
                try {
                    return (String) JsonPath.read(future.get(), "$.data.status");
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList()).containsExactlyInAnyOrder("ACCEPTED", "REPLAYED");
        }
        assertThat(gatewayAuditRepository.findAll()).hasSize(1);
    }

    @Test
    void returnsConflictForStaleVersionAndAcceptedWhenDisconnectIsPending() throws Exception {
        JtTerminal terminal = registerBindAndActivate("T-API-004", "PHONE-9004");

        mockMvc.perform(post("/api/terminals/T-API-004/suspend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(action(terminal.getVersion() - 1, "过期请求")))
                .andExpect(status().isConflict());

        controlClient.available = false;
        mockMvc.perform(post("/api/terminals/T-API-004/suspend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(action(terminal.getVersion(), "安全停用")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.code").value("DISCONNECT_PENDING_CONFIRMATION"));

        assertThat(terminalRepository.findByTerminalCode("T-API-004").orElseThrow().getStatus())
                .isEqualTo(JtTerminal.Status.SUSPENDED);
    }

    @Test
    void returnsConflictWhenActivationPreconditionsAreNotMet() throws Exception {
        mockMvc.perform(post("/api/terminals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(presetRequest("T-API-005", "PHONE-9005")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/terminals/T-API-005/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(action(0, "提前启用")))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectsActionWithoutExpectedVersion() throws Exception {
        mockMvc.perform(post("/api/terminals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(presetRequest("T-API-008", "PHONE-9008")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/terminals/T-API-008/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"缺少版本\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsOversizedPublicRequestFieldsAsBadRequest() throws Exception {
        String oversizedReason = "R".repeat(301);
        mockMvc.perform(post("/api/terminals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(presetRequest("T-SIZE-1", "P".repeat(31))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/terminals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(presetRequest("C".repeat(81), "PHONE-SIZE")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/terminals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(presetRequestWithIdentity("T-SIZE-2", "PHONE-SIZE-2",
                                "M".repeat(81), "MODEL-X", "JT808_2019", "reason")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/terminals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(presetRequestWithIdentity("T-SIZE-3", "PHONE-SIZE-3",
                                "MFG01", "X".repeat(121), "JT808_2019", "reason")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/terminals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(presetRequestWithIdentity("T-SIZE-4", "PHONE-SIZE-4",
                                "MFG01", "MODEL-X", "P".repeat(41), "reason")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/terminals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(presetRequestWithIdentity("T-SIZE-5", "PHONE-SIZE-5",
                                "MFG01", "MODEL-X", "JT808_2019", oversizedReason)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rotatesAuthenticationWithoutAcceptingBrowserSuppliedDigest() throws Exception {
        JtTerminal terminal = registerBindAndActivate("T-API-ROTATE", "PHONE-ROTATE");
        int oldTokenVersion = terminal.getAuthTokenVersion();
        String oldHash = terminal.getAuthTokenHash();

        mockMvc.perform(post("/api/terminals/T-API-ROTATE/rotate-auth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(action(terminal.getVersion(), "安全轮换")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.terminal.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.data.terminal.registrationCompleted").value(false))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(oldHash))));

        JtTerminal pending = terminalRepository.findByTerminalCode("T-API-ROTATE").orElseThrow();
        assertThat(pending.getAuthTokenVersion()).isEqualTo(oldTokenVersion + 1);
        assertThat(pending.getAuthTokenHash()).isNotEqualTo(oldHash);
        assertThat(serviceAuthentication(pending.getId(), oldTokenVersion, oldHash)).isFalse();

        String verification = internalPost("/internal/jt-gateway/registrations/verify", """
                {"terminalPhone":"PHONE-ROTATE","terminalCode":"T-API-ROTATE",
                 "manufacturerId":"MFG01","model":"MODEL-X","vehicleIdentifier":"浙A20001",
                 "protocolVersion":"JT808_2019"}
                """).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approved").value(true))
                .andExpect(jsonPath("$.data.tokenVersion").value(oldTokenVersion + 1))
                .andReturn().getResponse().getContentAsString();
        assertThat(verification).doesNotContain(oldHash);

        internalPost("/internal/jt-gateway/registrations/" + pending.getId() + "/complete", """
                {"tokenVersion":%d,"tokenSha256":"%s","gatewayInstance":"gateway-a"}
                """.formatted(oldTokenVersion + 1, TOKEN_HASH)).andExpect(status().isOk());
        pending = terminalRepository.findByTerminalCode("T-API-ROTATE").orElseThrow();
        mockMvc.perform(post("/api/terminals/T-API-ROTATE/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(action(pending.getVersion(), "恢复上线")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    void deniesTerminalReadToEveryNonAdminRole() throws Exception {
        for (RoleCode role : java.util.List.of(RoleCode.DISPATCHER, RoleCode.OPERATOR, RoleCode.AUDITOR)) {
            SimpleGrantedAuthority[] authorities = Permission.permissionsFor(java.util.Set.of(role)).stream()
                    .map(Permission::name)
                    .map(SimpleGrantedAuthority::new)
                    .toArray(SimpleGrantedAuthority[]::new);
            mockMvc.perform(get("/api/terminals").with(user(role.name()).authorities(authorities)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void detailHasNoCurrentBindingAfterRetireOrReplacement() throws Exception {
        JtTerminal retired = registerBindAndActivate("T-DETAIL-RETIRED", "PHONE-RETIRED");
        mockMvc.perform(post("/api/terminals/T-DETAIL-RETIRED/retire")
                        .contentType(MediaType.APPLICATION_JSON).content(action(retired.getVersion(), "设备退役")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/terminals/T-DETAIL-RETIRED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.currentBinding").doesNotExist());

        JtTerminal oldTerminal = registerBindAndActivate("T-DETAIL-OLD", "PHONE-OLD");
        mockMvc.perform(post("/api/terminals").contentType(MediaType.APPLICATION_JSON)
                        .content(presetRequest("T-DETAIL-NEW", "PHONE-NEW"))).andExpect(status().isCreated());
        JtTerminal replacement = preprovisionReplacement("T-DETAIL-NEW");
        mockMvc.perform(post("/api/terminals/T-DETAIL-OLD/replace").contentType(MediaType.APPLICATION_JSON).content("""
                {"replacementTerminalCode":"T-DETAIL-NEW","expectedVersion":%d,"replacementExpectedVersion":%d,"reason":"换机"}
                """.formatted(oldTerminal.getVersion(), replacement.getVersion()))) .andExpect(status().isOk());
        mockMvc.perform(get("/api/terminals/T-DETAIL-OLD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentBinding").doesNotExist())
                .andExpect(jsonPath("$.data.bindingHistory").isEmpty());
    }

    @Test
    void reportsOnlineBoundaryAndOnlyExposesOfflineTimeWhenOffline() throws Exception {
        JtTerminal terminal = presetAndBind("T-DETAIL-CLOCK", "PHONE-CLOCK");
        OffsetDateTime onlineSeenAt = OffsetDateTime.now(terminalClock).minusMinutes(2);
        org.springframework.test.util.ReflectionTestUtils.setField(
                terminal, "lastSeenAt", onlineSeenAt);
        terminalRepository.saveAndFlush(terminal);
        mockMvc.perform(get("/api/terminals/T-DETAIL-CLOCK"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.onlineStatus").value("ONLINE"))
                .andExpect(jsonPath("$.data.offlineAt").doesNotExist());
        terminal = terminalRepository.findByTerminalCode("T-DETAIL-CLOCK").orElseThrow();
        OffsetDateTime offlineSeenAt = OffsetDateTime.now(terminalClock)
                .minusMinutes(4)
                .truncatedTo(ChronoUnit.MICROS);
        org.springframework.test.util.ReflectionTestUtils.setField(
                terminal, "lastSeenAt", offlineSeenAt);
        terminalRepository.saveAndFlush(terminal);
        String offlineBody = mockMvc.perform(get("/api/terminals/T-DETAIL-CLOCK"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.onlineStatus").value("OFFLINE"))
                .andExpect(jsonPath("$.data.offlineAt").isString())
                .andReturn().getResponse().getContentAsString();
        String actualOfflineAt = JsonPath.read(offlineBody, "$.data.offlineAt");
        assertThat(OffsetDateTime.parse(actualOfflineAt).toInstant())
                .isEqualTo(offlineSeenAt.plusMinutes(3).toInstant());
    }

    @Test
    void deniesTerminalDetailAndManagementActionToNonAdminRoles() throws Exception {
        for (RoleCode role : java.util.List.of(RoleCode.DISPATCHER, RoleCode.OPERATOR, RoleCode.AUDITOR)) {
            SimpleGrantedAuthority[] authorities = Permission.permissionsFor(java.util.Set.of(role)).stream()
                    .map(Permission::name).map(SimpleGrantedAuthority::new).toArray(SimpleGrantedAuthority[]::new);
            mockMvc.perform(get("/api/terminals/anything").with(user(role.name()).authorities(authorities))).andExpect(status().isForbidden());
            mockMvc.perform(post("/api/terminals/anything/suspend").contentType(MediaType.APPLICATION_JSON)
                    .content(action(1, "未授权")).with(user(role.name()).authorities(authorities))).andExpect(status().isForbidden());
        }
    }

    @Test
    void deniesCapabilityManagementToEveryNonAdminRole() throws Exception {
        for (RoleCode role : java.util.List.of(RoleCode.DISPATCHER, RoleCode.OPERATOR, RoleCode.AUDITOR)) {
            SimpleGrantedAuthority[] authorities = Permission.permissionsFor(java.util.Set.of(role)).stream()
                    .map(Permission::name).map(SimpleGrantedAuthority::new).toArray(SimpleGrantedAuthority[]::new);
            mockMvc.perform(post("/api/terminals/anything/capabilities").contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"expectedVersion":1,"activeSafetyStandard":"T/JSATL12-2017",
                             "activeSafetyModules":["ADAS"],"jt1078Enabled":false,"reason":"权限校验"}
                            """)
                    .with(user(role.name()).authorities(authorities))).andExpect(status().isForbidden());
        }
    }

    @Test
    void returnsAcceptedForReplacementWhenOldTerminalDisconnectIsPending() throws Exception {
        JtTerminal oldTerminal = registerBindAndActivate("T-API-006", "PHONE-9006");
        mockMvc.perform(post("/api/terminals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(presetRequest("T-API-007", "PHONE-9007")))
                .andExpect(status().isCreated());
        JtTerminal replacement = preprovisionReplacement("T-API-007");
        controlClient.available = false;

        mockMvc.perform(post("/api/terminals/T-API-006/replace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"replacementTerminalCode":"T-API-007","expectedVersion":%d,
                                 "replacementExpectedVersion":%d,"reason":"设备换机"}
                                """.formatted(oldTerminal.getVersion(), replacement.getVersion())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.code").value("DISCONNECT_PENDING_CONFIRMATION"))
                .andExpect(jsonPath("$.data.terminal.status").value("PENDING"))
                .andExpect(jsonPath("$.data.terminal.registrationCompleted").value(false));

        assertThat(terminalRepository.findByTerminalCode("T-API-006").orElseThrow().getStatus())
                .isEqualTo(JtTerminal.Status.RETIRED);
    }

    @Test
    void rejectsUnpreparedReplacementWithoutMutation() throws Exception {
        JtTerminal oldTerminal = registerBindAndActivate(
                "T-API-UNPREPARED-OLD", "PHONE-UNPREPARED-OLD");
        mockMvc.perform(post("/api/terminals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(presetRequest(
                                "T-API-UNPREPARED-NEW", "PHONE-UNPREPARED-NEW")))
                .andExpect(status().isCreated());
        JtTerminal replacement = terminalRepository
                .findByTerminalCode("T-API-UNPREPARED-NEW").orElseThrow();
        int auditCount = auditLogRepository.findAll().size();

        mockMvc.perform(post("/api/terminals/T-API-UNPREPARED-OLD/replace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"replacementTerminalCode":"T-API-UNPREPARED-NEW",
                                 "expectedVersion":%d,"replacementExpectedVersion":%d,
                                 "reason":"未预置换机"}
                                """.formatted(
                                oldTerminal.getVersion(), replacement.getVersion())))
                .andExpect(status().isConflict());

        assertThat(terminalRepository.findById(oldTerminal.getId()).orElseThrow())
                .satisfies(terminal -> {
                    assertThat(terminal.getStatus()).isEqualTo(JtTerminal.Status.ACTIVE);
                    assertThat(terminal.getVersion()).isEqualTo(oldTerminal.getVersion());
                });
        assertThat(terminalRepository.findById(replacement.getId()).orElseThrow())
                .satisfies(terminal -> {
                    assertThat(terminal.getStatus()).isEqualTo(JtTerminal.Status.PENDING);
                    assertThat(terminal.getVersion()).isEqualTo(replacement.getVersion());
                });
        assertThat(onboardMembershipRepository.findActiveByTerminalId(oldTerminal.getId()))
                .isPresent();
        assertThat(onboardMembershipRepository.findActiveByTerminalId(replacement.getId()))
                .isEmpty();
        assertThat(bindingRepository.findAll()).isEmpty();
        assertThat(auditLogRepository.findAll()).hasSize(auditCount);
        assertThat(controlClient.requests).isEmpty();
    }

    @Test
    void controlClientSendsOnlyInternalIdReasonAndIndependentControlCredential() {
        java.util.concurrent.atomic.AtomicReference<ClientRequest> captured = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> capturedBody = new java.util.concurrent.atomic.AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            MockClientHttpRequest outbound = new MockClientHttpRequest(request.method(), request.url());
            request.body().insert(outbound, new BodyInserter.Context() {
                @Override
                public java.util.List<org.springframework.http.codec.HttpMessageWriter<?>> messageWriters() {
                    return ExchangeStrategies.withDefaults().messageWriters();
                }

                @Override
                public java.util.Optional<org.springframework.http.server.reactive.ServerHttpRequest> serverRequest() {
                    return java.util.Optional.empty();
                }

                @Override
                public java.util.Map<String, Object> hints() {
                    return java.util.Map.of();
                }
            }).block();
            capturedBody.set(outbound.getBodyAsString().block());
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        });
        String controlCredential = UUID.randomUUID().toString();
        JtGatewayControlClient client = new JtGatewayControlClient.Http(
                builder, "http://gateway.invalid", controlCredential, "11");
        UUID terminalId = UUID.fromString("99999999-9999-9999-9999-999999999999");

        assertThat(client.disconnect(terminalId, "TERMINAL_SUSPENDED")).isTrue();
        assertThat(captured.get().method()).isEqualTo(HttpMethod.POST);
        assertThat(captured.get().url().getPath())
                .isEqualTo("/internal/control/terminals/" + terminalId + "/disconnect");
        assertThat(captured.get().headers().getFirst("Authorization")).isEqualTo("Bearer " + controlCredential);
        assertThat(captured.get().headers().getFirst("X-Control-Credential-Version")).isEqualTo("11");
        assertThat(capturedBody.get()).isEqualTo("{\"reasonCode\":\"TERMINAL_SUSPENDED\"}");
        assertThat(capturedBody.get()).doesNotContain(terminalId.toString());

        JtGatewayControlClient unconfigured = new JtGatewayControlClient.Http(builder, "", "", "");
        assertThat(unconfigured.disconnect(terminalId, "TERMINAL_SUSPENDED")).isFalse();
    }

    private JtTerminal presetAndBind(String code, String phone) throws Exception {
        mockMvc.perform(post("/api/terminals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(presetRequest(code, phone)))
                .andExpect(status().isCreated());
        JtTerminal terminal = terminalRepository.findByTerminalCode(code).orElseThrow();
        mockMvc.perform(post("/api/terminals/" + code + "/bind")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vehicleId":"%s","expectedVersion":%d,"reason":"首配车辆"}
                                """.formatted(VEHICLE_ID, terminal.getVersion())))
                .andExpect(status().isOk());
        return terminalRepository.findByTerminalCode(code).orElseThrow();
    }

    private JtTerminal registerBindAndActivate(String code, String phone) throws Exception {
        JtTerminal terminal = presetAndBind(code, phone);
        internalPost("/internal/jt-gateway/registrations/" + terminal.getId() + "/complete", """
                {"tokenVersion":1,"tokenSha256":"%s","gatewayInstance":"gateway-a"}
                """.formatted(TOKEN_HASH)).andExpect(status().isOk());
        terminal = terminalRepository.findByTerminalCode(code).orElseThrow();
        mockMvc.perform(post("/api/terminals/" + code + "/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(action(terminal.getVersion(), "正式启用")))
                .andExpect(status().isOk());
        return terminalRepository.findByTerminalCode(code).orElseThrow();
    }

    private JtTerminal preprovisionReplacement(String code) {
        JtTerminal replacement = terminalRepository.findByTerminalCode(code).orElseThrow();
        service.bind(
                code, VEHICLE_ID, replacement.getVersion(),
                "预置同系统 replacement",
                UUID.fromString("11111111-1111-1111-1111-111111111111"));
        replacement = terminalRepository.findByTerminalCode(code).orElseThrow();
        onboardProfileRepository.saveAndFlush(OnboardDeviceProtocolProfile.activate(
                replacement.getId(),
                OnboardDeviceProtocolProfile.TransportProfile.JT808_2019,
                OnboardDeviceProtocolProfile.BusinessProfile.GBT28787_2023,
                OnboardDeviceProtocolProfile.SafetyProfile.NONE,
                OnboardDeviceProtocolProfile.MediaProfile.NONE,
                30, 60, "replacement 自有协议档案",
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                OffsetDateTime.now()));
        onboardFixtures.verifyDispatchAndLocation(code);
        assertThat(bindingRepository.findAll()).isEmpty();
        return terminalRepository.findByTerminalCode(code).orElseThrow();
    }

    private org.springframework.test.web.servlet.ResultActions internalPost(String path, String body) throws Exception {
        return mockMvc.perform(post(path)
                .header("Authorization", "Bearer " + SERVICE_CREDENTIAL)
                .header("X-Service-Credential-Version", "7")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String presetRequest(String code, String phone) {
        return presetRequestWithIdentity(code, phone, "MFG01", "MODEL-X", "JT808_2019", "设备预置");
    }

    private String presetRequestWithIdentity(
            String code, String phone, String manufacturer, String model, String protocol, String reason) {
        return """
                {"terminalPhone":"%s","terminalCode":"%s","manufacturerId":"%s",
                 "model":"%s","protocolVersion":"%s","sourceCoordinateSystem":"GCJ02",
                 "reason":"%s"}
                """.formatted(phone, code, manufacturer, model, protocol, reason);
    }

    private boolean serviceAuthentication(UUID terminalId, int version, String hash) {
        return service.verifyAuthentication(terminalId, version, hash, "gateway-a").approved();
    }

    private String action(long version, String reason) {
        return "{\"expectedVersion\":" + version + ",\"reason\":\"" + reason + "\"}";
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @TestConfiguration
    static class ControlClientConfiguration {
        @Bean
        @Primary
        FakeControlClient fakeControlClient() {
            return new FakeControlClient();
        }

        @Bean
        Clock terminalClock() {
            return Clock.offset(Clock.systemUTC(), Duration.ofMinutes(5));
        }
    }

    static final class FakeControlClient implements JtGatewayControlClient {
        boolean available = true;
        final ArrayList<String> requests = new ArrayList<>();

        @Override
        public boolean disconnect(UUID terminalId, String reasonCode) {
            requests.add(reasonCode);
            return available;
        }
    }
}
