package com.idavy.drtops.domain.onboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import com.idavy.drtops.domain.location.VehicleLocationEvent;
import com.idavy.drtops.domain.location.VehicleLocationEventRepository;
import com.idavy.drtops.domain.onboard.OnboardDeviceMembership.NetworkMode;
import com.idavy.drtops.domain.onboard.OnboardDeviceRoleAssignment.Role;
import com.idavy.drtops.domain.onboard.OnboardSystem.OperatingMode;
import com.idavy.drtops.domain.onboard.OnboardSystemConfigurationService.ConfigurationCommand;
import com.idavy.drtops.domain.onboard.OnboardSystemConfigurationService.ConfigurationPreview;
import com.idavy.drtops.domain.onboard.OnboardSystemConfigurationService.CapabilityVerificationCommand;
import com.idavy.drtops.domain.onboard.OnboardSystemConfigurationService.DeviceConfiguration;
import com.idavy.drtops.domain.onboard.OnboardSystemConfigurationService.OnboardSystemView;
import com.idavy.drtops.domain.onboard.OnboardSystemConfigurationService.ProtocolProfiles;
import com.idavy.drtops.domain.terminal.JtTerminal;
import com.idavy.drtops.domain.terminal.JtTerminalVehicleBinding;
import com.idavy.drtops.domain.terminal.JtTerminalVehicleBindingRepository;
import com.idavy.drtops.domain.terminal.TerminalManagementService;
import com.idavy.drtops.domain.terminal.TerminalConflictException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:onboard_configuration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import({OnboardTestFixtures.class,
        OnboardSystemConfigurationServiceTest.PreviewPauseConfiguration.class})
class OnboardSystemConfigurationServiceTest {

    @Autowired
    OnboardSystemConfigurationService service;

    @Autowired
    OnboardTestFixtures fixtures;

    @Autowired
    OnboardSystemRepository systemRepository;

    @Autowired
    OnboardDeviceMembershipRepository membershipRepository;

    @Autowired
    OnboardDeviceCapabilityRepository capabilityRepository;

    @Autowired
    OnboardDeviceProtocolProfileRepository profileRepository;

    @Autowired
    OnboardDeviceRoleAssignmentRepository roleRepository;

    @Autowired
    AuditLogRepository auditLogRepository;

    @Autowired
    VehicleRepository vehicleRepository;

    @Autowired
    VehicleLocationEventRepository locationEventRepository;

    @Autowired
    OnboardSystemRuntimeStateRepository runtimeStateRepository;

    @Autowired
    JtTerminalVehicleBindingRepository bindingRepository;

    @Autowired
    TerminalManagementService terminalManagementService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    EntityManager entityManager;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    PreviewReadPause previewReadPause;

    @Autowired
    SystemRefreshRacePause systemRefreshRacePause;

    @Autowired
    TerminalCodeRacePause terminalCodeRacePause;

    @Autowired
    ConstraintFailureInjector constraintFailureInjector;

    @BeforeEach
    void setUp() {
        fixtures.clear();
    }

    @Test
    void rejectsASecondDeviceForAnExclusiveRole() {
        OnboardSystem system = fixtures.activeSystem(OperatingMode.DISPATCH_SERVICE);
        fixtures.verifyDispatchAndLocation("dispatch-01");
        fixtures.verifyDispatchAndLocation("recorder-01");

        ConfigurationCommand command = command(system.getVersion(), List.of(
                device("dispatch-01", NetworkMode.DIRECT_CELLULAR,
                        Set.of(Role.DISPATCH, Role.LOCATION_PRIMARY)),
                device("recorder-01", NetworkMode.SHARED_LAN_CLIENT,
                        Set.of(Role.DISPATCH, Role.LOCATION_BACKUP))));

        assertThatThrownBy(() -> service.preview(system.getVehicleId(), command))
                .isInstanceOf(OnboardConfigurationConflictException.class)
                .hasMessage("EXCLUSIVE_ROLE_CONFLICT:DISPATCH");
    }

    @Test
    void ignoresDeclaredCapabilityWhenValidatingARole() {
        OnboardSystem system = fixtures.activeSystem(OperatingMode.DISPATCH_SERVICE);
        fixtures.declareCapability(
                "dispatch-01", OnboardDeviceCapability.Capability.GBT28787_DISPATCH);
        fixtures.verifySafetyVideoAndLocation("dispatch-01");

        ConfigurationCommand command = command(system.getVersion(), List.of(
                device("dispatch-01", NetworkMode.DIRECT_CELLULAR,
                        Set.of(Role.DISPATCH, Role.LOCATION_PRIMARY))));

        assertThatThrownBy(() -> service.preview(system.getVehicleId(), command))
                .isInstanceOf(OnboardConfigurationConflictException.class)
                .hasMessage("ROLE_CAPABILITY_MISSING:DISPATCH");
    }

    @Test
    void rejectsOnePhysicalTerminalAsBothPrimaryAndBackup() {
        OnboardSystem system = fixtures.activeSystem(OperatingMode.DISPATCH_SERVICE);
        fixtures.verifyDispatchAndLocation("dispatch-01");

        ConfigurationCommand command = command(system.getVersion(), List.of(
                device("dispatch-01", NetworkMode.DIRECT_CELLULAR,
                        Set.of(Role.DISPATCH, Role.LOCATION_PRIMARY, Role.LOCATION_BACKUP))));

        assertThatThrownBy(() -> service.preview(system.getVehicleId(), command))
                .isInstanceOf(OnboardConfigurationConflictException.class)
                .hasMessage("PRIMARY_BACKUP_SAME_TERMINAL");
    }

    @Test
    void previewsAValidDualDeviceStateWithoutWritingAnything() {
        OnboardSystem system = fixtures.activeSystem(OperatingMode.DISPATCH_SERVICE);
        fixtures.verifyDispatchAndLocation("dispatch-01");
        fixtures.verifySafetyVideoAndLocation("recorder-01");
        long versionBefore = system.getVersion();

        ConfigurationCommand command = command(versionBefore, List.of(
                device("dispatch-01", NetworkMode.DIRECT_CELLULAR,
                        Set.of(Role.DISPATCH, Role.LOCATION_PRIMARY, Role.WAN_UPLINK)),
                device("recorder-01", NetworkMode.SHARED_LAN_CLIENT,
                        Set.of(Role.LOCATION_BACKUP, Role.ACTIVE_SAFETY, Role.VIDEO))));

        ConfigurationPreview preview = service.preview(system.getVehicleId(), command);

        assertThat(preview.changedFields()).containsExactly(
                "devices", "protocolProfiles", "roles", "wanUplink");
        assertThat(systemRepository.findById(system.getId()).orElseThrow().getVersion())
                .isEqualTo(versionBefore);
        assertThat(membershipRepository.findAll()).isEmpty();
        assertThat(profileRepository.findAll()).isEmpty();
        assertThat(roleRepository.findAll()).isEmpty();
        assertThat(auditLogRepository.findAll()).isEmpty();
    }

    @Test
    void appliesEachChangedHistoryAsCloseAndAppendWithOneSafeAuditAndVersionAdvance() {
        installActiveHistoryUniquenessChecks();
        OnboardSystem system = fixtures.activeSystem(OperatingMode.DISPATCH_SERVICE);
        fixtures.verifyDispatchAndLocation("dispatch-secret-code");
        fixtures.verifySafetyVideoAndLocation("recorder-secret-code");
        ConfigurationCommand first = command(system.getVersion(), List.of(
                device("dispatch-secret-code", NetworkMode.DIRECT_CELLULAR,
                        Set.of(Role.DISPATCH, Role.LOCATION_PRIMARY, Role.WAN_UPLINK)),
                device("recorder-secret-code", NetworkMode.SHARED_LAN_CLIENT,
                        Set.of(Role.LOCATION_BACKUP, Role.ACTIVE_SAFETY, Role.VIDEO))));

        ConfigurationPreview firstResult = service.apply(
                system.getVehicleId(), first, OnboardTestFixtures.ACTOR_ID);

        assertThat(firstResult.currentVersion()).isEqualTo(system.getVersion() + 1);
        assertThat(membershipRepository.findAll()).hasSize(2);
        assertThat(profileRepository.findAll()).hasSize(2);
        assertThat(roleRepository.findAll()).hasSize(6);
        assertThat(auditLogRepository.findAll()).singleElement().satisfies(audit -> {
            assertThat(audit.getAction()).isEqualTo("ONBOARD_SYSTEM_CONFIGURATION_CHANGED");
            assertThat(audit.getMetadataJson())
                    .contains("changedFields", "oldVersion", "newVersion", "deviceCount", "roleNames")
                    .doesNotContain(
                            "dispatch-secret-code", "recorder-secret-code",
                            "PHONE-dispatch-secret-code", "PHONE-recorder-secret-code",
                            "synthetic-evidence",
                            system.getId().toString(), system.getVehicleId().toString());
        });

        fixtures.verifySafetyVideoAndLocation("recorder-next");
        ConfigurationCommand second = command(firstResult.currentVersion(), List.of(
                deviceWithProfiles("dispatch-secret-code", NetworkMode.SHARED_LAN_CLIENT,
                        Set.of(Role.DISPATCH, Role.LOCATION_PRIMARY),
                        new ProtocolProfiles("JT808_2013", "NONE", "NONE", "NONE", 15, 60)),
                device("recorder-next", NetworkMode.DIRECT_CELLULAR,
                        Set.of(Role.LOCATION_BACKUP, Role.ACTIVE_SAFETY, Role.VIDEO, Role.WAN_UPLINK))));

        ConfigurationPreview secondResult = service.apply(
                system.getVehicleId(), second, OnboardTestFixtures.ACTOR_ID);

        assertThat(secondResult.currentVersion()).isEqualTo(firstResult.currentVersion() + 1);
        JtTerminal dispatch = fixtures.terminal("dispatch-secret-code");
        JtTerminal oldRecorder = fixtures.terminal("recorder-secret-code");
        JtTerminal nextRecorder = fixtures.terminal("recorder-next");
        assertThat(membershipRepository.findHistoryByTerminalIdOrderByValidFromAsc(dispatch.getId()))
                .extracting(OnboardDeviceMembership::getStatus)
                .containsExactly(OnboardDeviceMembership.Status.REMOVED, OnboardDeviceMembership.Status.ACTIVE);
        assertThat(membershipRepository.findHistoryByTerminalIdOrderByValidFromAsc(oldRecorder.getId()))
                .extracting(OnboardDeviceMembership::getStatus)
                .containsExactly(OnboardDeviceMembership.Status.REMOVED);
        assertThat(membershipRepository.findHistoryByTerminalIdOrderByValidFromAsc(nextRecorder.getId()))
                .extracting(OnboardDeviceMembership::getStatus)
                .containsExactly(OnboardDeviceMembership.Status.ACTIVE);
        assertThat(profileRepository.findHistoryByTerminalIdOrderByValidFromAsc(dispatch.getId()))
                .extracting(OnboardDeviceProtocolProfile::getStatus)
                .containsExactly(OnboardDeviceProtocolProfile.Status.SUPERSEDED,
                        OnboardDeviceProtocolProfile.Status.ACTIVE);
        assertThat(roleRepository.findHistoryByOnboardSystemIdAndRoleOrderByValidFromAsc(
                        system.getId(), Role.LOCATION_BACKUP))
                .extracting(OnboardDeviceRoleAssignment::getStatus)
                .containsExactly(OnboardDeviceRoleAssignment.Status.REVOKED,
                        OnboardDeviceRoleAssignment.Status.ACTIVE);
        assertThat(auditLogRepository.findAll()).hasSize(2);

        fixtures.verifySafetyVideoAndLocation("recorder-third");
        ConfigurationCommand third = command(secondResult.currentVersion(), List.of(
                device("dispatch-secret-code", NetworkMode.DIRECT_CELLULAR,
                        Set.of(Role.DISPATCH, Role.LOCATION_PRIMARY, Role.WAN_UPLINK)),
                device("recorder-third", NetworkMode.SHARED_LAN_CLIENT,
                        Set.of(Role.LOCATION_BACKUP, Role.ACTIVE_SAFETY, Role.VIDEO))));

        ConfigurationPreview thirdResult = service.apply(
                system.getVehicleId(), third, OnboardTestFixtures.ACTOR_ID);

        assertThat(thirdResult.currentVersion()).isEqualTo(secondResult.currentVersion() + 1);
        assertThat(membershipRepository.findHistoryByTerminalIdOrderByValidFromAsc(dispatch.getId()))
                .extracting(OnboardDeviceMembership::getStatus)
                .containsExactly(OnboardDeviceMembership.Status.REMOVED,
                        OnboardDeviceMembership.Status.REMOVED,
                        OnboardDeviceMembership.Status.ACTIVE);
        assertThat(profileRepository.findHistoryByTerminalIdOrderByValidFromAsc(dispatch.getId()))
                .extracting(OnboardDeviceProtocolProfile::getStatus)
                .containsExactly(OnboardDeviceProtocolProfile.Status.SUPERSEDED,
                        OnboardDeviceProtocolProfile.Status.SUPERSEDED,
                        OnboardDeviceProtocolProfile.Status.ACTIVE);
        assertThat(roleRepository.findHistoryByOnboardSystemIdAndRoleOrderByValidFromAsc(
                        system.getId(), Role.LOCATION_BACKUP))
                .extracting(OnboardDeviceRoleAssignment::getStatus)
                .containsExactly(OnboardDeviceRoleAssignment.Status.REVOKED,
                        OnboardDeviceRoleAssignment.Status.REVOKED,
                        OnboardDeviceRoleAssignment.Status.ACTIVE);
        assertThat(auditLogRepository.findAll()).hasSize(3);
    }

    private void installActiveHistoryUniquenessChecks() {
        jdbcTemplate.execute("""
                alter table onboard_device_memberships
                add column test_active_terminal_id uuid generated always as
                  (case when status = 'ACTIVE' and valid_to is null then terminal_id else null end)
                """);
        jdbcTemplate.execute("""
                create unique index test_uq_membership_transition_order
                on onboard_device_memberships(test_active_terminal_id)
                """);
        jdbcTemplate.execute("""
                alter table onboard_device_protocol_profiles
                add column test_active_profile_terminal_id uuid generated always as
                  (case when status = 'ACTIVE' and valid_to is null then terminal_id else null end)
                """);
        jdbcTemplate.execute("""
                create unique index test_uq_profile_transition_order
                on onboard_device_protocol_profiles(test_active_profile_terminal_id)
                """);
        jdbcTemplate.execute("""
                alter table onboard_device_role_assignments
                add column test_active_role_system_id uuid generated always as
                  (case when status = 'ACTIVE' and valid_to is null then onboard_system_id else null end)
                """);
        jdbcTemplate.execute("""
                alter table onboard_device_role_assignments
                add column test_active_role_name varchar(30) generated always as
                  (case when status = 'ACTIVE' and valid_to is null then cast(role as varchar) else null end)
                """);
        jdbcTemplate.execute("""
                create unique index test_uq_role_transition_order
                on onboard_device_role_assignments(test_active_role_system_id, test_active_role_name)
                """);
    }

    @Test
    void rejectsNoOpApplyWithoutVersionHistoryOrAuditChanges() {
        OnboardSystem system = fixtures.activeSystem(OperatingMode.DISPATCH_SERVICE);
        fixtures.verifyDispatchAndLocation("dispatch-01");
        ConfigurationCommand first = command(system.getVersion(), List.of(
                device("dispatch-01", NetworkMode.DIRECT_CELLULAR,
                        Set.of(Role.DISPATCH, Role.LOCATION_PRIMARY))));
        ConfigurationPreview applied = service.apply(
                system.getVehicleId(), first, OnboardTestFixtures.ACTOR_ID);
        int membershipCount = membershipRepository.findAll().size();
        int profileCount = profileRepository.findAll().size();
        int roleCount = roleRepository.findAll().size();
        int auditCount = auditLogRepository.findAll().size();

        ConfigurationCommand noOp = command(applied.currentVersion(), first.devices());

        assertThatThrownBy(() -> service.apply(
                system.getVehicleId(), noOp, OnboardTestFixtures.ACTOR_ID))
                .isInstanceOf(OnboardConfigurationConflictException.class)
                .hasMessage("NO_CONFIGURATION_CHANGES");
        assertThat(systemRepository.findById(system.getId()).orElseThrow().getVersion())
                .isEqualTo(applied.currentVersion());
        assertThat(membershipRepository.findAll()).hasSize(membershipCount);
        assertThat(profileRepository.findAll()).hasSize(profileCount);
        assertThat(roleRepository.findAll()).hasSize(roleCount);
        assertThat(auditLogRepository.findAll()).hasSize(auditCount);
    }

    @Test
    void rejectsConfigurationReasonAboveThreeHundredCodePointsBeforeWrites() {
        OnboardSystem system = fixtures.activeSystem(OperatingMode.DISPATCH_SERVICE);
        fixtures.verifyDispatchAndLocation("reason-limit-terminal");
        List<DeviceConfiguration> devices = List.of(device(
                "reason-limit-terminal", NetworkMode.DIRECT_CELLULAR,
                Set.of(Role.DISPATCH, Role.LOCATION_PRIMARY)));

        assertThatThrownBy(() -> service.apply(
                system.getVehicleId(),
                command(system.getVersion(), OperatingMode.DISPATCH_SERVICE,
                        devices, "理".repeat(301)),
                OnboardTestFixtures.ACTOR_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("reason must not exceed 300 characters");
        assertThat(systemRepository.findById(system.getId()).orElseThrow().getVersion()).isZero();
        assertThat(membershipRepository.findAll()).isEmpty();
        assertThat(profileRepository.findAll()).isEmpty();
        assertThat(roleRepository.findAll()).isEmpty();
        assertThat(auditLogRepository.findAll()).isEmpty();
    }

    @Test
    void preservesThreeHundredCodePointReasonForOperatingModeOnlyAudit() {
        OnboardSystem system = fixtures.activeSystem(OperatingMode.DISPATCH_SERVICE);
        fixtures.verifyDispatchAndLocation("mode-reason-terminal");
        List<DeviceConfiguration> devices = List.of(device(
                "mode-reason-terminal", NetworkMode.DIRECT_CELLULAR,
                Set.of(Role.DISPATCH, Role.LOCATION_PRIMARY)));
        ConfigurationPreview initial = service.apply(
                system.getVehicleId(), command(system.getVersion(), devices),
                OnboardTestFixtures.ACTOR_ID);
        auditLogRepository.deleteAll();
        String reason = "因".repeat(300);

        ConfigurationPreview result = service.apply(
                system.getVehicleId(),
                command(initial.currentVersion(), OperatingMode.SAFETY_MONITOR_ONLY,
                        devices, reason),
                OnboardTestFixtures.ACTOR_ID);

        assertThat(result.changedFields()).containsExactly("operatingMode");
        assertThat(auditLogRepository.findAll()).singleElement().satisfies(audit -> {
            assertThat(audit.getReason()).isEqualTo(reason);
            assertThat(audit.getMetadataJson()).doesNotContain(reason);
        });
    }

    @Test
    void preservesOneReasonAcrossMembershipProfileRoleHistoryAndConfigurationAudit() {
        OnboardSystem system = fixtures.activeSystem(OperatingMode.SAFETY_MONITOR_ONLY);
        fixtures.verifySafetyVideoAndLocation("trace-reason");
        String reason = "traceable configuration reason";

        service.apply(system.getVehicleId(), command(
                system.getVersion(), OperatingMode.SAFETY_MONITOR_ONLY,
                List.of(device("trace-reason", NetworkMode.DIRECT_CELLULAR,
                        Set.of(Role.LOCATION_PRIMARY, Role.ACTIVE_SAFETY, Role.VIDEO))),
                reason), OnboardTestFixtures.ACTOR_ID);

        assertThat(membershipRepository.findAll()).singleElement().satisfies(membership ->
                assertThat(membership.getAddedReason()).isEqualTo(reason));
        assertThat(profileRepository.findAll()).singleElement().satisfies(profile ->
                assertThat(profile.getReason()).isEqualTo(reason));
        assertThat(roleRepository.findAll()).allSatisfy(role ->
                assertThat(role.getAssignedReason()).isEqualTo(reason));
        assertThat(auditLogRepository.findAll()).singleElement().satisfies(audit -> {
            assertThat(audit.getReason()).isEqualTo(reason);
            assertThat(audit.getMetadataJson()).doesNotContain(reason);
        });
    }

    @Test
    void capabilityReasonUsesThreeHundredLimitWhileEvidenceKeepsFiveHundredLimit() {
        JtTerminal terminal = fixtures.terminal("capability-reason-limit");

        assertThatThrownBy(() -> service.verifyCapability(
                terminal.getTerminalCode(),
                new CapabilityVerificationCommand(
                        OnboardDeviceCapability.Capability.DMS, null,
                        "由".repeat(301), "evidence"),
                OnboardTestFixtures.ACTOR_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("reason must not exceed 300 characters");
        assertThat(capabilityRepository.findAll()).isEmpty();
        assertThat(auditLogRepository.findAll()).isEmpty();

        String reason = "由".repeat(300);
        String evidence = "证".repeat(500);
        service.verifyCapability(
                terminal.getTerminalCode(),
                new CapabilityVerificationCommand(
                        OnboardDeviceCapability.Capability.DMS, null, reason, evidence),
                OnboardTestFixtures.ACTOR_ID);

        assertThat(capabilityRepository.findAll()).singleElement().satisfies(fact -> {
            assertThat(fact.getReason()).isEqualTo(reason);
            assertThat(fact.getEvidenceRef()).isEqualTo(evidence);
        });
        assertThat(auditLogRepository.findAll()).singleElement().satisfies(audit -> {
            assertThat(audit.getReason()).isEqualTo(reason);
            assertThat(audit.getMetadataJson()).doesNotContain(evidence, reason);
        });
    }

    @Test
    void legacyBindCreatesOnlyCompositeMembershipAndRollsBackLegacyWriteOnConflict() {
        Vehicle vehicle = fixtures.createVehicle("LEGACY-VEHICLE");
        JtTerminal terminal = fixtures.terminal("legacy-recorder");

        terminalManagementService.bind(
                terminal.getTerminalCode(), vehicle.getId(), terminal.getVersion(),
                "legacy compatible binding", OnboardTestFixtures.ACTOR_ID);

        OnboardSystem system = systemRepository.findActiveByVehicleId(vehicle.getId()).orElseThrow();
        assertThat(system.getOperatingMode()).isEqualTo(OperatingMode.SAFETY_MONITOR_ONLY);
        assertThat(runtimeStateRepository.findById(system.getId())).isPresent();
        assertThat(membershipRepository.findActiveByTerminalId(terminal.getId()))
                .get().extracting(OnboardDeviceMembership::getNetworkMode)
                .isEqualTo(NetworkMode.DIRECT_CELLULAR);
        assertThat(capabilityRepository.findHistoryByTerminalIdOrderByCreatedAtAsc(terminal.getId())).isEmpty();
        assertThat(profileRepository.findHistoryByTerminalIdOrderByValidFromAsc(terminal.getId())).isEmpty();
        assertThat(roleRepository.findHistoryByTerminalIdOrderByValidFromAsc(terminal.getId())).isEmpty();
        assertThat(bindingRepository.findAll()).isEmpty();

        Vehicle conflictingVehicle = fixtures.createVehicle("LEGACY-CONFLICT");
        JtTerminal conflictingTerminal = fixtures.terminal("legacy-conflict-terminal");
        OnboardSystem otherSystem = fixtures.activeSystem(OperatingMode.SAFETY_MONITOR_ONLY);
        membershipRepository.saveAndFlush(OnboardDeviceMembership.join(
                otherSystem.getId(), conflictingTerminal.getId(), NetworkMode.DIRECT_CELLULAR,
                "pre-existing membership", OnboardTestFixtures.ACTOR_ID, OffsetDateTime.now()));
        long terminalVersionBefore = conflictingTerminal.getVersion();
        int auditCountBefore = auditLogRepository.findAll().size();

        assertThatThrownBy(() -> terminalManagementService.bind(
                conflictingTerminal.getTerminalCode(), conflictingVehicle.getId(),
                terminalVersionBefore, "must roll back", OnboardTestFixtures.ACTOR_ID))
                .isInstanceOf(OnboardConfigurationConflictException.class)
                .hasMessage("TERMINAL_ALREADY_ASSIGNED");
        assertThat(bindingRepository.findByTerminalIdAndStatus(
                conflictingTerminal.getId(), JtTerminalVehicleBinding.Status.ACTIVE)).isEmpty();
        assertThat(fixtures.terminal(conflictingTerminal.getTerminalCode()).getVersion())
                .isEqualTo(terminalVersionBefore);
        assertThat(systemRepository.findActiveByVehicleId(conflictingVehicle.getId())).isEmpty();
        assertThat(auditLogRepository.findAll()).hasSize(auditCountBefore);
    }

    @Test
    void terminalRetirementWinsItsRowLockAndApplyFailsWithoutPartialConfiguration() throws Exception {
        OnboardSystem system = fixtures.activeSystem(OperatingMode.DISPATCH_SERVICE);
        fixtures.verifyDispatchAndLocation("retire-race-terminal");
        JtTerminal terminal = fixtures.terminal("retire-race-terminal");
        ConfigurationCommand desired = command(system.getVersion(), List.of(
                device("retire-race-terminal", NetworkMode.DIRECT_CELLULAR,
                        Set.of(Role.DISPATCH, Role.LOCATION_PRIMARY))));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch terminalLocked = new CountDownLatch(1);
        CountDownLatch releaseTerminal = new CountDownLatch(1);
        try {
            Future<?> retirement = holdTerminalRow(
                    executor, terminal.getId(), true, terminalLocked, releaseTerminal);
            assertThat(terminalLocked.await(5, TimeUnit.SECONDS)).isTrue();
            CountDownLatch workerStarted = new CountDownLatch(1);
            Future<OperationOutcome> apply = executor.submit(() -> {
                workerStarted.countDown();
                return applyOutcome(system, desired);
            });

            assertThat(workerStarted.await(5, TimeUnit.SECONDS)).isTrue();
            boolean completedWhileTerminalWasLocked = completedWithin(apply, 750);
            releaseTerminal.countDown();

            retirement.get(5, TimeUnit.SECONDS);
            OperationOutcome outcome = apply.get(5, TimeUnit.SECONDS);
            assertThat(completedWhileTerminalWasLocked).isFalse();
            assertThat(outcome).isEqualTo(OperationOutcome.conflict("TERMINAL_RETIRED"));
            assertThat(fixtures.terminal("retire-race-terminal").getStatus())
                    .isEqualTo(JtTerminal.Status.RETIRED);
            assertThat(membershipRepository.findAll()).isEmpty();
            assertThat(profileRepository.findAll()).isEmpty();
            assertThat(roleRepository.findAll()).isEmpty();
            assertThat(auditLogRepository.findAll()).isEmpty();
            assertThat(systemRepository.findById(system.getId()).orElseThrow().getVersion()).isZero();
        } finally {
            releaseTerminal.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void twoSystemsCompetingForOneTerminalProduceOneSuccessAndOneSafeConflict() throws Exception {
        OnboardSystem firstSystem = fixtures.activeSystem(OperatingMode.SAFETY_MONITOR_ONLY);
        OnboardSystem secondSystem = fixtures.activeSystem(OperatingMode.SAFETY_MONITOR_ONLY);
        fixtures.verifySafetyVideoAndLocation("membership-race-terminal");
        JtTerminal terminal = fixtures.terminal("membership-race-terminal");
        ConfigurationCommand first = command(
                firstSystem.getVersion(), OperatingMode.SAFETY_MONITOR_ONLY,
                List.of(device("membership-race-terminal", NetworkMode.DIRECT_CELLULAR,
                        Set.of(Role.LOCATION_PRIMARY))));
        ConfigurationCommand second = command(
                secondSystem.getVersion(), OperatingMode.SAFETY_MONITOR_ONLY,
                List.of(device("membership-race-terminal", NetworkMode.DIRECT_CELLULAR,
                        Set.of(Role.LOCATION_PRIMARY))));

        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch terminalLocked = new CountDownLatch(1);
        CountDownLatch releaseTerminal = new CountDownLatch(1);
        try {
            Future<?> coordinator = holdTerminalRow(
                    executor, terminal.getId(), false, terminalLocked, releaseTerminal);
            assertThat(terminalLocked.await(5, TimeUnit.SECONDS)).isTrue();
            CountDownLatch workersStarted = new CountDownLatch(2);
            Future<OperationOutcome> firstApply = executor.submit(() -> {
                workersStarted.countDown();
                return applyOutcome(firstSystem, first);
            });
            Future<OperationOutcome> secondApply = executor.submit(() -> {
                workersStarted.countDown();
                return applyOutcome(secondSystem, second);
            });

            assertThat(workersStarted.await(5, TimeUnit.SECONDS)).isTrue();
            boolean firstCompletedWhileLocked = completedWithin(firstApply, 750);
            boolean secondCompletedWhileLocked = completedWithin(secondApply, 750);
            releaseTerminal.countDown();

            coordinator.get(5, TimeUnit.SECONDS);
            List<OperationOutcome> outcomes = List.of(
                    firstApply.get(5, TimeUnit.SECONDS),
                    secondApply.get(5, TimeUnit.SECONDS));
            assertThat(firstCompletedWhileLocked).isFalse();
            assertThat(secondCompletedWhileLocked).isFalse();
            assertThat(outcomes).filteredOn(OperationOutcome::success).hasSize(1);
            assertThat(outcomes).filteredOn(outcome ->
                    "TERMINAL_ALREADY_ASSIGNED".equals(outcome.conflictCode())).hasSize(1);
            assertThat(outcomes).allMatch(outcome -> outcome.unexpectedType() == null);
            assertThat(membershipRepository.findActiveByTerminalId(terminal.getId())).isPresent();
            assertThat(membershipRepository.findHistoryByTerminalIdOrderByValidFromAsc(terminal.getId()))
                    .hasSize(1);
            assertThat(auditLogRepository.findAll().stream()
                    .filter(audit -> "ONBOARD_SYSTEM_CONFIGURATION_CHANGED".equals(audit.getAction())))
                    .hasSize(1);
        } finally {
            releaseTerminal.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void twoCapabilityVerificationsProduceOneVerifiedFactAndOneSafeConflict() throws Exception {
        JtTerminal terminal = fixtures.terminal("capability-race-terminal");
        CapabilityVerificationCommand command = new CapabilityVerificationCommand(
                OnboardDeviceCapability.Capability.DMS, null,
                "concurrent capability verification", "synthetic-concurrent-evidence");

        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch terminalLocked = new CountDownLatch(1);
        CountDownLatch releaseTerminal = new CountDownLatch(1);
        try {
            Future<?> coordinator = holdTerminalRow(
                    executor, terminal.getId(), false, terminalLocked, releaseTerminal);
            assertThat(terminalLocked.await(5, TimeUnit.SECONDS)).isTrue();
            CountDownLatch workersStarted = new CountDownLatch(2);
            Future<OperationOutcome> first = executor.submit(() -> {
                workersStarted.countDown();
                return capabilityOutcome(terminal, command);
            });
            Future<OperationOutcome> second = executor.submit(() -> {
                workersStarted.countDown();
                return capabilityOutcome(terminal, command);
            });

            assertThat(workersStarted.await(5, TimeUnit.SECONDS)).isTrue();
            boolean firstCompletedWhileLocked = completedWithin(first, 750);
            boolean secondCompletedWhileLocked = completedWithin(second, 750);
            releaseTerminal.countDown();

            coordinator.get(5, TimeUnit.SECONDS);
            List<OperationOutcome> outcomes = List.of(
                    first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertThat(firstCompletedWhileLocked).isFalse();
            assertThat(secondCompletedWhileLocked).isFalse();
            assertThat(outcomes).filteredOn(OperationOutcome::success).hasSize(1);
            assertThat(outcomes).filteredOn(outcome ->
                    "CAPABILITY_ALREADY_VERIFIED".equals(outcome.conflictCode())).hasSize(1);
            assertThat(outcomes).allMatch(outcome -> outcome.unexpectedType() == null);
            assertThat(capabilityRepository.findHistoryByTerminalIdAndCapabilityOrderByCreatedAtAsc(
                    terminal.getId(), OnboardDeviceCapability.Capability.DMS))
                    .singleElement().satisfies(fact ->
                            assertThat(fact.getStatus())
                                    .isEqualTo(OnboardDeviceCapability.CapabilityStatus.VERIFIED));
            assertThat(auditLogRepository.findAll().stream()
                    .filter(audit -> "DEVICE_CAPABILITY_VERIFIED".equals(audit.getAction())))
                    .hasSize(1);
        } finally {
            releaseTerminal.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void previewCannotMixAnOldAggregateVersionWithNewlyAppliedCurrentState() throws Exception {
        OnboardSystem system = fixtures.activeSystem(OperatingMode.SAFETY_MONITOR_ONLY);
        fixtures.verifySafetyVideoAndLocation("preview-race-terminal");
        ConfigurationCommand desired = command(
                system.getVersion(), OperatingMode.SAFETY_MONITOR_ONLY,
                List.of(device("preview-race-terminal", NetworkMode.DIRECT_CELLULAR,
                        Set.of(Role.LOCATION_PRIMARY))));
        CountDownLatch previewPaused = new CountDownLatch(1);
        CountDownLatch releasePreview = new CountDownLatch(1);
        previewReadPause.arm("preview-snapshot-thread", previewPaused, releasePreview);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ConfigurationPreview> preview = executor.submit(() -> {
                Thread.currentThread().setName("preview-snapshot-thread");
                return service.preview(system.getVehicleId(), desired);
            });
            assertThat(previewPaused.await(5, TimeUnit.SECONDS)).isTrue();
            CountDownLatch workerStarted = new CountDownLatch(1);
            Future<OperationOutcome> apply = executor.submit(() -> {
                workerStarted.countDown();
                return applyOutcome(system, desired);
            });

            assertThat(workerStarted.await(5, TimeUnit.SECONDS)).isTrue();
            boolean applyCompletedWhilePreviewWasPaused = completedWithin(apply, 750);
            releasePreview.countDown();

            ConfigurationPreview previewResult = preview.get(5, TimeUnit.SECONDS);
            OperationOutcome applyResult = apply.get(5, TimeUnit.SECONDS);
            assertThat(previewResult.currentVersion()).isZero();
            assertThat(previewResult.changedFields())
                    .containsExactly("devices", "protocolProfiles", "roles");
            assertThat(applyCompletedWhilePreviewWasPaused).isFalse();
            assertThat(applyResult).isEqualTo(OperationOutcome.succeeded());
            assertThat(systemRepository.findById(system.getId()).orElseThrow().getVersion())
                    .isEqualTo(1);
            assertThat(auditLogRepository.findAll().stream()
                    .filter(audit -> "ONBOARD_SYSTEM_CONFIGURATION_CHANGED".equals(audit.getAction())))
                    .hasSize(1);
        } finally {
            releasePreview.countDown();
            previewReadPause.disarm();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void previewReloadsTheAggregateAfterWaitingForAnApplyCommit() throws Exception {
        OnboardSystem system = fixtures.activeSystem(OperatingMode.SAFETY_MONITOR_ONLY);
        fixtures.verifySafetyVideoAndLocation("system-refresh-race");
        ConfigurationCommand desired = command(
                system.getVersion(), OperatingMode.SAFETY_MONITOR_ONLY,
                List.of(device("system-refresh-race", NetworkMode.DIRECT_CELLULAR,
                        Set.of(Role.LOCATION_PRIMARY))));
        CountDownLatch applyBeforeCommit = new CountDownLatch(1);
        CountDownLatch releaseApply = new CountDownLatch(1);
        CountDownLatch previewLocatedSystem = new CountDownLatch(1);
        systemRefreshRacePause.arm(
                "system-refresh-apply-thread", applyBeforeCommit, releaseApply,
                "system-refresh-preview-thread", previewLocatedSystem);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<OperationOutcome> apply = executor.submit(() -> {
                Thread.currentThread().setName("system-refresh-apply-thread");
                return applyOutcome(system, desired);
            });
            assertThat(applyBeforeCommit.await(5, TimeUnit.SECONDS)).isTrue();
            Future<PreviewOutcome> preview = executor.submit(() -> {
                Thread.currentThread().setName("system-refresh-preview-thread");
                return previewOutcome(system, desired);
            });
            assertThat(previewLocatedSystem.await(5, TimeUnit.SECONDS)).isTrue();

            boolean previewCompletedBeforeApplyCommit = completedWithin(preview, 750);
            releaseApply.countDown();

            OperationOutcome applyOutcome = apply.get(5, TimeUnit.SECONDS);
            PreviewOutcome previewOutcome = preview.get(5, TimeUnit.SECONDS);
            assertThat(previewCompletedBeforeApplyCommit).isFalse();
            assertThat(applyOutcome).isEqualTo(OperationOutcome.succeeded());
            assertThat(previewOutcome.unexpectedType()).isNull();
            if (previewOutcome.preview() == null) {
                assertThat(previewOutcome.conflictCode())
                        .isEqualTo("STALE_CONFIGURATION_VERSION");
            } else {
                assertThat(previewOutcome.conflictCode()).isNull();
                assertThat(previewOutcome.preview().currentVersion()).isEqualTo(1);
            }
            assertThat(systemRepository.findById(system.getId()).orElseThrow().getVersion())
                    .isEqualTo(1);
            assertThat(auditLogRepository.findAll().stream()
                    .filter(audit -> "ONBOARD_SYSTEM_CONFIGURATION_CHANGED".equals(audit.getAction())))
                    .hasSize(1);
        } finally {
            releaseApply.countDown();
            systemRefreshRacePause.disarm();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void legacyBindWaitsForSystemBeforeTakingTheTerminalRowLock() throws Exception {
        Vehicle vehicle = fixtures.createVehicle("LEGACY-LOCK-ORDER");
        OnboardSystem system = systemRepository.saveAndFlush(OnboardSystem.create(
                vehicle.getId(), OperatingMode.SAFETY_MONITOR_ONLY,
                OnboardTestFixtures.ACTOR_ID, OffsetDateTime.now()));
        runtimeStateRepository.saveAndFlush(
                OnboardSystemRuntimeState.initialize(system.getId(), OffsetDateTime.now()));
        JtTerminal terminal = fixtures.terminal("legacy-lock");

        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch systemLocked = new CountDownLatch(1);
        CountDownLatch releaseSystem = new CountDownLatch(1);
        try {
            Future<?> coordinator = holdSystemRow(
                    executor, system.getId(), systemLocked, releaseSystem);
            assertThat(systemLocked.await(5, TimeUnit.SECONDS)).isTrue();
            CountDownLatch bindWorkerStarted = new CountDownLatch(1);
            Future<OperationOutcome> bind = executor.submit(() -> {
                bindWorkerStarted.countDown();
                return legacyBindOutcome(terminal, vehicle.getId(), "legacy lock order");
            });
            assertThat(bindWorkerStarted.await(5, TimeUnit.SECONDS)).isTrue();
            boolean bindCompletedWhileSystemLocked = completedWithin(bind, 750);

            CountDownLatch terminalProbeStarted = new CountDownLatch(1);
            Future<?> terminalProbe = executor.submit(() -> {
                terminalProbeStarted.countDown();
                lockTerminalOnce(terminal.getId());
            });
            assertThat(terminalProbeStarted.await(5, TimeUnit.SECONDS)).isTrue();
            boolean terminalProbeCompletedWhileSystemLocked = completedWithin(terminalProbe, 750);
            releaseSystem.countDown();

            coordinator.get(5, TimeUnit.SECONDS);
            terminalProbe.get(5, TimeUnit.SECONDS);
            OperationOutcome bindOutcome = bind.get(5, TimeUnit.SECONDS);
            assertThat(bindCompletedWhileSystemLocked).isFalse();
            assertThat(terminalProbeCompletedWhileSystemLocked).isTrue();
            assertThat(bindOutcome).isEqualTo(OperationOutcome.succeeded());
            assertThat(bindingRepository.findAll()).isEmpty();
            assertThat(membershipRepository.findActiveByTerminalId(terminal.getId())).isPresent();
        } finally {
            releaseSystem.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void legacyBindRechecksExpectedVersionAfterWaitingForSystemAndRefreshingTerminal()
            throws Exception {
        Vehicle vehicle = fixtures.createVehicle("LEGACY-STALE-VERSION");
        OnboardSystem system = systemRepository.saveAndFlush(OnboardSystem.create(
                vehicle.getId(), OperatingMode.SAFETY_MONITOR_ONLY,
                OnboardTestFixtures.ACTOR_ID, OffsetDateTime.now()));
        runtimeStateRepository.saveAndFlush(
                OnboardSystemRuntimeState.initialize(system.getId(), OffsetDateTime.now()));
        JtTerminal terminal = fixtures.terminal("legacy-stale");
        long staleVersion = terminal.getVersion();
        CountDownLatch bindLocatedSystem = new CountDownLatch(1);
        systemRefreshRacePause.armLegacyBind(
                "legacy-stale-bind-thread", bindLocatedSystem);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch systemLocked = new CountDownLatch(1);
        CountDownLatch releaseSystem = new CountDownLatch(1);
        try {
            Future<?> coordinator = holdSystemRow(
                    executor, system.getId(), systemLocked, releaseSystem);
            assertThat(systemLocked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<OperationOutcome> bind = executor.submit(() -> {
                Thread.currentThread().setName("legacy-stale-bind-thread");
                return legacyBindOutcome(
                        terminal, vehicle.getId(), staleVersion, "stale legacy bind");
            });
            assertThat(bindLocatedSystem.await(5, TimeUnit.SECONDS)).isTrue();

            TransactionTemplate update = new TransactionTemplate(transactionManager);
            update.executeWithoutResult(status -> {
                JtTerminal current = entityManager.find(JtTerminal.class, terminal.getId());
                current.touch();
                entityManager.flush();
            });
            releaseSystem.countDown();

            coordinator.get(5, TimeUnit.SECONDS);
            OperationOutcome bindOutcome = bind.get(5, TimeUnit.SECONDS);
            assertThat(bindOutcome).isEqualTo(
                    OperationOutcome.conflict("terminal version conflict"));
            assertThat(systemRepository.findById(system.getId()).orElseThrow().getVersion()).isZero();
            assertThat(runtimeStateRepository.findAll()).hasSize(1);
            assertThat(membershipRepository.findAll()).isEmpty();
            assertThat(bindingRepository.findAll()).isEmpty();
            assertThat(auditLogRepository.findAll()).isEmpty();
            assertThat(fixtures.terminal("legacy-stale").getVersion()).isEqualTo(staleVersion + 1);
        } finally {
            releaseSystem.countDown();
            systemRefreshRacePause.disarmLegacyBind();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void listUsesFixedQueryCountAndNeverLoadsHistoricalSystems() {
        fixtures.configureDualDeviceSystem("d-list-a", "r-list-a", "LIST-A");
        fixtures.configureDualDeviceSystem("d-list-b", "r-list-b", "LIST-B");
        fixtures.configureDualDeviceSystem("d-list-c", "r-list-c", "LIST-C");
        OnboardSystem historical = fixtures.activeSystem(OperatingMode.SAFETY_MONITOR_ONLY);
        historical.suspend(OnboardTestFixtures.ACTOR_ID, OffsetDateTime.now());
        systemRepository.saveAndFlush(historical);
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        List<OnboardSystemView> systems = service.listSystems();
        long queryCount = statistics.getPrepareStatementCount();

        assertThat(systems).hasSize(3);
        assertThat(systems).allSatisfy(system -> assertThat(system.status())
                .isEqualTo(OnboardSystem.Status.ACTIVE));
        assertThat(queryCount).isLessThanOrEqualTo(6);
    }

    @Test
    void applyRejectsTerminalCodeReleasedAndReusedAfterInitialResolution() throws Exception {
        OnboardSystem system = fixtures.activeSystem(OperatingMode.SAFETY_MONITOR_ONLY);
        fixtures.verifySafetyVideoAndLocation("code-race");
        fixtures.verifySafetyVideoAndLocation("code-reuse");
        JtTerminal original = fixtures.terminal("code-race");
        JtTerminal replacement = fixtures.terminal("code-reuse");
        ConfigurationCommand desired = command(
                system.getVersion(), OperatingMode.SAFETY_MONITOR_ONLY,
                List.of(device("code-race", NetworkMode.DIRECT_CELLULAR,
                        Set.of(Role.LOCATION_PRIMARY))));
        CountDownLatch codeResolved = new CountDownLatch(1);
        CountDownLatch releaseOperation = new CountDownLatch(1);
        terminalCodeRacePause.arm(
                "apply-code-race-thread", codeResolved, releaseOperation);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<OperationOutcome> apply = executor.submit(() -> {
                Thread.currentThread().setName("apply-code-race-thread");
                return applyOutcome(system, desired);
            });
            assertThat(codeResolved.await(5, TimeUnit.SECONDS)).isTrue();
            reuseTerminalCode(original.getId(), replacement.getId(), "code-race");
            releaseOperation.countDown();

            assertThat(apply.get(5, TimeUnit.SECONDS))
                    .isEqualTo(OperationOutcome.conflict("TERMINAL_CODE_CHANGED"));
            assertThat(systemRepository.findById(system.getId()).orElseThrow().getVersion()).isZero();
            assertThat(membershipRepository.findAll()).isEmpty();
            assertThat(profileRepository.findAll()).isEmpty();
            assertThat(roleRepository.findAll()).isEmpty();
            assertThat(auditLogRepository.findAll()).isEmpty();
        } finally {
            releaseOperation.countDown();
            terminalCodeRacePause.disarm();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void capabilityVerificationRejectsPathCodeReleasedAndReusedAfterResolution()
            throws Exception {
        JtTerminal original = fixtures.terminal("cap-code");
        JtTerminal replacement = fixtures.terminal("cap-reuse");
        CapabilityVerificationCommand command = new CapabilityVerificationCommand(
                OnboardDeviceCapability.Capability.DMS, null,
                "code race verification", "synthetic evidence");
        CountDownLatch codeResolved = new CountDownLatch(1);
        CountDownLatch releaseOperation = new CountDownLatch(1);
        terminalCodeRacePause.arm(
                "capability-code-race-thread", codeResolved, releaseOperation);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<OperationOutcome> verification = executor.submit(() -> {
                Thread.currentThread().setName("capability-code-race-thread");
                return capabilityOutcome("cap-code", command);
            });
            assertThat(codeResolved.await(5, TimeUnit.SECONDS)).isTrue();
            reuseTerminalCode(original.getId(), replacement.getId(), "cap-code");
            releaseOperation.countDown();

            assertThat(verification.get(5, TimeUnit.SECONDS))
                    .isEqualTo(OperationOutcome.conflict("TERMINAL_CODE_CHANGED"));
            assertThat(capabilityRepository.findHistoryByTerminalIdAndCapabilityOrderByCreatedAtAsc(
                    original.getId(), OnboardDeviceCapability.Capability.DMS)).isEmpty();
            assertThat(capabilityRepository.findHistoryByTerminalIdAndCapabilityOrderByCreatedAtAsc(
                    replacement.getId(), OnboardDeviceCapability.Capability.DMS)).isEmpty();
            assertThat(auditLogRepository.findAll()).isEmpty();
        } finally {
            releaseOperation.countDown();
            terminalCodeRacePause.disarm();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void translatesKnownPostgresUniqueConstraintCauseChain() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException(
                "outer",
                new SQLException(
                        "duplicate key value violates unique constraint "
                                + "\"uq_onboard_device_memberships_active_terminal\"",
                        "23505"));

        assertKnownConstraintTranslation(failure, "TERMINAL_ALREADY_ASSIGNED");
    }

    @Test
    void translatesKnownH2UniqueConstraintCauseChain() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException(
                "outer",
                new SQLException(
                        "Unique index or primary key violation: "
                                + "\"PUBLIC.UQ_ONBOARD_DEVICE_CAPABILITIES_ACTIVE_TERMINAL_CAPABILITY\"",
                        "23505"));

        assertKnownConstraintTranslation(failure, "CAPABILITY_VERIFICATION_CONFLICT");
    }

    @Test
    void rethrowsUnknownUniqueConstraintEvenWhenNameContainsKnownPrefix() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException(
                "outer",
                new SQLException(
                        "duplicate key value violates unique constraint "
                                + "\"uq_onboard_device_memberships_active_terminal_shadow\"",
                        "23505"));

        assertUnknownConstraintRethrown(failure);
    }

    @Test
    void rethrowsNonUniqueSqlStateEvenWhenMessageContainsKnownConstraint() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException(
                "outer",
                new SQLException(
                        "permission failure near uq_onboard_device_memberships_active_terminal",
                        "42501"));

        assertUnknownConstraintRethrown(failure);
    }

    @Test
    void readyDispatchFixturePersistsLocationEventAndConsistentOnboardProvenance() {
        java.util.UUID vehicleId = fixtures.readyDispatchSystemVehicleId();
        Vehicle vehicle = vehicleRepository.findById(vehicleId).orElseThrow();

        assertThat(vehicle.getCurrentLocationEventId()).isNotNull();
        var storedEvent = locationEventRepository.findById(vehicle.getCurrentLocationEventId());
        assertThat(storedEvent).isPresent();
        VehicleLocationEvent event = storedEvent.orElseThrow();
        assertThat(event.getVehicleId()).isEqualTo(vehicleId);
        assertThat(event.getTerminalId()).isEqualTo(vehicle.getCurrentLocationTerminalId());
        OnboardDeviceMembership membership = membershipRepository
                .findActiveByTerminalId(event.getTerminalId()).orElseThrow();
        OnboardSystem system = systemRepository.findById(membership.getOnboardSystemId()).orElseThrow();
        assertThat(system.getVehicleId()).isEqualTo(vehicleId);
        assertThat(runtimeStateRepository.findById(system.getId()).orElseThrow()
                .getActiveLocationTerminalId()).isEqualTo(event.getTerminalId());
    }

    private static ConfigurationCommand command(
            long expectedVersion, List<DeviceConfiguration> devices) {
        return command(expectedVersion, OperatingMode.DISPATCH_SERVICE, devices);
    }

    private static ConfigurationCommand command(
            long expectedVersion,
            OperatingMode operatingMode,
            List<DeviceConfiguration> devices) {
        return command(
                expectedVersion, operatingMode, devices,
                "configure synthetic onboard system");
    }

    private static ConfigurationCommand command(
            long expectedVersion,
            OperatingMode operatingMode,
            List<DeviceConfiguration> devices,
            String reason) {
        return new ConfigurationCommand(
                expectedVersion, operatingMode,
                devices, reason);
    }

    private Future<?> holdTerminalRow(
            ExecutorService executor,
            java.util.UUID terminalId,
            boolean retire,
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
                if (retire) {
                    entityManager.find(JtTerminal.class, terminalId).retire();
                }
                terminalLocked.countDown();
                await(releaseTerminal);
            });
        });
    }

    private Future<?> holdSystemRow(
            ExecutorService executor,
            java.util.UUID onboardSystemId,
            CountDownLatch systemLocked,
            CountDownLatch releaseSystem) {
        return executor.submit(() -> {
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            transaction.executeWithoutResult(status -> {
                entityManager.createNativeQuery("""
                                select id from onboard_systems
                                where id = :onboardSystemId
                                for update
                                """)
                        .setParameter("onboardSystemId", onboardSystemId)
                        .getSingleResult();
                systemLocked.countDown();
                await(releaseSystem);
            });
        });
    }

    private void lockTerminalOnce(java.util.UUID terminalId) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> entityManager.createNativeQuery("""
                        select id from jt_terminals
                        where id = :terminalId
                        for update
                        """)
                .setParameter("terminalId", terminalId)
                .getSingleResult());
    }

    private void reuseTerminalCode(
            java.util.UUID originalId,
            java.util.UUID replacementId,
            String reusedCode) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            JtTerminal original = entityManager.find(JtTerminal.class, originalId);
            original.correctIdentity(
                    original.getTerminalPhone(), "released-" + reusedCode,
                    original.getManufacturerId(), original.getModel(),
                    original.getProtocolVersion(), original.getSourceCoordinateSystem());
            entityManager.flush();
            JtTerminal replacement = entityManager.find(JtTerminal.class, replacementId);
            replacement.correctIdentity(
                    replacement.getTerminalPhone(), reusedCode,
                    replacement.getManufacturerId(), replacement.getModel(),
                    replacement.getProtocolVersion(), replacement.getSourceCoordinateSystem());
            entityManager.flush();
        });
    }

    private void assertKnownConstraintTranslation(
            DataIntegrityViolationException failure, String expectedCode) {
        OnboardSystem system = constraintTranslationSystem();
        constraintFailureInjector.arm(failure);
        try {
            assertThatThrownBy(() -> service.apply(
                    system.getVehicleId(), constraintTranslationCommand(system),
                    OnboardTestFixtures.ACTOR_ID))
                    .isInstanceOf(OnboardConfigurationConflictException.class)
                    .hasMessage(expectedCode);
            assertConstraintFailureRolledBack(system);
        } finally {
            constraintFailureInjector.disarm();
        }
    }

    private void assertUnknownConstraintRethrown(DataIntegrityViolationException failure) {
        OnboardSystem system = constraintTranslationSystem();
        constraintFailureInjector.arm(failure);
        try {
            assertThatThrownBy(() -> service.apply(
                    system.getVehicleId(), constraintTranslationCommand(system),
                    OnboardTestFixtures.ACTOR_ID))
                    .isSameAs(failure);
            assertConstraintFailureRolledBack(system);
        } finally {
            constraintFailureInjector.disarm();
        }
    }

    private OnboardSystem constraintTranslationSystem() {
        OnboardSystem system = fixtures.activeSystem(OperatingMode.SAFETY_MONITOR_ONLY);
        fixtures.verifySafetyVideoAndLocation("constraint-terminal");
        return system;
    }

    private ConfigurationCommand constraintTranslationCommand(OnboardSystem system) {
        return command(
                system.getVersion(), OperatingMode.SAFETY_MONITOR_ONLY,
                List.of(device("constraint-terminal", NetworkMode.DIRECT_CELLULAR,
                        Set.of(Role.LOCATION_PRIMARY))));
    }

    private void assertConstraintFailureRolledBack(OnboardSystem system) {
        assertThat(systemRepository.findById(system.getId()).orElseThrow().getVersion()).isZero();
        assertThat(membershipRepository.findAll()).isEmpty();
        assertThat(profileRepository.findAll()).isEmpty();
        assertThat(roleRepository.findAll()).isEmpty();
        assertThat(auditLogRepository.findAll()).isEmpty();
    }

    private OperationOutcome applyOutcome(OnboardSystem system, ConfigurationCommand command) {
        try {
            service.apply(system.getVehicleId(), command, OnboardTestFixtures.ACTOR_ID);
            return OperationOutcome.succeeded();
        } catch (OnboardConfigurationConflictException conflict) {
            return OperationOutcome.conflict(conflict.getMessage());
        } catch (RuntimeException unexpected) {
            return OperationOutcome.unexpected(unexpected.getClass().getName());
        }
    }

    private OperationOutcome capabilityOutcome(
            JtTerminal terminal, CapabilityVerificationCommand command) {
        return capabilityOutcome(terminal.getTerminalCode(), command);
    }

    private OperationOutcome capabilityOutcome(
            String terminalCode, CapabilityVerificationCommand command) {
        try {
            service.verifyCapability(
                    terminalCode, command, OnboardTestFixtures.ACTOR_ID);
            return OperationOutcome.succeeded();
        } catch (OnboardConfigurationConflictException conflict) {
            return OperationOutcome.conflict(conflict.getMessage());
        } catch (RuntimeException unexpected) {
            return OperationOutcome.unexpected(unexpected.getClass().getName());
        }
    }

    private OperationOutcome legacyBindOutcome(
            JtTerminal terminal, java.util.UUID vehicleId, String reason) {
        return legacyBindOutcome(terminal, vehicleId, terminal.getVersion(), reason);
    }

    private OperationOutcome legacyBindOutcome(
            JtTerminal terminal,
            java.util.UUID vehicleId,
            long expectedVersion,
            String reason) {
        try {
            terminalManagementService.bind(
                    terminal.getTerminalCode(), vehicleId, expectedVersion,
                    reason, OnboardTestFixtures.ACTOR_ID);
            return OperationOutcome.succeeded();
        } catch (OnboardConfigurationConflictException conflict) {
            return OperationOutcome.conflict(conflict.getMessage());
        } catch (TerminalConflictException conflict) {
            return OperationOutcome.conflict(conflict.getMessage());
        } catch (RuntimeException unexpected) {
            return OperationOutcome.unexpected(unexpected.getClass().getName());
        }
    }

    private PreviewOutcome previewOutcome(
            OnboardSystem system, ConfigurationCommand command) {
        try {
            return PreviewOutcome.succeeded(service.preview(system.getVehicleId(), command));
        } catch (OnboardConfigurationConflictException conflict) {
            return PreviewOutcome.conflict(conflict.getMessage());
        } catch (RuntimeException unexpected) {
            return PreviewOutcome.unexpected(unexpected.getClass().getName());
        }
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
                throw new IllegalStateException("concurrency test latch timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("concurrency test interrupted", interrupted);
        }
    }

    private record OperationOutcome(
            boolean success, String conflictCode, String unexpectedType) {
        static OperationOutcome succeeded() {
            return new OperationOutcome(true, null, null);
        }

        static OperationOutcome conflict(String code) {
            return new OperationOutcome(false, code, null);
        }

        static OperationOutcome unexpected(String type) {
            return new OperationOutcome(false, null, type);
        }
    }

    private record PreviewOutcome(
            ConfigurationPreview preview, String conflictCode, String unexpectedType) {
        static PreviewOutcome succeeded(ConfigurationPreview preview) {
            return new PreviewOutcome(preview, null, null);
        }

        static PreviewOutcome conflict(String code) {
            return new PreviewOutcome(null, code, null);
        }

        static PreviewOutcome unexpected(String type) {
            return new PreviewOutcome(null, null, type);
        }
    }

    static final class PreviewReadPause {
        private final AtomicBoolean pauseOnce = new AtomicBoolean();
        private volatile String threadName;
        private volatile CountDownLatch paused;
        private volatile CountDownLatch release;

        void arm(String threadName, CountDownLatch paused, CountDownLatch release) {
            this.threadName = threadName;
            this.paused = paused;
            this.release = release;
            pauseOnce.set(false);
        }

        void beforeMembershipSnapshotRead() {
            if (Thread.currentThread().getName().equals(threadName)
                    && pauseOnce.compareAndSet(false, true)) {
                paused.countDown();
                await(release);
            }
        }

        void disarm() {
            threadName = null;
            paused = null;
            release = null;
            pauseOnce.set(false);
        }
    }

    static final class SystemRefreshRacePause {
        private final AtomicBoolean applyPauseOnce = new AtomicBoolean();
        private final AtomicBoolean previewSignalOnce = new AtomicBoolean();
        private volatile String applyThreadName;
        private volatile CountDownLatch applyBeforeCommit;
        private volatile CountDownLatch releaseApply;
        private volatile String previewThreadName;
        private volatile CountDownLatch previewLocatedSystem;
        private volatile String legacyBindThreadName;
        private volatile CountDownLatch legacyBindLocatedSystem;

        void arm(
                String applyThreadName,
                CountDownLatch applyBeforeCommit,
                CountDownLatch releaseApply,
                String previewThreadName,
                CountDownLatch previewLocatedSystem) {
            this.applyThreadName = applyThreadName;
            this.applyBeforeCommit = applyBeforeCommit;
            this.releaseApply = releaseApply;
            this.previewThreadName = previewThreadName;
            this.previewLocatedSystem = previewLocatedSystem;
            applyPauseOnce.set(false);
            previewSignalOnce.set(false);
        }

        void afterAuditSave() {
            if (Thread.currentThread().getName().equals(applyThreadName)
                    && applyPauseOnce.compareAndSet(false, true)) {
                applyBeforeCommit.countDown();
                await(releaseApply);
            }
        }

        void afterActiveSystemLookup() {
            if (Thread.currentThread().getName().equals(previewThreadName)
                    && previewSignalOnce.compareAndSet(false, true)) {
                previewLocatedSystem.countDown();
            }
            if (Thread.currentThread().getName().equals(legacyBindThreadName)
                    && legacyBindLocatedSystem != null) {
                legacyBindLocatedSystem.countDown();
            }
        }

        void armLegacyBind(
                String legacyBindThreadName, CountDownLatch legacyBindLocatedSystem) {
            this.legacyBindThreadName = legacyBindThreadName;
            this.legacyBindLocatedSystem = legacyBindLocatedSystem;
        }

        void disarmLegacyBind() {
            legacyBindThreadName = null;
            legacyBindLocatedSystem = null;
        }

        void disarm() {
            applyThreadName = null;
            applyBeforeCommit = null;
            releaseApply = null;
            previewThreadName = null;
            previewLocatedSystem = null;
            legacyBindThreadName = null;
            legacyBindLocatedSystem = null;
            applyPauseOnce.set(false);
            previewSignalOnce.set(false);
        }
    }

    static final class TerminalCodeRacePause {
        private final AtomicBoolean pauseOnce = new AtomicBoolean();
        private volatile String threadName;
        private volatile CountDownLatch codeResolved;
        private volatile CountDownLatch release;

        void arm(
                String threadName,
                CountDownLatch codeResolved,
                CountDownLatch release) {
            this.threadName = threadName;
            this.codeResolved = codeResolved;
            this.release = release;
            pauseOnce.set(false);
        }

        void afterTerminalCodeResolution() {
            if (Thread.currentThread().getName().equals(threadName)
                    && pauseOnce.compareAndSet(false, true)) {
                codeResolved.countDown();
                await(release);
            }
        }

        void disarm() {
            threadName = null;
            codeResolved = null;
            release = null;
            pauseOnce.set(false);
        }
    }

    static final class ConstraintFailureInjector {
        private volatile DataIntegrityViolationException failure;

        void arm(DataIntegrityViolationException failure) {
            this.failure = failure;
        }

        DataIntegrityViolationException take() {
            DataIntegrityViolationException current = failure;
            failure = null;
            return current;
        }

        void disarm() {
            failure = null;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PreviewPauseConfiguration {

        @Bean
        PreviewReadPause previewReadPause() {
            return new PreviewReadPause();
        }

        @Bean
        SystemRefreshRacePause systemRefreshRacePause() {
            return new SystemRefreshRacePause();
        }

        @Bean
        TerminalCodeRacePause terminalCodeRacePause() {
            return new TerminalCodeRacePause();
        }

        @Bean
        ConstraintFailureInjector constraintFailureInjector() {
            return new ConstraintFailureInjector();
        }

        @Bean
        static BeanPostProcessor repositoryPausePostProcessor(
                PreviewReadPause previewReadPause,
                SystemRefreshRacePause systemRefreshRacePause,
                TerminalCodeRacePause terminalCodeRacePause,
                ConstraintFailureInjector constraintFailureInjector) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof OnboardDeviceMembershipRepository repository) {
                        return Proxy.newProxyInstance(
                                OnboardDeviceMembershipRepository.class.getClassLoader(),
                                new Class<?>[] {OnboardDeviceMembershipRepository.class},
                                (proxy, method, arguments) -> {
                                    if (method.getName().equals(
                                            "findActiveByOnboardSystemIdOrderByValidFromAsc")) {
                                        previewReadPause.beforeMembershipSnapshotRead();
                                    }
                                    return invokeRepository(repository, method, arguments);
                                });
                    }
                    if (bean instanceof OnboardSystemRepository repository) {
                        return Proxy.newProxyInstance(
                                OnboardSystemRepository.class.getClassLoader(),
                                new Class<?>[] {OnboardSystemRepository.class},
                                (proxy, method, arguments) -> {
                                    Object result = invokeRepository(repository, method, arguments);
                                    if (method.getName().equals("findActiveByVehicleId")) {
                                        systemRefreshRacePause.afterActiveSystemLookup();
                                    }
                                    return result;
                                });
                    }
                    if (bean instanceof AuditLogRepository repository) {
                        return Proxy.newProxyInstance(
                                AuditLogRepository.class.getClassLoader(),
                                new Class<?>[] {AuditLogRepository.class},
                                (proxy, method, arguments) -> {
                                    if (method.getName().equals("save")) {
                                        DataIntegrityViolationException injected =
                                                constraintFailureInjector.take();
                                        if (injected != null) {
                                            throw injected;
                                        }
                                    }
                                    Object result = invokeRepository(repository, method, arguments);
                                    if (method.getName().equals("save")) {
                                        systemRefreshRacePause.afterAuditSave();
                                    }
                                    return result;
                                });
                    }
                    if (bean instanceof com.idavy.drtops.domain.terminal.JtTerminalRepository repository) {
                        return Proxy.newProxyInstance(
                                com.idavy.drtops.domain.terminal.JtTerminalRepository.class.getClassLoader(),
                                new Class<?>[] {com.idavy.drtops.domain.terminal.JtTerminalRepository.class},
                                (proxy, method, arguments) -> {
                                    Object result = invokeRepository(repository, method, arguments);
                                    if (method.getName().equals("findByTerminalCode")) {
                                        terminalCodeRacePause.afterTerminalCodeResolution();
                                    }
                                    return result;
                                });
                    }
                    return bean;
                }
            };
        }

        private static Object invokeRepository(
                Object repository,
                java.lang.reflect.Method method,
                Object[] arguments) throws Throwable {
            try {
                return method.invoke(repository, arguments);
            } catch (InvocationTargetException invocation) {
                throw invocation.getTargetException();
            }
        }
    }

    private static DeviceConfiguration device(
            String terminalCode, NetworkMode networkMode, Set<Role> roles) {
        return deviceWithProfiles(terminalCode, networkMode, roles,
                new ProtocolProfiles("JT808_2019", "NONE", "NONE", "NONE", 30, 60));
    }

    private static DeviceConfiguration deviceWithProfiles(
            String terminalCode,
            NetworkMode networkMode,
            Set<Role> roles,
            ProtocolProfiles profiles) {
        return new DeviceConfiguration(terminalCode, networkMode, roles, profiles);
    }
}
