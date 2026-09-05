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
import com.idavy.drtops.domain.onboard.OnboardSystemConfigurationService.DeviceView;
import com.idavy.drtops.domain.onboard.OnboardSystemConfigurationService.OnboardSystemView;
import com.idavy.drtops.domain.onboard.OnboardSystemConfigurationService.ProtocolProfiles;
import com.idavy.drtops.domain.terminal.JtTerminal;
import com.idavy.drtops.domain.terminal.JtTerminalRepository;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.persistence.EntityManager;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
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
@Import({OnboardTestFixtures.class, OnboardSystemApiTest.DetailQueryProbeConfiguration.class})
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
    OnboardSystemRuntimeStateRepository runtimeStateRepository;

    @Autowired
    JtTerminalRepository terminalRepository;

    @Autowired
    DetailQueryProbe detailQueryProbe;

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
                        "SYNTH", "SYNTHETIC", "synthetic-evidence",
                        "gatewayInstance", "connectionId", "leaseGeneration");

        String detailBody = mockMvc.perform(get(
                            "/api/onboard-systems/{vehicleId}", system.getVehicleId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(alias)))
                .andExpect(content().string(not(containsString(dispatch.getId().toString()))))
                .andReturn().getResponse().getContentAsString();
        assertThat(detailBody).doesNotContain(
                "gatewayInstance", "connectionId", "leaseGeneration");

        mockMvc.perform(get("/api/onboard-systems")
                        .with(user(ACTOR).authorities(new SimpleGrantedAuthority("TERMINAL_MANAGE"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void detailExposesTaskSevenReadinessRuntimeSourceWanAndOnlySafeDeviceFacts() throws Exception {
        // Mutations caught: omitting Task 7 readiness, guessing the active source from
        // LOCATION_PRIMARY, guessing WAN from network mode, or leaking raw terminal identity.
        JtTerminal dispatch = fixtures.terminal("dispatch-01");
        JtTerminal recorder = fixtures.terminal("recorder-01");
        OnboardSystemRuntimeState runtime = runtimeStateRepository.findById(system.getId()).orElseThrow();
        runtime.selectLocationSource(recorder.getId(), runtime.getUpdatedAt().plusNanos(1));
        runtimeStateRepository.saveAndFlush(runtime);
        entityManager.clear();

        String detailBody = mockMvc.perform(get(
                            "/api/onboard-systems/{vehicleId}", system.getVehicleId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.readiness.connectivity").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.readiness.dispatch").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.readiness.location").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.readiness.activeSafety").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.readiness.video").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.readiness.dispatchEligible").value(false))
                .andExpect(jsonPath("$.data.readiness.overallStatus").value("OFFLINE"))
                .andExpect(jsonPath("$.data.devices[*].terminalStatus")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("PENDING"))))
                .andExpect(jsonPath("$.data.devices[*].authenticationPresent")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(false))))
                .andExpect(content().string(containsString("\"lastRegisteredAt\":null")))
                .andExpect(content().string(containsString("\"lastAuthenticatedAt\":null")))
                .andExpect(content().string(containsString("\"lastSeenAt\":null")))
                .andReturn().getResponse().getContentAsString();

        String dispatchAlias = aliasForRole(detailBody, "WAN_UPLINK");
        String recorderAlias = aliasForRole(detailBody, "LOCATION_BACKUP");
        assertThat(JsonPath.<String>read(detailBody, "$.data.activeLocationDeviceAlias"))
                .isEqualTo(recorderAlias)
                .isNotEqualTo(dispatchAlias);
        assertThat(JsonPath.<String>read(detailBody, "$.data.wanDeviceAlias"))
                .isEqualTo(dispatchAlias);

        String listBody = mockMvc.perform(get("/api/onboard-systems"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(listBody, "$.data.items[0].activeLocationDeviceAlias"))
                .isEqualTo(recorderAlias);
        assertThat(JsonPath.<String>read(listBody, "$.data.items[0].wanDeviceAlias"))
                .isEqualTo(dispatchAlias);
        assertThat(detailBody)
                .doesNotContain(
                        dispatch.getId().toString(), recorder.getId().toString(),
                        dispatch.getTerminalCode(), recorder.getTerminalCode(),
                        dispatch.getTerminalPhone(), recorder.getTerminalPhone(),
                        dispatch.getAuthTokenHash(), recorder.getAuthTokenHash(),
                        "evidenceRef", "terminalId", "terminalCode", "terminalPhone",
                        "authToken", "ipAddress", "simNumber");
    }

    @Test
    void maskedAliasesPreviewAndApplyWithoutSendingRawTerminalCodes() throws Exception {
        // Mutation caught: keeping terminalCode as the only desired-device selector or
        // resolving an alias without applying the selected current member configuration.
        String detailBody = mockMvc.perform(get(
                            "/api/onboard-systems/{vehicleId}", system.getVehicleId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String dispatchAlias = aliasForRole(detailBody, "DISPATCH");
        String recorderAlias = aliasForRole(detailBody, "ACTIVE_SAFETY");
        String request = aliasCommandJson(
                currentVersion, OperatingMode.SAFETY_MONITOR_ONLY,
                dispatchAlias, recorderAlias);

        mockMvc.perform(post("/api/onboard-systems/{vehicleId}/configuration/preview",
                            system.getVehicleId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentVersion").value(currentVersion))
                .andExpect(jsonPath("$.data.changedFields[0]").value("operatingMode"));

        String appliedBody = mockMvc.perform(post(
                            "/api/onboard-systems/{vehicleId}/configuration", system.getVehicleId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentVersion").value(currentVersion + 1))
                .andReturn().getResponse().getContentAsString();
        assertThat(appliedBody).doesNotContain(
                "dispatch-01", "recorder-01", "PHONE-DISPATCH", "PHONE-RECORDER");
    }

    @Test
    void maskedSelectorFailsClosedForMissingMixedDuplicateAndForeignAliases() throws Exception {
        // Mutations caught: accepting zero/two selectors, alias reuse, or resolving an alias
        // outside the locked system's current active membership (which could add a device).
        String detailBody = mockMvc.perform(get(
                            "/api/onboard-systems/{vehicleId}", system.getVehicleId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String dispatchAlias = aliasForRole(detailBody, "DISPATCH");
        String recorderAlias = aliasForRole(detailBody, "ACTIVE_SAFETY");
        int membershipCount = membershipRepository
                .findActiveByOnboardSystemIdOrderByValidFromAsc(system.getId()).size();

        String missing = aliasCommandJson(
                currentVersion, OperatingMode.DISPATCH_SERVICE,
                dispatchAlias, recorderAlias).replace("\"deviceAlias\":\"" + dispatchAlias + "\",", "");
        mockMvc.perform(post("/api/onboard-systems/{vehicleId}/configuration/preview",
                            system.getVehicleId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missing))
                .andExpect(status().isBadRequest());

        String mixed = aliasCommandJson(
                currentVersion, OperatingMode.DISPATCH_SERVICE,
                dispatchAlias, recorderAlias).replace(
                        "\"deviceAlias\":\"" + dispatchAlias + "\",",
                        "\"terminalCode\":\"dispatch-01\",\"deviceAlias\":\""
                                + dispatchAlias + "\",");
        mockMvc.perform(post("/api/onboard-systems/{vehicleId}/configuration/preview",
                            system.getVehicleId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mixed))
                .andExpect(status().isBadRequest());

        String duplicate = aliasCommandJson(
                currentVersion, OperatingMode.DISPATCH_SERVICE,
                dispatchAlias, dispatchAlias);
        mockMvc.perform(post("/api/onboard-systems/{vehicleId}/configuration/preview",
                            system.getVehicleId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(duplicate))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("DUPLICATE_DEVICE_ALIAS")));

        UUID foreignVehicleId = fixtures.recorderOnlyVehicleId();
        String foreignBody = mockMvc.perform(get(
                            "/api/onboard-systems/{vehicleId}", foreignVehicleId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String foreignAlias = JsonPath.read(foreignBody, "$.data.devices[0].deviceAlias");
        String foreign = aliasCommandJson(
                currentVersion, OperatingMode.DISPATCH_SERVICE,
                foreignAlias, recorderAlias);
        String foreignResponse = mockMvc.perform(post(
                            "/api/onboard-systems/{vehicleId}/configuration", system.getVehicleId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(foreign))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("DEVICE_ALIAS_CHANGED")))
                .andReturn().getResponse().getContentAsString();
        assertThat(foreignResponse).doesNotContain(
                "dispatch-01", "recorder-01", "PHONE-DISPATCH", "PHONE-RECORDER",
                fixtures.terminal("dispatch-01").getId().toString(),
                fixtures.terminal("recorder-01").getId().toString());
        assertThat(systemRepository.findById(system.getId()).orElseThrow().getVersion())
                .isEqualTo(currentVersion);
        assertThat(membershipRepository.findActiveByOnboardSystemIdOrderByValidFromAsc(
                system.getId())).hasSize(membershipCount);
    }

    @Test
    void staleAliasPreviewAndApplyFailClosedWithoutAdditionalMutation() throws Exception {
        // Mutations caught: resolving an alias from membership history, allowing an old alias
        // to re-add a removed terminal, or writing version/audit data before stale rejection.
        String detailBody = mockMvc.perform(get(
                            "/api/onboard-systems/{vehicleId}", system.getVehicleId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String dispatchAlias = aliasForRole(detailBody, "DISPATCH");
        String staleRecorderAlias = aliasForRole(detailBody, "ACTIVE_SAFETY");
        JtTerminal recorder = fixtures.terminal("recorder-01");
        OnboardDeviceMembership recorderMembership = membershipRepository
                .findActiveByTerminalId(recorder.getId()).orElseThrow();
        recorderMembership.remove(
                "replace current member before stale alias request",
                OnboardTestFixtures.ACTOR_ID,
                recorderMembership.getUpdatedAt().plusNanos(1));
        membershipRepository.saveAndFlush(recorderMembership);
        entityManager.clear();
        long versionBeforeRequests = systemRepository.findById(system.getId()).orElseThrow().getVersion();
        long auditCountBeforeRequests = auditLogRepository.count();
        int activeMembershipsBeforeRequests = membershipRepository
                .findActiveByOnboardSystemIdOrderByValidFromAsc(system.getId()).size();
        String staleRequest = aliasCommandJson(
                currentVersion, OperatingMode.DISPATCH_SERVICE,
                dispatchAlias, staleRecorderAlias);

        String previewResponse = mockMvc.perform(post(
                            "/api/onboard-systems/{vehicleId}/configuration/preview",
                            system.getVehicleId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(staleRequest))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("DEVICE_ALIAS_CHANGED")))
                .andReturn().getResponse().getContentAsString();
        String applyResponse = mockMvc.perform(post(
                            "/api/onboard-systems/{vehicleId}/configuration",
                            system.getVehicleId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(staleRequest))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("DEVICE_ALIAS_CHANGED")))
                .andReturn().getResponse().getContentAsString();

        assertThat(previewResponse + applyResponse).doesNotContain(
                recorder.getId().toString(), recorder.getTerminalCode(),
                recorder.getTerminalPhone(), recorder.getAuthTokenHash());
        assertThat(systemRepository.findById(system.getId()).orElseThrow().getVersion())
                .isEqualTo(versionBeforeRequests);
        assertThat(auditLogRepository.count()).isEqualTo(auditCountBeforeRequests);
        assertThat(membershipRepository.findActiveByOnboardSystemIdOrderByValidFromAsc(
                system.getId())).hasSize(activeMembershipsBeforeRequests);
    }

    @Test
    void rawCompatibilityCannotIntroduceAnAliasCollisionAndLeavesNoMutation() throws Exception {
        // Mutation caught: checking alias uniqueness only among current memberships and
        // allowing a raw terminalCode target to introduce an ambiguous masked alias.
        JtTerminal collidingDispatch = terminalRepository.saveAndFlush(JtTerminal.preset(
                UUID.fromString("00000000-0000-0000-0000-000001293bc1"),
                "PHONE-COLLISION-DISPATCH", "collision-dispatch",
                "SYNTH", "COLLISION", "JT808_2019", "WGS84",
                OnboardTestFixtures.ACTOR_ID));
        JtTerminal collidingRecorder = terminalRepository.saveAndFlush(JtTerminal.preset(
                UUID.fromString("00000000-0000-0000-0000-000001567b14"),
                "PHONE-COLLISION-RECORDER", "collision-recorder",
                "SYNTH", "COLLISION", "JT808_2019", "WGS84",
                OnboardTestFixtures.ACTOR_ID));
        fixtures.verifyDispatchAndLocation("collision-dispatch");
        fixtures.verifySafetyVideoAndLocation("collision-recorder");
        assertThat(OnboardSystemConfigurationService.safeDeviceAlias(collidingDispatch.getId()))
                .isEqualTo("device-2f8663173468")
                .isEqualTo(OnboardSystemConfigurationService.safeDeviceAlias(
                        collidingRecorder.getId()));
        Set<UUID> membershipIdsBefore = membershipRepository
                .findActiveByOnboardSystemIdOrderByValidFromAsc(system.getId()).stream()
                .map(OnboardDeviceMembership::getTerminalId)
                .collect(java.util.stream.Collectors.toSet());
        long auditCountBefore = auditLogRepository.count();

        String response = mockMvc.perform(post(
                            "/api/onboard-systems/{vehicleId}/configuration",
                            system.getVehicleId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandJson(currentVersion, OperatingMode.DISPATCH_SERVICE)
                                .replace("dispatch-01", "collision-dispatch")
                                .replace("recorder-01", "collision-recorder")))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("DUPLICATE_DEVICE_ALIAS")))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(
                collidingDispatch.getId().toString(), collidingRecorder.getId().toString(),
                collidingDispatch.getTerminalCode(), collidingRecorder.getTerminalCode(),
                collidingDispatch.getTerminalPhone(), collidingRecorder.getTerminalPhone());

        assertThat(systemRepository.findById(system.getId()).orElseThrow().getVersion())
                .isEqualTo(currentVersion);
        assertThat(membershipRepository.findActiveByOnboardSystemIdOrderByValidFromAsc(
                system.getId()).stream().map(OnboardDeviceMembership::getTerminalId))
                .containsExactlyInAnyOrderElementsOf(membershipIdsBefore);
        assertThat(auditLogRepository.count()).isEqualTo(auditCountBefore);
    }

    @Test
    void detailNeverCombinesConfigurationAndReadinessFromDifferentVersions() throws Exception {
        // Mutation caught: reading the masked configuration in one transaction and Task 7
        // readiness in a later transaction, permitting an old config/new readiness response.
        UUID vehicleId = fixtures.readyDispatchSystemVehicleId();
        OnboardSystem targetSystem = systemRepository.findActiveByVehicleId(vehicleId).orElseThrow();
        long oldVersion = targetSystem.getVersion();
        OnboardSystemView oldView = service.getSystem(vehicleId);
        DeviceView deviceView = oldView.devices().getFirst();
        UUID terminalId = membershipRepository
                .findActiveByOnboardSystemIdOrderByValidFromAsc(targetSystem.getId())
                .getFirst().getTerminalId();
        JtTerminal terminal = terminalRepository.findById(terminalId).orElseThrow();
        ConfigurationCommand nextConfiguration = new ConfigurationCommand(
                oldVersion,
                OperatingMode.SAFETY_MONITOR_ONLY,
                List.of(new DeviceConfiguration(
                        terminal.getTerminalCode(), deviceView.networkMode(),
                        deviceView.roles().stream().map(Role::valueOf)
                                .collect(java.util.stream.Collectors.toSet()),
                        deviceView.protocolProfiles())),
                "switch mode while detail waits for one snapshot");

        CountDownLatch systemLocked = new CountDownLatch(1);
        CountDownLatch configurationRead = detailQueryProbe.arm();
        CountDownLatch allowWriter = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = executor.submit(() -> {
                TransactionTemplate transaction = new TransactionTemplate(transactionManager);
                transaction.executeWithoutResult(status -> {
                    systemRepository.findLockedById(targetSystem.getId()).orElseThrow();
                    systemLocked.countDown();
                    await(allowWriter);
                    service.apply(vehicleId, nextConfiguration, OnboardTestFixtures.ACTOR_ID);
                });
            });
            assertThat(systemLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<org.springframework.test.web.servlet.MvcResult> detailRequest = executor.submit(() ->
                    mockMvc.perform(get("/api/onboard-systems/{vehicleId}", vehicleId)
                                    .with(user(ACTOR).authorities(
                                            new SimpleGrantedAuthority("TERMINAL_READ"))))
                            .andExpect(status().isOk())
                            .andReturn());
            assertThat(configurationRead.await(1, TimeUnit.SECONDS))
                    .as("detail configuration query must not pass the writer's system lock")
                    .isFalse();
            allowWriter.countDown();
            writer.get(5, TimeUnit.SECONDS);
            assertThat(configurationRead.await(5, TimeUnit.SECONDS))
                    .as("detail configuration query probe must fire after the writer releases")
                    .isTrue();
            String response;
            try {
                response = detailRequest.get(5, TimeUnit.SECONDS)
                        .getResponse().getContentAsString();
            } catch (java.util.concurrent.ExecutionException mixedSnapshotFailure) {
                assertThat(mixedSnapshotFailure.getCause())
                        .as("detail must successfully return one system/runtime locked snapshot")
                        .isNull();
                return;
            }

            long responseVersion = ((Number) JsonPath.read(
                    response, "$.data.version")).longValue();
            String responseMode = JsonPath.read(response, "$.data.operatingMode");
            boolean dispatchEligible = JsonPath.read(
                    response, "$.data.readiness.dispatchEligible");
            String dispatchReadiness = JsonPath.read(response, "$.data.readiness.dispatch");
            assertThat(responseVersion).isEqualTo(oldVersion + 1);
            assertThat(responseMode).isEqualTo("SAFETY_MONITOR_ONLY");
            assertThat(dispatchEligible).isFalse();
            assertThat(dispatchReadiness).isEqualTo("NOT_INSTALLED");
        } finally {
            allowWriter.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
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
                new ProtocolProfiles(
                        "JT808_2019",
                        roles.contains(Role.DISPATCH) ? "GBT28787_2023" : "NONE",
                        roles.contains(Role.ACTIVE_SAFETY) ? "JSATL12_2017" : "NONE",
                        roles.contains(Role.VIDEO) ? "JT1078_2016" : "NONE",
                        30, 60));
    }

    private static String commandJson(long version, OperatingMode operatingMode) {
        return """
                {"expectedVersion":%d,"operatingMode":"%s","reason":"safe API change",
                 "devices":[
                   {"terminalCode":"dispatch-01","networkMode":"DIRECT_CELLULAR",
                    "roles":["DISPATCH","LOCATION_PRIMARY","WAN_UPLINK"],
                    "protocolProfiles":{"transportProfile":"JT808_2019","businessProfile":"GBT28787_2023",
                      "safetyProfile":"NONE","mediaProfile":"NONE",
                      "activePositionIntervalSeconds":30,"idlePositionIntervalSeconds":60}},
                   {"terminalCode":"recorder-01","networkMode":"SHARED_LAN_CLIENT",
                    "roles":["LOCATION_BACKUP","ACTIVE_SAFETY","VIDEO"],
                    "protocolProfiles":{"transportProfile":"JT808_2019","businessProfile":"NONE",
                      "safetyProfile":"JSATL12_2017","mediaProfile":"JT1078_2016",
                      "activePositionIntervalSeconds":30,"idlePositionIntervalSeconds":60}}]}
                """.formatted(version, operatingMode.name());
    }

    private static String aliasCommandJson(
            long version,
            OperatingMode operatingMode,
            String dispatchAlias,
            String recorderAlias) {
        return """
                {"expectedVersion":%d,"operatingMode":"%s","reason":"safe alias API change",
                 "devices":[
                   {"deviceAlias":"%s","networkMode":"DIRECT_CELLULAR",
                    "roles":["DISPATCH","LOCATION_PRIMARY","WAN_UPLINK"],
                    "protocolProfiles":{"transportProfile":"JT808_2019","businessProfile":"GBT28787_2023",
                      "safetyProfile":"NONE","mediaProfile":"NONE",
                      "activePositionIntervalSeconds":30,"idlePositionIntervalSeconds":60}},
                   {"deviceAlias":"%s","networkMode":"SHARED_LAN_CLIENT",
                    "roles":["LOCATION_BACKUP","ACTIVE_SAFETY","VIDEO"],
                    "protocolProfiles":{"transportProfile":"JT808_2019","businessProfile":"NONE",
                      "safetyProfile":"JSATL12_2017","mediaProfile":"JT1078_2016",
                      "activePositionIntervalSeconds":30,"idlePositionIntervalSeconds":60}}]}
                """.formatted(version, operatingMode.name(), dispatchAlias, recorderAlias);
    }

    @SuppressWarnings("unchecked")
    private static String aliasForRole(String responseBody, String role) {
        List<Map<String, Object>> devices = JsonPath.read(responseBody, "$.data.devices");
        return devices.stream()
                .filter(device -> ((List<String>) device.get("roles")).contains(role))
                .map(device -> (String) device.get("deviceAlias"))
                .findFirst()
                .orElseThrow();
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

    @TestConfiguration(proxyBeanMethods = false)
    static class DetailQueryProbeConfiguration {

        @Bean
        DetailQueryProbe detailQueryProbe() {
            return new DetailQueryProbe();
        }

        @Bean
        HibernatePropertiesCustomizer detailQueryProbeCustomizer(DetailQueryProbe probe) {
            return properties -> properties.put(
                    "hibernate.session_factory.statement_inspector", probe);
        }
    }

    static final class DetailQueryProbe implements StatementInspector {

        private final AtomicReference<CountDownLatch> armed = new AtomicReference<>();

        CountDownLatch arm() {
            CountDownLatch latch = new CountDownLatch(1);
            if (!armed.compareAndSet(null, latch)) {
                throw new IllegalStateException("detail query probe is already armed");
            }
            return latch;
        }

        @Override
        public String inspect(String sql) {
            CountDownLatch latch = armed.get();
            if (latch != null) {
                String normalized = sql.toLowerCase(java.util.Locale.ROOT);
                if (normalized.contains("from onboard_device_memberships")
                        && normalized.contains("join jt_terminals")
                        && normalized.contains(" in (")
                        && !normalized.contains("for update")
                        && armed.compareAndSet(latch, null)) {
                    latch.countDown();
                }
            }
            return sql;
        }
    }
}
