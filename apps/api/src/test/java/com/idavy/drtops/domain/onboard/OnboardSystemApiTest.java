package com.idavy.drtops.domain.onboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.domain.onboard.OnboardDeviceMembership.NetworkMode;
import com.idavy.drtops.domain.onboard.OnboardDeviceRoleAssignment.Role;
import com.idavy.drtops.domain.onboard.OnboardSystem.OperatingMode;
import com.idavy.drtops.domain.onboard.OnboardSystemConfigurationService.ConfigurationCommand;
import com.idavy.drtops.domain.onboard.OnboardSystemConfigurationService.ConfigurationPreview;
import com.idavy.drtops.domain.onboard.OnboardSystemConfigurationService.DeviceConfiguration;
import com.idavy.drtops.domain.onboard.OnboardSystemConfigurationService.ProtocolProfiles;
import com.idavy.drtops.domain.terminal.JtTerminal;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:onboard_api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE, printOnlyOnFailure = false)
@WithMockUser(username = "11111111-1111-1111-1111-111111111111",
        authorities = {"TERMINAL_READ", "TERMINAL_MANAGE"})
@Import(OnboardTestFixtures.class)
class OnboardSystemApiTest {

    private static final String ACTOR = "11111111-1111-1111-1111-111111111111";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    OnboardSystemConfigurationService service;

    @Autowired
    OnboardTestFixtures fixtures;

    @Autowired
    AuditLogRepository auditLogRepository;

    @Autowired
    OnboardSystemRepository systemRepository;

    @Autowired
    OnboardDeviceMembershipRepository membershipRepository;

    @Autowired
    OnboardDeviceProtocolProfileRepository profileRepository;

    @Autowired
    OnboardDeviceRoleAssignmentRepository roleRepository;

    @Autowired
    EntityManager entityManager;

    @Autowired
    PlatformTransactionManager transactionManager;

    private OnboardSystem system;
    private ConfigurationCommand currentCommand;
    private long currentVersion;

    @BeforeEach
    void setUp() {
        fixtures.clear();
        system = fixtures.activeSystem(OperatingMode.DISPATCH_SERVICE);
        fixtures.verifyDispatchAndLocation("dispatch-01");
        fixtures.verifySafetyVideoAndLocation("recorder-01");
        currentCommand = command(system.getVersion(), OperatingMode.DISPATCH_SERVICE);
        ConfigurationPreview applied = service.apply(
                system.getVehicleId(), currentCommand, OnboardTestFixtures.ACTOR_ID);
        currentVersion = applied.currentVersion();
    }

    @Test
    void exposesBothReadEndpointsOnlyWithTerminalReadAndMasksEveryPhysicalIdentity() throws Exception {
        String listBody = mockMvc.perform(get("/api/onboard-systems"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].vehicleId").value(system.getVehicleId().toString()))
                .andExpect(jsonPath("$.data.items[0].devices.length()").value(2))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andReturn().getResponse().getContentAsString();

        String alias = JsonPath.read(listBody, "$.data.items[0].devices[0].deviceAlias");
        assertThat(alias).matches("device-[0-9a-f]{12}");
        JtTerminal dispatch = fixtures.terminal("dispatch-01");
        JtTerminal recorder = fixtures.terminal("recorder-01");
        assertThat(listBody)
                .doesNotContain(
                        dispatch.getId().toString(), recorder.getId().toString(),
                        dispatch.getTerminalCode(), recorder.getTerminalCode(),
                        dispatch.getTerminalPhone(), recorder.getTerminalPhone(),
                        "SYNTH", "SYNTHETIC", "synthetic-evidence");

        mockMvc.perform(get("/api/onboard-systems/{vehicleId}", system.getVehicleId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(alias)))
                .andExpect(content().string(not(containsString(dispatch.getId().toString()))));

        mockMvc.perform(get("/api/onboard-systems")
                        .with(user(ACTOR).authorities(new SimpleGrantedAuthority("TERMINAL_MANAGE"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void pagesActiveOnboardSystemsAndRejectsInvalidPageBounds() throws Exception {
        fixtures.clear();
        for (int index = 0; index < 5; index++) {
            fixtures.configureRecorderSystem("rec-p" + index, "PAGE-" + index);
        }

        mockMvc.perform(get("/api/onboard-systems")
                        .queryParam("page", "1")
                        .queryParam("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(5))
                .andExpect(jsonPath("$.data.totalPages").value(3));

        mockMvc.perform(get("/api/onboard-systems").queryParam("page", "-1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/onboard-systems").queryParam("size", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requiresTerminalManageForPreviewApplyAndCapabilityVerification() throws Exception {
        var reader = user(ACTOR).authorities(new SimpleGrantedAuthority("TERMINAL_READ"));
        String request = commandJson(currentVersion, OperatingMode.SAFETY_MONITOR_ONLY);

        mockMvc.perform(post("/api/onboard-systems/{vehicleId}/configuration/preview", system.getVehicleId())
                        .with(reader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/onboard-systems/{vehicleId}/configuration", system.getVehicleId())
                        .with(reader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/terminals/recorder-01/capability-verifications")
                        .with(reader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(capabilityJson("DMS", null, "evidence-secret")))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsMissingExpectedVersionAtZeroForPreviewWithoutWrites() throws Exception {
        assertVersionRequiredAtZero("/configuration/preview", false);
    }

    @Test
    void rejectsNullExpectedVersionAtZeroForPreviewWithoutWrites() throws Exception {
        assertVersionRequiredAtZero("/configuration/preview", true);
    }

    @Test
    void rejectsMissingExpectedVersionAtZeroForApplyWithoutWrites() throws Exception {
        assertVersionRequiredAtZero("/configuration", false);
    }

    @Test
    void rejectsNullExpectedVersionAtZeroForApplyWithoutWrites() throws Exception {
        assertVersionRequiredAtZero("/configuration", true);
    }

    @Test
    void previewsAndAppliesThroughOneContractAndReturnsSafeStaleConflict() throws Exception {
        String request = commandJson(currentVersion, OperatingMode.SAFETY_MONITOR_ONLY);

        mockMvc.perform(post("/api/onboard-systems/{vehicleId}/configuration/preview", system.getVehicleId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changedFields[0]").value("operatingMode"))
                .andExpect(jsonPath("$.data.currentVersion").value(currentVersion));

        String appliedBody = mockMvc.perform(post(
                            "/api/onboard-systems/{vehicleId}/configuration", system.getVehicleId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentVersion").value(currentVersion + 1))
                .andReturn().getResponse().getContentAsString();
        assertThat(appliedBody).doesNotContain("PHONE-DISPATCH", "PHONE-RECORDER");

        String stalePreviewBody = mockMvc.perform(post(
                            "/api/onboard-systems/{vehicleId}/configuration/preview",
                            system.getVehicleId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("STALE_CONFIGURATION_VERSION")))
                .andReturn().getResponse().getContentAsString();
        assertThat(stalePreviewBody).doesNotContain(
                "dispatch-01", "recorder-01", "PHONE-DISPATCH", "PHONE-RECORDER",
                fixtures.terminal("dispatch-01").getId().toString(),
                "constraint", "CONSTRAINT", "SQL", "23505");

        mockMvc.perform(post("/api/onboard-systems/{vehicleId}/configuration", system.getVehicleId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("STALE_CONFIGURATION_VERSION")))
                .andExpect(content().string(not(containsString("dispatch-01"))))
                .andExpect(content().string(not(containsString("recorder-01"))));
    }

    @Test
    void verifiesCapabilityWithoutReturningOrAuditingEvidenceAndKeepsVerifiedFactImmutable()
            throws Exception {
        String evidence = "TOP-SECRET-EVIDENCE-REF";
        String request = capabilityJson("DMS", null, evidence);

        String response = mockMvc.perform(post(
                            "/api/terminals/recorder-01/capability-verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deviceAlias").value(
                        org.hamcrest.Matchers.matchesPattern("device-[0-9a-f]{12}")))
                .andExpect(jsonPath("$.data.capability").value("DMS"))
                .andExpect(jsonPath("$.data.status").value("VERIFIED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(
                evidence, "recorder-01", "PHONE-RECORDER",
                fixtures.terminal("recorder-01").getId().toString());
        assertThat(auditLogRepository.findAll().stream()
                .filter(audit -> "DEVICE_CAPABILITY_VERIFIED".equals(audit.getAction()))
                .toList()).singleElement().satisfies(audit ->
                        assertThat(audit.getMetadataJson())
                                .contains("DMS", "VERIFIED")
                                .doesNotContain(evidence, "recorder-01", "PHONE-RECORDER"));

        mockMvc.perform(post("/api/terminals/recorder-01/capability-verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("CAPABILITY_ALREADY_VERIFIED")))
                .andExpect(content().string(not(containsString(evidence))));

        mockMvc.perform(post("/api/terminals/recorder-01/capability-verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(capabilityJson("NOT_A_CAPABILITY", null, evidence)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString(evidence))));
    }

    @Test
    void concurrentCapabilityVerificationReturnsOneSafeConflictWithoutSqlOrIdentity()
            throws Exception {
        JtTerminal recorder = fixtures.terminal("recorder-01");
        String evidence = "CONCURRENT-SECRET-EVIDENCE";
        String request = capabilityJson("DMS", null, evidence);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch terminalLocked = new CountDownLatch(1);
        CountDownLatch releaseTerminal = new CountDownLatch(1);
        try {
            Future<?> coordinator = holdTerminalRow(
                    executor, recorder.getId(), terminalLocked, releaseTerminal);
            assertThat(terminalLocked.await(5, TimeUnit.SECONDS)).isTrue();
            CountDownLatch workersStarted = new CountDownLatch(2);
            Future<org.springframework.test.web.servlet.MvcResult> first = executor.submit(() ->
                    {
                        workersStarted.countDown();
                        return capabilityRequest(request);
                    });
            Future<org.springframework.test.web.servlet.MvcResult> second = executor.submit(() ->
                    {
                        workersStarted.countDown();
                        return capabilityRequest(request);
                    });

            assertThat(workersStarted.await(5, TimeUnit.SECONDS)).isTrue();
            boolean firstCompletedWhileLocked = completedWithin(first, 750);
            boolean secondCompletedWhileLocked = completedWithin(second, 750);
            releaseTerminal.countDown();

            coordinator.get(5, TimeUnit.SECONDS);
            List<org.springframework.test.web.servlet.MvcResult> results = List.of(
                    first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertThat(firstCompletedWhileLocked).isFalse();
            assertThat(secondCompletedWhileLocked).isFalse();
            assertThat(results).extracting(result -> result.getResponse().getStatus())
                    .containsExactlyInAnyOrder(200, 409);
            String conflictBody = results.stream()
                    .filter(result -> result.getResponse().getStatus() == 409)
                    .findFirst().orElseThrow().getResponse().getContentAsString();
            assertThat(conflictBody)
                    .contains("CAPABILITY_ALREADY_VERIFIED")
                    .doesNotContain(
                            evidence, "recorder-01", "PHONE-RECORDER", recorder.getId().toString(),
                            "constraint", "CONSTRAINT", "SQL", "23505");
            assertThat(auditLogRepository.findAll().stream()
                    .filter(audit -> "DEVICE_CAPABILITY_VERIFIED".equals(audit.getAction())))
                    .hasSize(1);
        } finally {
            releaseTerminal.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static ConfigurationCommand command(long version, OperatingMode operatingMode) {
        return new ConfigurationCommand(version, operatingMode, List.of(
                device("dispatch-01", NetworkMode.DIRECT_CELLULAR,
                        Set.of(Role.DISPATCH, Role.LOCATION_PRIMARY, Role.WAN_UPLINK)),
                device("recorder-01", NetworkMode.SHARED_LAN_CLIENT,
                        Set.of(Role.LOCATION_BACKUP, Role.ACTIVE_SAFETY, Role.VIDEO))),
                "configure synthetic onboard system");
    }

    private void assertVersionRequiredAtZero(String endpointSuffix, boolean explicitNull)
            throws Exception {
        fixtures.clear();
        OnboardSystem zeroVersionSystem = fixtures.activeSystem(OperatingMode.DISPATCH_SERVICE);
        fixtures.verifyDispatchAndLocation("dispatch-01");
        fixtures.verifySafetyVideoAndLocation("recorder-01");
        String request = commandJson(0, OperatingMode.DISPATCH_SERVICE);
        request = explicitNull
                ? request.replace("\"expectedVersion\":0", "\"expectedVersion\":null")
                : request.replace("\"expectedVersion\":0,", "");

        var result = mockMvc.perform(post(
                            "/api/onboard-systems/{vehicleId}" + endpointSuffix,
                            zeroVersionSystem.getVehicleId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("dispatch-01", "recorder-01", "PHONE-DISPATCH", "PHONE-RECORDER");
        assertThat(systemRepository.findById(zeroVersionSystem.getId()).orElseThrow().getVersion())
                .isZero();
        assertThat(membershipRepository.findAll()).isEmpty();
        assertThat(profileRepository.findAll()).isEmpty();
        assertThat(roleRepository.findAll()).isEmpty();
        assertThat(auditLogRepository.findAll()).isEmpty();
    }

    private static DeviceConfiguration device(
            String terminalCode, NetworkMode networkMode, Set<Role> roles) {
        return new DeviceConfiguration(terminalCode, networkMode, roles,
                new ProtocolProfiles("JT808_2019", "NONE", "NONE", "NONE", 30, 60));
    }

    private static String commandJson(long version, OperatingMode operatingMode) {
        return """
                {"expectedVersion":%d,"operatingMode":"%s","reason":"safe API change",
                 "devices":[
                   {"terminalCode":"dispatch-01","networkMode":"DIRECT_CELLULAR",
                    "roles":["DISPATCH","LOCATION_PRIMARY","WAN_UPLINK"],
                    "protocolProfiles":{"transportProfile":"JT808_2019","businessProfile":"NONE",
                      "safetyProfile":"NONE","mediaProfile":"NONE",
                      "activePositionIntervalSeconds":30,"idlePositionIntervalSeconds":60}},
                   {"terminalCode":"recorder-01","networkMode":"SHARED_LAN_CLIENT",
                    "roles":["LOCATION_BACKUP","ACTIVE_SAFETY","VIDEO"],
                    "protocolProfiles":{"transportProfile":"JT808_2019","businessProfile":"NONE",
                      "safetyProfile":"NONE","mediaProfile":"NONE",
                      "activePositionIntervalSeconds":30,"idlePositionIntervalSeconds":60}}]}
                """.formatted(version, operatingMode.name());
    }

    private static String capabilityJson(
            String capability, Long expectedVersion, String evidenceRef) {
        String version = expectedVersion == null ? "null" : expectedVersion.toString();
        return """
                {"capability":"%s","expectedVersion":%s,
                 "reason":"verify synthetic capability","evidenceRef":"%s"}
                """.formatted(capability, version, evidenceRef);
    }

    private Future<?> holdTerminalRow(
            ExecutorService executor,
            UUID terminalId,
            CountDownLatch terminalLocked,
            CountDownLatch releaseTerminal) {
        return executor.submit(() -> {
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            transaction.executeWithoutResult(status -> {
                entityManager.createNativeQuery("""
                                select id from jt_terminals
                                where id = :terminalId
                                for update
                                """)
                        .setParameter("terminalId", terminalId)
                        .getSingleResult();
                terminalLocked.countDown();
                await(releaseTerminal);
            });
        });
    }

    private org.springframework.test.web.servlet.MvcResult capabilityRequest(String request)
            throws Exception {
        return mockMvc.perform(post("/api/terminals/recorder-01/capability-verifications")
                        .with(user(ACTOR).authorities(
                                new SimpleGrantedAuthority("TERMINAL_MANAGE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andReturn();
    }

    private static boolean completedWithin(Future<?> future, long milliseconds) throws Exception {
        try {
            future.get(milliseconds, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException expectedWait) {
            return false;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("API concurrency latch timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("API concurrency test interrupted", interrupted);
        }
    }
}
