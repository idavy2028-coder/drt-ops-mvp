package com.idavy.drtops.domain.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.domain.audit.AuditLog;
import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import com.idavy.drtops.domain.onboard.OnboardDeviceCapability;
import com.idavy.drtops.domain.onboard.OnboardDeviceCapabilityRepository;
import com.idavy.drtops.domain.onboard.OnboardDeviceMembership;
import com.idavy.drtops.domain.onboard.OnboardDeviceMembershipRepository;
import com.idavy.drtops.domain.onboard.OnboardDeviceProtocolProfile;
import com.idavy.drtops.domain.onboard.OnboardDeviceProtocolProfileRepository;
import com.idavy.drtops.domain.onboard.OnboardDeviceRoleAssignment;
import com.idavy.drtops.domain.onboard.OnboardDeviceRoleAssignmentRepository;
import com.idavy.drtops.domain.onboard.OnboardSystem;
import com.idavy.drtops.domain.onboard.OnboardSystemConfigurationService;
import com.idavy.drtops.domain.onboard.OnboardSystemRepository;
import com.idavy.drtops.domain.onboard.OnboardSystemRuntimeState;
import com.idavy.drtops.domain.onboard.OnboardSystemRuntimeStateRepository;
import com.idavy.drtops.integration.jtgateway.JtGatewayControlClient;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.persistence.EntityManager;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:terminal_management;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import(TerminalManagementServiceTest.ControlClientConfiguration.class)
class TerminalManagementServiceTest {

    private static final UUID ACTOR_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VEHICLE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SECOND_VEHICLE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID CORRECTION_VEHICLE_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final String INITIAL_HASH = sha256(UUID.randomUUID().toString());
    private static final String ROTATED_HASH = sha256(UUID.randomUUID().toString());

    @Autowired
    TerminalManagementService service;

    @Autowired
    JtTerminalRepository terminalRepository;

    @Autowired
    JtTerminalVehicleBindingRepository bindingRepository;

    @Autowired
    AuditLogRepository auditLogRepository;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JtGatewayAuditEventRepository gatewayAuditRepository;

    @Autowired
    VehicleRepository vehicleRepository;

    @Autowired
    OnboardSystemRepository onboardSystemRepository;

    @Autowired
    OnboardSystemRuntimeStateRepository runtimeStateRepository;

    @Autowired
    OnboardDeviceMembershipRepository membershipRepository;

    @Autowired
    OnboardDeviceCapabilityRepository capabilityRepository;

    @Autowired
    OnboardDeviceProtocolProfileRepository profileRepository;

    @Autowired
    OnboardDeviceRoleAssignmentRepository roleRepository;

    @Autowired
    FakeControlClient controlClient;

    @Autowired
    EntityManager entityManager;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    LifecyclePause lifecyclePause;

    @BeforeEach
    void setUp() {
        lifecyclePause.reset();
        gatewayAuditRepository.deleteAll();
        auditLogRepository.deleteAll();
        roleRepository.deleteAll();
        profileRepository.deleteAll();
        capabilityRepository.deleteAll();
        membershipRepository.deleteAll();
        runtimeStateRepository.deleteAll();
        onboardSystemRepository.deleteAll();
        bindingRepository.deleteAll();
        terminalRepository.deleteAll();
        vehicleRepository.deleteAll();
        controlClient.available = true;
        controlClient.requests.clear();
        controlClient.committedStateObserved = false;
        vehicleRepository.save(Vehicle.create(
                VEHICLE_ID, "浙A10001", "Microbus", 8, "IDLE",
                "POINT(120.155 30.274)", "测试车队", true));
        vehicleRepository.save(Vehicle.create(
                SECOND_VEHICLE_ID, "浙A10002", "Microbus", 8, "IDLE",
                "POINT(120.155 30.274)", "测试车队", true));
        vehicleRepository.save(Vehicle.create(
                CORRECTION_VEHICLE_ID, "浙A10003", "Microbus", 8, "IDLE",
                "POINT(120.155 30.274)", "P6-2 REAL TERMINAL ACCEPTANCE", false));
    }

    @Test
    void onboardOnlyBindCompletesRegistrationAndActivatesWithoutLegacyRow() {
        JtTerminal terminal = preset("T-ONBOARD-BIND", "PHONE-ONBOARD-BIND");

        service.bind(
                terminal.getTerminalCode(), VEHICLE_ID, terminal.getVersion(),
                "车载系统成员接入", ACTOR_ID);

        JtTerminal bound = terminalRepository.findById(terminal.getId()).orElseThrow();
        assertThat(bindingRepository.findAll()).isEmpty();
        assertThat(membershipRepository.findActiveByTerminalId(bound.getId())).isPresent();
        addProfile(bound.getId());

        service.completeCompositeRegistration(
                bound.getId(), bound.getAuthTokenVersion(), INITIAL_HASH, "gateway-onboard");
        JtTerminal registered = terminalRepository.findById(bound.getId()).orElseThrow();
        service.activate(
                registered.getTerminalCode(), registered.getVersion(),
                "车载系统成员上线", ACTOR_ID);

        assertThat(terminalRepository.findById(bound.getId()).orElseThrow().getStatus())
                .isEqualTo(JtTerminal.Status.ACTIVE);
        assertThat(bindingRepository.findAll()).isEmpty();
    }

    @Test
    void detailUsesCurrentOnboardMembershipAndKeepsLegacyBindingAsHistoryOnly() {
        JtTerminal terminal = preset("T-DETAIL-AUTHORITY", "PHONE-DETAIL-AUTHORITY");
        service.bind(
                terminal.getTerminalCode(), VEHICLE_ID, terminal.getVersion(),
                "车载系统成员接入", ACTOR_ID);
        JtTerminal current = terminalRepository.findById(terminal.getId()).orElseThrow();
        bindingRepository.saveAndFlush(JtTerminalVehicleBinding.bind(
                current, SECOND_VEHICLE_ID, "历史 legacy 绑定", ACTOR_ID));

        TerminalManagementService.TerminalDetail detail = service.getDetail(
                terminal.getTerminalCode());

        assertThat(detail.currentBinding()).isNull();
        assertThat(detail.currentOnboardMembership()).satisfies(membership -> {
            assertThat(membership.onboardSystemId()).isEqualTo(
                    membershipRepository.findActiveByTerminalId(terminal.getId())
                            .orElseThrow().getOnboardSystemId());
            assertThat(membership.vehicleId()).isEqualTo(VEHICLE_ID);
            assertThat(membership.status()).isEqualTo("ACTIVE");
            assertThat(membership.validFrom()).isNotNull();
        });
        assertThat(detail.legacyBindingHistory()).singleElement()
                .satisfies(binding -> assertThat(binding.plateNumber())
                        .isEqualTo("浙A10002"));
        assertThat(detail.bindingHistory()).isEqualTo(detail.legacyBindingHistory());
    }

    @Test
    void retiringLastMemberSuspendsSystemAndClosesAggregateWithoutUpdatingLegacyHistory() {
        JtTerminal terminal = activeTerminal("T-LAST-MEMBER", "PHONE-LAST-MEMBER");
        OnboardSystem system = onboardSystem(VEHICLE_ID, OnboardSystem.OperatingMode.SAFETY_MONITOR_ONLY);
        addMembership(system.getId(), terminal.getId());
        verifyCapability(terminal.getId(), OnboardDeviceCapability.Capability.JT808_LOCATION);
        addProfile(terminal.getId());
        addRole(system.getId(), terminal.getId(), OnboardDeviceRoleAssignment.Role.LOCATION_PRIMARY);
        OnboardSystemRuntimeState runtime = runtimeStateRepository.findById(system.getId()).orElseThrow();
        runtime.selectLocationSource(terminal.getId(), OffsetDateTime.now());
        runtimeStateRepository.saveAndFlush(runtime);
        JtTerminalVehicleBinding legacy = bindingRepository.saveAndFlush(
                JtTerminalVehicleBinding.bind(terminal, VEHICLE_ID, "历史绑定", ACTOR_ID));

        service.retire(
                terminal.getTerminalCode(), terminal.getVersion(), "最后成员退役", ACTOR_ID);

        assertThat(terminalRepository.findById(terminal.getId()).orElseThrow().getStatus())
                .isEqualTo(JtTerminal.Status.RETIRED);
        assertThat(onboardSystemRepository.findById(system.getId()).orElseThrow().getStatus())
                .isEqualTo(OnboardSystem.Status.SUSPENDED);
        assertThat(membershipRepository.findHistoryByTerminalIdOrderByValidFromAsc(terminal.getId()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getStatus()).isEqualTo(OnboardDeviceMembership.Status.REMOVED);
                    assertThat(row.getValidTo()).isNotNull();
                });
        assertThat(roleRepository.findHistoryByTerminalIdOrderByValidFromAsc(terminal.getId()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getStatus()).isEqualTo(OnboardDeviceRoleAssignment.Status.REVOKED);
                    assertThat(row.getValidTo()).isNotNull();
                });
        assertThat(runtimeStateRepository.findById(system.getId()).orElseThrow()
                .getActiveLocationTerminalId()).isNull();
        assertThat(bindingRepository.findById(legacy.getId()).orElseThrow()).satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo(JtTerminalVehicleBinding.Status.ACTIVE);
            assertThat(row.getValidTo()).isNull();
        });
    }

    @Test
    void preprovisionedReplacementTransfersRolesWithoutWritingLegacyHistory() {
        ReplacementFixture fixture = replacementFixture(true);

        TerminalManagementService.ReplacementResult result = service.replace(
                fixture.oldTerminal().getTerminalCode(),
                fixture.replacement().getTerminalCode(),
                fixture.oldTerminal().getVersion(),
                fixture.replacement().getVersion(),
                "预置换机", ACTOR_ID);

        assertThat(result.terminal().getId()).isEqualTo(fixture.replacement().getId());
        assertThat(terminalRepository.findById(fixture.oldTerminal().getId()).orElseThrow().getStatus())
                .isEqualTo(JtTerminal.Status.RETIRED);
        assertThat(membershipRepository.findActiveByTerminalId(fixture.oldTerminal().getId())).isEmpty();
        assertThat(membershipRepository.findActiveByTerminalId(fixture.replacement().getId()))
                .isPresent();
        assertThat(roleRepository.findActiveByTerminalIdOrderByValidFromAsc(
                fixture.oldTerminal().getId())).isEmpty();
        assertThat(roleRepository.findActiveByTerminalIdOrderByValidFromAsc(
                fixture.replacement().getId()))
                .extracting(OnboardDeviceRoleAssignment::getRole)
                .containsExactlyInAnyOrder(
                        OnboardDeviceRoleAssignment.Role.DISPATCH,
                        OnboardDeviceRoleAssignment.Role.LOCATION_PRIMARY);
        assertThat(onboardSystemRepository.findById(fixture.system().getId()).orElseThrow().getStatus())
                .isEqualTo(OnboardSystem.Status.ACTIVE);
        OnboardSystemRuntimeState runtime = runtimeStateRepository
                .findById(fixture.system().getId()).orElseThrow();
        // replacement 必须等待首个合法定位事件，不能仅凭角色迁移冒领活动来源或继承旧游标。
        assertThat(runtime.getActiveLocationTerminalId()).isNull();
        assertThat(runtime.getLastPrimaryValidGatewayReceivedAt()).isNull();
        assertThat(runtime.getPrimaryTerminalCursorAt()).isNull();
        assertThat(runtime.getBackupTerminalCursorAt()).isNull();
        assertThat(bindingRepository.findAll()).isEmpty();
    }

    @Test
    void unverifiedReplacementIsRejectedWithoutAnyMutation() {
        ReplacementFixture fixture = replacementFixture(false);
        long oldVersion = fixture.oldTerminal().getVersion();
        long replacementVersion = fixture.replacement().getVersion();
        long systemVersion = fixture.system().getVersion();
        int auditCount = auditLogRepository.findAll().size();

        assertThatThrownBy(() -> service.replace(
                fixture.oldTerminal().getTerminalCode(),
                fixture.replacement().getTerminalCode(),
                oldVersion, replacementVersion, "能力不足换机", ACTOR_ID))
                .isInstanceOf(TerminalConflictException.class)
                .hasMessage("ONBOARD_REPLACEMENT_CAPABILITY_MISSING");

        assertThat(terminalRepository.findById(fixture.oldTerminal().getId()).orElseThrow())
                .satisfies(terminal -> {
                    assertThat(terminal.getStatus()).isEqualTo(JtTerminal.Status.ACTIVE);
                    assertThat(terminal.getVersion()).isEqualTo(oldVersion);
                });
        assertThat(terminalRepository.findById(fixture.replacement().getId()).orElseThrow())
                .satisfies(terminal -> {
                    assertThat(terminal.getStatus()).isEqualTo(JtTerminal.Status.PENDING);
                    assertThat(terminal.getVersion()).isEqualTo(replacementVersion);
                });
        assertThat(onboardSystemRepository.findById(fixture.system().getId()).orElseThrow())
                .satisfies(system -> {
                    assertThat(system.getStatus()).isEqualTo(OnboardSystem.Status.ACTIVE);
                    assertThat(system.getVersion()).isEqualTo(systemVersion);
                });
        assertThat(membershipRepository.findActiveByTerminalId(fixture.oldTerminal().getId()))
                .isPresent();
        assertThat(membershipRepository.findActiveByTerminalId(fixture.replacement().getId()))
                .isPresent();
        assertThat(roleRepository.findActiveByTerminalIdOrderByValidFromAsc(
                fixture.oldTerminal().getId()))
                .extracting(OnboardDeviceRoleAssignment::getRole)
                .containsExactlyInAnyOrder(
                        OnboardDeviceRoleAssignment.Role.DISPATCH,
                        OnboardDeviceRoleAssignment.Role.LOCATION_PRIMARY);
        assertThat(roleRepository.findActiveByTerminalIdOrderByValidFromAsc(
                fixture.replacement().getId())).isEmpty();
        assertThat(runtimeStateRepository.findById(fixture.system().getId()).orElseThrow()
                .getActiveLocationTerminalId()).isEqualTo(fixture.oldTerminal().getId());
        assertThat(auditLogRepository.findAll()).hasSize(auditCount);
        assertThat(gatewayAuditRepository.findAll()).isEmpty();
        assertThat(bindingRepository.findAll()).isEmpty();
        assertThat(controlClient.requests).isEmpty();
    }

    @Test
    void retireRejectsStaleExpectedVersionAfterAggregateWaitWithoutMutation() throws Exception {
        JtTerminal terminal = activeTerminal("T-RETIRE-STALE", "PHONE-RETIRE-STALE");
        OnboardSystem system = onboardSystem(
                VEHICLE_ID, OnboardSystem.OperatingMode.SAFETY_MONITOR_ONLY);
        addMembership(system.getId(), terminal.getId());
        verifyCapability(terminal.getId(), OnboardDeviceCapability.Capability.JT808_LOCATION);
        addProfile(terminal.getId());
        addRole(system.getId(), terminal.getId(), OnboardDeviceRoleAssignment.Role.LOCATION_PRIMARY);
        OnboardSystemRuntimeState runtime = runtimeStateRepository.findById(system.getId()).orElseThrow();
        runtime.selectLocationSource(terminal.getId(), OffsetDateTime.now());
        runtimeStateRepository.saveAndFlush(runtime);
        long expectedVersion = terminal.getVersion();
        long systemVersion = system.getVersion();
        int auditCount = auditLogRepository.findAll().size();
        int gatewayAuditCount = gatewayAuditRepository.findAll().size();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        lifecyclePause.arm("retireTerminal");

        try {
            Future<TerminalManagementService.ActionResult> retire = executor.submit(() ->
                    service.retire(
                            terminal.getTerminalCode(), expectedVersion,
                            "stale retire must fail", ACTOR_ID));
            lifecyclePause.awaitEntered();
            long concurrentVersion = touchTerminalInIndependentTransaction(terminal.getId());
            lifecyclePause.release();
            Throwable failure = failureOf(retire);

            assertAll(
                    () -> assertThat(failure)
                            .isInstanceOf(TerminalConflictException.class)
                            .hasMessage("terminal version conflict"),
                    () -> assertThat(terminalRepository.findById(terminal.getId()).orElseThrow())
                            .satisfies(current -> {
                                assertThat(current.getStatus()).isEqualTo(JtTerminal.Status.ACTIVE);
                                assertThat(current.getVersion()).isEqualTo(concurrentVersion);
                            }),
                    () -> assertThat(onboardSystemRepository.findById(system.getId()).orElseThrow())
                            .satisfies(current -> {
                                assertThat(current.getStatus()).isEqualTo(OnboardSystem.Status.ACTIVE);
                                assertThat(current.getVersion()).isEqualTo(systemVersion);
                            }),
                    () -> assertThat(membershipRepository.findActiveByTerminalId(terminal.getId()))
                            .isPresent(),
                    () -> assertThat(roleRepository.findActiveByTerminalIdOrderByValidFromAsc(
                            terminal.getId())).singleElement(),
                    () -> assertThat(runtimeStateRepository.findById(system.getId()).orElseThrow()
                            .getActiveLocationTerminalId()).isEqualTo(terminal.getId()),
                    () -> assertThat(auditLogRepository.findAll()).hasSize(auditCount),
                    () -> assertThat(gatewayAuditRepository.findAll()).hasSize(gatewayAuditCount),
                    () -> assertThat(bindingRepository.findAll()).isEmpty(),
                    () -> assertThat(controlClient.requests).isEmpty());
        } finally {
            lifecyclePause.release();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void replaceRejectsStaleReplacementVersionAfterAggregateWaitWithoutMutation()
            throws Exception {
        ReplacementFixture fixture = replacementFixture(true);
        long oldExpectedVersion = fixture.oldTerminal().getVersion();
        long replacementExpectedVersion = fixture.replacement().getVersion();
        long systemVersion = fixture.system().getVersion();
        int auditCount = auditLogRepository.findAll().size();
        int gatewayAuditCount = gatewayAuditRepository.findAll().size();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        lifecyclePause.arm("replaceTerminal");

        try {
            Future<TerminalManagementService.ReplacementResult> replace = executor.submit(() ->
                    service.replace(
                            fixture.oldTerminal().getTerminalCode(),
                            fixture.replacement().getTerminalCode(),
                            oldExpectedVersion, replacementExpectedVersion,
                            "stale replacement must fail", ACTOR_ID));
            lifecyclePause.awaitEntered();
            long concurrentReplacementVersion = touchTerminalInIndependentTransaction(
                    fixture.replacement().getId());
            lifecyclePause.release();
            Throwable failure = failureOf(replace);

            assertAll(
                    () -> assertThat(failure)
                            .isInstanceOf(TerminalConflictException.class)
                            .hasMessage("terminal version conflict"),
                    () -> assertThat(terminalRepository.findById(
                                    fixture.oldTerminal().getId()).orElseThrow())
                            .satisfies(current -> {
                                assertThat(current.getStatus()).isEqualTo(JtTerminal.Status.ACTIVE);
                                assertThat(current.getVersion()).isEqualTo(oldExpectedVersion);
                            }),
                    () -> assertThat(terminalRepository.findById(
                                    fixture.replacement().getId()).orElseThrow())
                            .satisfies(current -> {
                                assertThat(current.getStatus()).isEqualTo(JtTerminal.Status.PENDING);
                                assertThat(current.getVersion()).isEqualTo(
                                        concurrentReplacementVersion);
                            }),
                    () -> assertThat(onboardSystemRepository.findById(
                                    fixture.system().getId()).orElseThrow())
                            .satisfies(current -> {
                                assertThat(current.getStatus()).isEqualTo(OnboardSystem.Status.ACTIVE);
                                assertThat(current.getVersion()).isEqualTo(systemVersion);
                            }),
                    () -> assertThat(membershipRepository.findActiveByTerminalId(
                            fixture.oldTerminal().getId())).isPresent(),
                    () -> assertThat(membershipRepository.findActiveByTerminalId(
                            fixture.replacement().getId())).isPresent(),
                    () -> assertThat(roleRepository.findActiveByTerminalIdOrderByValidFromAsc(
                            fixture.oldTerminal().getId())).hasSize(2),
                    () -> assertThat(roleRepository.findActiveByTerminalIdOrderByValidFromAsc(
                            fixture.replacement().getId())).isEmpty(),
                    () -> assertThat(runtimeStateRepository.findById(
                                    fixture.system().getId()).orElseThrow()
                            .getActiveLocationTerminalId()).isEqualTo(
                                    fixture.oldTerminal().getId()),
                    () -> assertThat(auditLogRepository.findAll()).hasSize(auditCount),
                    () -> assertThat(gatewayAuditRepository.findAll()).hasSize(gatewayAuditCount),
                    () -> assertThat(bindingRepository.findAll()).isEmpty(),
                    () -> assertThat(controlClient.requests).isEmpty());
        } finally {
            lifecyclePause.release();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void requiresCompletedRegistrationAndActiveBindingBeforeActivation() {
        JtTerminal terminal = preset("T-001", "PHONE-001");
        long pendingVersion = terminal.getVersion();

        assertThat(terminal.getStatus()).isEqualTo(JtTerminal.Status.PENDING);
        assertThatThrownBy(() -> service.activate(
                "T-001", pendingVersion, "上线前激活", ACTOR_ID))
                .isInstanceOf(IllegalStateException.class);

        service.bind("T-001", VEHICLE_ID, terminal.getVersion(), "首配车辆", ACTOR_ID);
        terminal = terminalRepository.findByTerminalCode("T-001").orElseThrow();
        addProfile(terminal.getId());
        service.completeRegistration(terminal.getId(), 1, INITIAL_HASH, "gateway-a");
        terminal = terminalRepository.findByTerminalCode("T-001").orElseThrow();
        service.activate("T-001", terminal.getVersion(), "正式启用", ACTOR_ID);

        assertThat(terminalRepository.findByTerminalCode("T-001").orElseThrow().getStatus())
                .isEqualTo(JtTerminal.Status.ACTIVE);
        assertThat(auditActions()).containsExactly(
                "JT_TERMINAL_PRESET", "JT_TERMINAL_BOUND", "JT_TERMINAL_ACTIVATED");
    }

    @Test
    void rejectsStaleVersionWithoutChangingStateOrAppendingAudit() {
        JtTerminal terminal = registeredAndBound("T-002", "PHONE-002");
        long staleVersion = terminal.getVersion();
        service.activate("T-002", staleVersion, "正式启用", ACTOR_ID);

        assertThatThrownBy(() -> service.suspend("T-002", staleVersion, "停用", ACTOR_ID))
                .isInstanceOf(TerminalConflictException.class);
        assertThat(terminalRepository.findByTerminalCode("T-002").orElseThrow().getStatus())
                .isEqualTo(JtTerminal.Status.ACTIVE);
        assertThat(auditActions()).doesNotContain("JT_TERMINAL_SUSPENDED");
    }

    @Test
    void exposesARealOptimisticLockFailureFromIndependentTransactions() {
        JtTerminal terminal = preset("T-OPTIMISTIC", "PHONE-OPTIMISTIC");
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        JtTerminal stale = transaction.execute(status -> entityManager.find(JtTerminal.class, terminal.getId()));
        transaction.executeWithoutResult(status -> {
            JtTerminal current = entityManager.find(JtTerminal.class, terminal.getId());
            current.touch();
            entityManager.flush();
        });
        stale.touch();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                    terminalRepository.saveAndFlush(stale);
                }))
                .isInstanceOfAny(
                        ObjectOptimisticLockingFailureException.class,
                        jakarta.persistence.OptimisticLockException.class);
    }

    @Test
    void keepsSuspendedStateWhenForcedDisconnectCannotBeConfirmed() {
        JtTerminal terminal = activate("T-003", "PHONE-003");
        controlClient.available = false;

        TerminalManagementService.ActionResult result = service.suspend(
                "T-003", terminal.getVersion(), "安全停用", ACTOR_ID);

        assertThat(result.disconnectStatus()).isEqualTo("DISCONNECT_PENDING_CONFIRMATION");
        assertThat(terminalRepository.findByTerminalCode("T-003").orElseThrow().getStatus())
                .isEqualTo(JtTerminal.Status.SUSPENDED);
        assertThat(controlClient.requests).containsExactly(new DisconnectRequest(terminal.getId(), "TERMINAL_SUSPENDED"));
        assertThat(auditActions()).endsWith("JT_TERMINAL_SUSPENDED", "JT_TERMINAL_DISCONNECT_REQUESTED");
    }

    @Test
    void preservesAuthenticationAndAuditStateWhenReplacingTerminal() {
        ReplacementFixture fixture = replacementFixture(true);
        JtTerminal oldTerminal = fixture.oldTerminal();
        JtTerminal replacement = fixture.replacement();
        String oldHash = oldTerminal.getAuthTokenHash();
        int oldTokenVersion = oldTerminal.getAuthTokenVersion();
        String replacementHash = replacement.getAuthTokenHash();
        int replacementTokenVersion = replacement.getAuthTokenVersion();
        assertThat(service.verifyAuthentication(
                replacement.getId(), replacementTokenVersion, replacementHash, "gateway-a",
                UUID.fromString("11111111-2222-3333-4444-555555555555")).approved())
                .isFalse();

        TerminalManagementService.ReplacementResult result = service.replace(
                oldTerminal.getTerminalCode(), replacement.getTerminalCode(),
                oldTerminal.getVersion(), replacement.getVersion(), "设备换机", ACTOR_ID);

        assertThat(result.terminal().getTerminalCode()).isEqualTo(replacement.getTerminalCode());
        assertThat(bindingRepository.findAll()).isEmpty();
        assertThat(terminalRepository.findById(oldTerminal.getId()).orElseThrow().getStatus())
                .isEqualTo(JtTerminal.Status.RETIRED);
        JtTerminal retired = terminalRepository.findById(oldTerminal.getId()).orElseThrow();
        JtTerminal pending = terminalRepository.findById(replacement.getId()).orElseThrow();
        assertThat(retired.getAuthTokenVersion()).isEqualTo(oldTokenVersion + 1);
        assertThat(retired.getAuthTokenHash()).isNotEqualTo(oldHash);
        assertThat(pending.getStatus()).isEqualTo(JtTerminal.Status.PENDING);
        assertThat(pending.getLastRegisteredAt()).isNull();
        assertThat(pending.getAuthTokenVersion()).isEqualTo(replacementTokenVersion + 1);
        assertThat(pending.getAuthTokenHash()).isNotEqualTo(replacementHash);
        assertThat(service.verifyAuthentication(
                pending.getId(), pending.getAuthTokenVersion(), pending.getAuthTokenHash(), "gateway-a",
                UUID.fromString("22222222-3333-4444-5555-666666666666")).approved())
                .isFalse();
        assertThat(service.verifyRegistration(
                pending.getTerminalPhone(), pending.getTerminalCode(), "MFG01", "MODEL-X",
                "浙A10001", "JT808_2019").approved())
                .isTrue();
        assertThat(auditActions()).endsWith("JT_TERMINAL_REPLACED", "JT_TERMINAL_DISCONNECT_REQUESTED");
        assertThat(gatewayAuditRepository.findAll()).singleElement().satisfies(event -> {
            assertThat(event.getEventType()).isEqualTo(JtGatewayAuditEvent.EventType.TERMINAL_REPLACED);
            assertThat(event.getResult()).isEqualTo(JtGatewayAuditEvent.Result.APPLIED);
            assertThat(event.getGatewayInstance()).isEqualTo("API_MANAGEMENT");
        });

        service.completeRegistration(pending.getId(), pending.getAuthTokenVersion(), ROTATED_HASH, "gateway-a");
        pending = terminalRepository.findById(replacement.getId()).orElseThrow();
        service.activate(pending.getTerminalCode(), pending.getVersion(), "完成换机上线", ACTOR_ID);
        assertThat(terminalRepository.findById(replacement.getId()).orElseThrow().getStatus())
                .isEqualTo(JtTerminal.Status.ACTIVE);
    }

    @Test
    void replacementAuditContainsOnlySafeAliasesVersionsRolesAndReasonCode()
            throws Exception {
        ReplacementFixture fixture = replacementFixture(true);
        JtTerminal oldTerminal = fixture.oldTerminal();
        JtTerminal replacement = fixture.replacement();
        int oldVersionBefore = oldTerminal.getAuthTokenVersion();
        int replacementVersionBefore = replacement.getAuthTokenVersion();
        String oldHashBefore = oldTerminal.getAuthTokenHash();
        String replacementHashBefore = replacement.getAuthTokenHash();
        AuditLog historicalSentinel = auditLogRepository.saveAndFlush(AuditLog.record(
                "SYNTHETIC_HISTORY",
                UUID.fromString("77777777-7777-7777-7777-777777777777"),
                "SYNTHETIC_LEGACY_AUDIT",
                "SYSTEM",
                "synthetic-r5-test",
                "synthetic historical sentinel",
                "{\"legacyMarker\":\"preserve-verbatim\"}"));
        UUID sentinelId = historicalSentinel.getId();
        entityManager.clear();
        historicalSentinel = auditLogRepository.findById(sentinelId).orElseThrow();
        OffsetDateTime sentinelCreatedAt = historicalSentinel.getCreatedAt();

        service.replace(
                oldTerminal.getTerminalCode(),
                replacement.getTerminalCode(),
                oldTerminal.getVersion(),
                replacement.getVersion(),
                "synthetic replacement",
                ACTOR_ID);

        AuditLog audit = auditLogRepository
                .findAllByOrderByCreatedAtAsc().stream()
                .filter(item -> "JT_TERMINAL_REPLACED"
                        .equals(item.getAction()))
                .findFirst()
                .orElseThrow();
        JsonNode metadata = objectMapper.readTree(audit.getMetadataJson());
        assertThat(fieldNames(metadata)).containsExactlyInAnyOrder(
                "oldDeviceAlias",
                "replacementDeviceAlias",
                "transferredRoleCount",
                "transferredRoles",
                "oldTokenVersion",
                "replacementTokenVersion",
                "reasonCode");
        assertThat(metadata.path("reasonCode").asText())
                .isEqualTo("TERMINAL_REPLACED");
        assertThat(metadata.path("oldTokenVersion").asInt())
                .isEqualTo(oldVersionBefore + 1);
        assertThat(metadata.path("replacementTokenVersion").asInt())
                .isEqualTo(replacementVersionBefore + 1);
        String expectedOldAlias = "device-"
                + sha256(oldTerminal.getId().toString()).substring(0, 12);
        String expectedReplacementAlias = "device-"
                + sha256(replacement.getId().toString()).substring(0, 12);
        assertThat(metadata.path("oldDeviceAlias").asText())
                .isEqualTo(expectedOldAlias)
                .matches("device-[0-9a-f]{12}");
        assertThat(metadata.path("replacementDeviceAlias").asText())
                .isEqualTo(expectedReplacementAlias)
                .matches("device-[0-9a-f]{12}")
                .isNotEqualTo(expectedOldAlias);
        assertThat(metadata.path("transferredRoleCount").asInt()).isEqualTo(2);
        assertThat(metadata.path("transferredRoles"))
                .extracting(JsonNode::asText)
                .containsExactly("DISPATCH", "LOCATION_PRIMARY");
        JtTerminal oldTerminalAfter = terminalRepository.findById(oldTerminal.getId()).orElseThrow();
        JtTerminal replacementAfter = terminalRepository.findById(replacement.getId()).orElseThrow();
        assertThat(audit.getMetadataJson()).doesNotContain(
                oldTerminal.getTerminalCode(),
                replacement.getTerminalCode(),
                oldTerminal.getTerminalPhone(),
                replacement.getTerminalPhone(),
                oldTerminal.getId().toString(),
                replacement.getId().toString(),
                oldHashBefore,
                replacementHashBefore,
                oldTerminalAfter.getAuthTokenHash(),
                replacementAfter.getAuthTokenHash(),
                "浙A10001");
        AuditLog sentinelAfter = auditLogRepository.findById(historicalSentinel.getId()).orElseThrow();
        assertThat(sentinelAfter.getEntityType()).isEqualTo("SYNTHETIC_HISTORY");
        assertThat(sentinelAfter.getEntityId()).isEqualTo(historicalSentinel.getEntityId());
        assertThat(sentinelAfter.getAction()).isEqualTo("SYNTHETIC_LEGACY_AUDIT");
        assertThat(sentinelAfter.getActorType()).isEqualTo("SYSTEM");
        assertThat(sentinelAfter.getActorId()).isEqualTo("synthetic-r5-test");
        assertThat(sentinelAfter.getReason()).isEqualTo("synthetic historical sentinel");
        assertThat(sentinelAfter.getMetadataJson())
                .isEqualTo("{\"legacyMarker\":\"preserve-verbatim\"}");
        assertThat(sentinelAfter.getCreatedAt()).isEqualTo(sentinelCreatedAt);
    }

    private static List<String> fieldNames(JsonNode value) {
        List<String> names = new ArrayList<>();
        value.fieldNames().forEachRemaining(names::add);
        return List.copyOf(names);
    }

    @Test
    void verifiesAuthenticationOnlyWhileAnActiveMembershipExists() {
        JtTerminal terminal = activate("T-010", "PHONE-010");
        assertThat(service.verifyAuthentication(
                terminal.getId(), 1, INITIAL_HASH, "gateway-a",
                UUID.fromString("33333333-4444-5555-6666-777777777777")).approved()).isTrue();

        OnboardDeviceMembership membership = membershipRepository
                .findActiveByTerminalId(terminal.getId())
                .orElseThrow();
        membership.remove("解除成员关系", ACTOR_ID, OffsetDateTime.now());
        membershipRepository.saveAndFlush(membership);

        assertThat(service.verifyAuthentication(
                terminal.getId(), 1, INITIAL_HASH, "gateway-a",
                UUID.fromString("44444444-5555-6666-7777-888888888888")).approved()).isFalse();
    }

    @Test
    void rejectsAReplacementThatHasAlreadyCompletedRegistration() {
        ReplacementFixture fixture = replacementFixture(true);
        JtTerminal oldTerminal = fixture.oldTerminal();
        JtTerminal replacement = fixture.replacement();
        replacement.completeRegistration(
                replacement.getAuthTokenVersion(), ROTATED_HASH);
        replacement = terminalRepository.saveAndFlush(replacement);
        long replacementVersion = replacement.getVersion();
        String replacementCode = replacement.getTerminalCode();

        assertThatThrownBy(() -> service.replace(
                oldTerminal.getTerminalCode(), replacementCode,
                oldTerminal.getVersion(), replacementVersion, "设备换机", ACTOR_ID))
                .isInstanceOf(TerminalConflictException.class);
        assertThat(controlClient.requests).isEmpty();
    }

    @Test
    void commitsSafeStateAndAuditBeforeCallingGateway() {
        JtTerminal terminal = activate("T-011", "PHONE-011");

        service.suspend("T-011", terminal.getVersion(), "安全停用", ACTOR_ID);

        assertThat(controlClient.committedStateObserved).isTrue();
    }

    @Test
    void neverCallsGatewayWhenDatabaseCommitFails() {
        JtTerminal terminal = activate("T-012", "PHONE-012");
        JtTerminal replacement = preset("T-013", "PHONE-013");
        String invalidAuditReason = "R".repeat(301);

        assertThatThrownBy(() -> service.suspend(
                "T-012", terminal.getVersion(), invalidAuditReason, ACTOR_ID))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.retire(
                "T-012", terminal.getVersion(), invalidAuditReason, ACTOR_ID))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.disconnect(
                "T-012", terminal.getVersion(), invalidAuditReason, ACTOR_ID))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.replace(
                "T-012", "T-013", terminal.getVersion(), replacement.getVersion(), invalidAuditReason, ACTOR_ID))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.rotateAuthentication(
                "T-012", terminal.getVersion(), invalidAuditReason, ACTOR_ID))
                .isInstanceOf(RuntimeException.class);

        assertThat(controlClient.requests).isEmpty();
        assertThat(terminalRepository.findByTerminalCode("T-012").orElseThrow().getStatus())
                .isEqualTo(JtTerminal.Status.ACTIVE);
    }

    @Test
    void rotatesAuthenticationAndSupportsAllAllowedStateTransitions() {
        JtTerminal terminal = activate("T-006", "PHONE-006");
        String oldHash = terminal.getAuthTokenHash();
        service.rotateAuthentication("T-006", terminal.getVersion(), "例行轮换", ACTOR_ID);
        terminal = terminalRepository.findByTerminalCode("T-006").orElseThrow();
        assertThat(terminal.getAuthTokenVersion()).isEqualTo(2);
        assertThat(terminal.getAuthTokenHash()).isNotEqualTo(oldHash);
        assertThat(terminal.getStatus()).isEqualTo(JtTerminal.Status.SUSPENDED);
        assertThat(terminal.getLastRegisteredAt()).isNull();

        service.completeRegistration(terminal.getId(), 2, ROTATED_HASH, "gateway-a");
        terminal = terminalRepository.findByTerminalCode("T-006").orElseThrow();
        service.activate("T-006", terminal.getVersion(), "恢复运营", ACTOR_ID);
        terminal = terminalRepository.findByTerminalCode("T-006").orElseThrow();
        service.suspend("T-006", terminal.getVersion(), "暂停运营", ACTOR_ID);
        terminal = terminalRepository.findByTerminalCode("T-006").orElseThrow();
        service.activate("T-006", terminal.getVersion(), "再次恢复", ACTOR_ID);
        terminal = terminalRepository.findByTerminalCode("T-006").orElseThrow();
        service.retire("T-006", terminal.getVersion(), "永久退役", ACTOR_ID);
        terminal = terminalRepository.findByTerminalCode("T-006").orElseThrow();

        assertThat(terminal.getStatus()).isEqualTo(JtTerminal.Status.RETIRED);
        long retiredVersion = terminal.getVersion();
        assertThatThrownBy(() -> service.activate("T-006", retiredVersion, "非法恢复", ACTOR_ID))
                .isInstanceOf(IllegalStateException.class);
        assertThat(auditActions()).contains(
                "JT_TERMINAL_AUTH_ROTATED", "JT_TERMINAL_SUSPENDED",
                "JT_TERMINAL_ACTIVATED", "JT_TERMINAL_RETIRED");
    }

    @Test
    void limitsRegistrationVerificationToAnUnregisteredPendingTerminalWithActiveBinding() {
        JtTerminal terminal = preset("T-007", "PHONE-007");

        assertThat(service.verifyRegistration(
                "PHONE-007", "T-007", "MFG01", "MODEL-X", "浙A10001", "JT808_2019").approved())
                .isFalse();

        service.bind("T-007", VEHICLE_ID, terminal.getVersion(), "首配车辆", ACTOR_ID);
        addProfile(terminal.getId());
        assertThat(service.verifyRegistration(
                "PHONE-007", "T-007", "MFG01", "MODEL-X", "浙A10001", "JT808_2019").approved())
                .isTrue();

        terminal = terminalRepository.findByTerminalCode("T-007").orElseThrow();
        service.completeRegistration(terminal.getId(), terminal.getAuthTokenVersion(), INITIAL_HASH, "gateway-a");
        assertThat(service.verifyRegistration(
                "PHONE-007", "T-007", "MFG01", "MODEL-X", "浙A10001", "JT808_2019").approved())
                .isFalse();
    }

    @Test
    void acceptsFixedWidthBcdHeaderPhoneFor2019Registration() {
        JtTerminal terminal = preset("T-BCD-2019", "013800000001");
        service.bind(terminal.getTerminalCode(), VEHICLE_ID, terminal.getVersion(), "首配车辆", ACTOR_ID);

        TerminalManagementService.RegistrationDecision decision = service.verifyRegistration(
                "00000000013800000001", "T-BCD-2019", "MFG01", "MODEL-X",
                "浙A10001", "JT808_2019");

        assertThat(decision.approved()).isTrue();
        assertThat(decision.reasonCode()).isNull();
    }

    @Test
    void acceptsFixedWidthBcdHeaderPhoneFor2013Registration() {
        JtTerminal terminal = service.preset(new TerminalManagementService.PresetCommand(
                "13800000001", "T-BCD-2013", "MFG01", "MODEL-X",
                "JT808_2013", "GCJ02", ACTOR_ID, "设备预置"));
        service.bind(terminal.getTerminalCode(), VEHICLE_ID, terminal.getVersion(), "首配车辆", ACTOR_ID);

        TerminalManagementService.RegistrationDecision decision = service.verifyRegistration(
                "013800000001", "T-BCD-2013", "MFG01", "MODEL-X",
                "浙A10001", "JT808_2013");

        assertThat(decision.approved()).isTrue();
        assertThat(decision.reasonCode()).isNull();
    }

    @Test
    void rejectsDifferentNonBcdOrOverwidthHeaderPhones() {
        JtTerminal terminal = preset("T-BCD-REJECT", "013800000001");
        service.bind(terminal.getTerminalCode(), VEHICLE_ID, terminal.getVersion(), "首配车辆", ACTOR_ID);

        assertThat(service.verifyRegistration(
                "00000000013800000002", "T-BCD-REJECT", "MFG01", "MODEL-X",
                "浙A10001", "JT808_2019").reasonCode())
                .isEqualTo("TERMINAL_PHONE_MISMATCH");
        assertThat(service.verifyRegistration(
                "0000000001380000000A", "T-BCD-REJECT", "MFG01", "MODEL-X",
                "浙A10001", "JT808_2019").reasonCode())
                .isEqualTo("TERMINAL_PHONE_MISMATCH");
        assertThat(service.verifyRegistration(
                "000000000013800000001", "T-BCD-REJECT", "MFG01", "MODEL-X",
                "浙A10001", "JT808_2019").reasonCode())
                .isEqualTo("TERMINAL_PHONE_MISMATCH");
    }

    @Test
    void normalizesDescriptiveProtocolVersionWhenPresettingANewTerminal() {
        JtTerminal terminal = service.preset(new TerminalManagementService.PresetCommand(
                "PHONE-PROTOCOL-NEW", "T-PROTOCOL-NEW", "MFG01", "MODEL-X",
                "JT/T 808-2019", "GCJ02", ACTOR_ID, "设备预置"));

        assertThat(terminal.getProtocolVersion()).isEqualTo("JT808_2019");
    }

    @Test
    void acceptsCanonicalGatewayVersionAgainstLegacyDescriptiveStoredVersion() {
        JtTerminal legacy = terminalRepository.saveAndFlush(JtTerminal.preset(
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                "PHONE-PROTOCOL-LEGACY", "T-PROTOCOL-LEGACY", "MFG01", "MODEL-X",
                "JT/T 808-2019", "GCJ02", ACTOR_ID));
        service.bind(legacy.getTerminalCode(), VEHICLE_ID, legacy.getVersion(), "首配车辆", ACTOR_ID);

        assertThat(service.verifyRegistration(
                "PHONE-PROTOCOL-LEGACY", "T-PROTOCOL-LEGACY", "MFG01", "MODEL-X",
                "浙A10001", "JT808_2019").approved()).isTrue();
    }

    @Test
    void returnsSafeFieldSpecificRegistrationRejectionCodes() {
        JtTerminal terminal = preset("T-REG-REASON", "PHONE-REG-REASON");

        assertThat(service.verifyRegistration(
                "PHONE-REG-REASON", "T-MISSING", "MFG01", "MODEL-X",
                "浙A10001", "JT808_2019").reasonCode())
                .isEqualTo("TERMINAL_CODE_NOT_FOUND");
        assertThat(service.verifyRegistration(
                "PHONE-REG-REASON", "T-REG-REASON", "MFG01", "MODEL-X",
                "浙A10001", "JT808_2019").reasonCode())
                .isEqualTo("BINDING_MISSING");

        service.bind("T-REG-REASON", VEHICLE_ID, terminal.getVersion(), "首配车辆", ACTOR_ID);

        assertThat(service.verifyRegistration(
                "WRONG-PHONE", "T-REG-REASON", "MFG01", "MODEL-X",
                "浙A10001", "JT808_2019").reasonCode())
                .isEqualTo("TERMINAL_PHONE_MISMATCH");
        assertThat(service.verifyRegistration(
                "PHONE-REG-REASON", "T-REG-REASON", "WRONG-MFG", "MODEL-X",
                "浙A10001", "JT808_2019").reasonCode())
                .isEqualTo("MANUFACTURER_MISMATCH");
        assertThat(service.verifyRegistration(
                "PHONE-REG-REASON", "T-REG-REASON", "MFG01", "WRONG-MODEL",
                "浙A10001", "JT808_2019").reasonCode())
                .isEqualTo("MODEL_MISMATCH");
        assertThat(service.verifyRegistration(
                "PHONE-REG-REASON", "T-REG-REASON", "MFG01", "MODEL-X",
                "浙A10001", "UNKNOWN").reasonCode())
                .isEqualTo("PROTOCOL_VERSION_MISMATCH");
        assertThat(service.verifyRegistration(
                "PHONE-REG-REASON", "T-REG-REASON", "MFG01", "MODEL-X",
                "浙A10002", "JT808_2019").reasonCode())
                .isEqualTo("VEHICLE_IDENTIFIER_MISMATCH");
    }

    @Test
    void correctsPendingIdentityAndBoundVehicleWithoutChangingSecurityOrBinding() throws Exception {
        JtTerminal terminal = preset("T-CORRECT-OLD", "PHONE-CORRECT-OLD");
        service.configureCapabilities(
                terminal.getTerminalCode(), terminal.getVersion(), "T/JSATL12-2017",
                List.of("ADAS", "DMS"), true, "配置能力", ACTOR_ID);
        terminal = terminalRepository.findById(terminal.getId()).orElseThrow();
        service.bind(terminal.getTerminalCode(), CORRECTION_VEHICLE_ID,
                terminal.getVersion(), "首配车辆", ACTOR_ID);
        terminal = terminalRepository.findById(terminal.getId()).orElseThrow();
        UUID terminalId = terminal.getId();
        String authenticationHash = terminal.getAuthTokenHash();
        int authenticationVersion = terminal.getAuthTokenVersion();

        TerminalManagementService.IdentityCorrectionResult result = service.correctIdentity(
                "T-CORRECT-OLD", terminal.getVersion(),
                new TerminalManagementService.IdentityCorrectionCommand(
                        "00000000000000000001", "T-CORRECT-NEW", "MFG-NEW", "MODEL-NEW",
                        "JT/T 808-2019", "WGS84", "浙A10003-NEW"),
                ACTOR_ID, "PRE_ACCEPTANCE_IDENTITY_CORRECTION");

        JtTerminal corrected = terminalRepository.findByTerminalCode("T-CORRECT-NEW").orElseThrow();
        assertThat(result.changedFields()).containsExactlyInAnyOrder(
                "terminalPhone", "terminalCode", "manufacturerId", "model",
                "sourceCoordinateSystem", "vehicleIdentifier");
        assertThat(corrected.getId()).isEqualTo(terminalId);
        assertThat(corrected.getStatus()).isEqualTo(JtTerminal.Status.PENDING);
        assertThat(corrected.getLastRegisteredAt()).isNull();
        assertThat(corrected.getLastAuthenticatedAt()).isNull();
        assertThat(corrected.getAuthTokenHash()).isEqualTo(authenticationHash);
        assertThat(corrected.getAuthTokenVersion()).isEqualTo(authenticationVersion);
        assertThat(corrected.getActiveSafetyStandard()).isEqualTo("T/JSATL12-2017");
        assertThat(corrected.getActiveSafetyModules()).contains("ADAS", "DMS");
        assertThat(corrected.isJt1078Enabled()).isTrue();
        assertThat(membershipRepository.findActiveByTerminalId(terminalId)).isPresent();
        assertThat(bindingRepository.findAll()).isEmpty();
        assertThat(vehicleRepository.findById(CORRECTION_VEHICLE_ID).orElseThrow().getPlateNumber())
                .isEqualTo("浙A10003-NEW");
        assertThat(auditActions()).endsWith(
                "JT_TERMINAL_IDENTITY_CORRECTED", "VEHICLE_IDENTIFIER_CORRECTED");
        var correctionAudits = auditLogRepository.findAllByOrderByCreatedAtAsc().stream()
                .filter(audit -> java.util.Set.of(
                        "JT_TERMINAL_IDENTITY_CORRECTED", "VEHICLE_IDENTIFIER_CORRECTED")
                        .contains(audit.getAction()))
                .toList();
        assertThat(correctionAudits).hasSize(2);
        for (var audit : correctionAudits) {
            var metadata = new ObjectMapper().readTree(audit.getMetadataJson());
            List<String> auditedChangedFields = new java.util.ArrayList<>();
            metadata.path("changedFields").forEach(field -> auditedChangedFields.add(field.asText()));

            assertThat(metadata.size()).isEqualTo(2);
            assertThat(auditedChangedFields).containsExactlyInAnyOrderElementsOf(result.changedFields());
            assertThat(metadata.path("version").asLong()).isEqualTo(corrected.getVersion());
            assertThat(audit.getActorType()).isEqualTo("USER");
            assertThat(audit.getActorId()).isEqualTo(ACTOR_ID.toString());
            assertThat(audit.getReason()).isEqualTo("PRE_ACCEPTANCE_IDENTITY_CORRECTION");
            assertThat(audit.getCreatedAt()).isNotNull();
            assertThat(audit.getMetadataJson())
                    .doesNotContain("beforeDigest", "afterDigest", "PHONE-CORRECT-OLD",
                            "00000000000000000001", "T-CORRECT-OLD", "T-CORRECT-NEW",
                            "MFG-NEW", "MODEL-NEW", "浙A10003", "浙A10003-NEW");
        }
        assertThat(correctionAudits.stream()
                .filter(audit -> "JT_TERMINAL_IDENTITY_CORRECTED".equals(audit.getAction()))
                .findFirst().orElseThrow().getEntityId()).isEqualTo(terminalId);
        assertThat(correctionAudits.stream()
                .filter(audit -> "VEHICLE_IDENTIFIER_CORRECTED".equals(audit.getAction()))
                .findFirst().orElseThrow().getEntityId()).isEqualTo(CORRECTION_VEHICLE_ID);
    }

    @Test
    void rejectsIdentityCorrectionForRegisteredStaleOrDuplicateTerminal() {
        JtTerminal target = preset("T-CORRECT-TARGET", "PHONE-CORRECT-TARGET");
        service.bind(target.getTerminalCode(), CORRECTION_VEHICLE_ID,
                target.getVersion(), "首配车辆", ACTOR_ID);
        target = terminalRepository.findById(target.getId()).orElseThrow();
        addProfile(target.getId());
        JtTerminal duplicate = preset("T-CORRECT-DUP", "PHONE-CORRECT-DUP");

        long currentVersion = target.getVersion();
        assertThatThrownBy(() -> service.correctIdentity(
                "T-CORRECT-TARGET", currentVersion - 1,
                new TerminalManagementService.IdentityCorrectionCommand(
                        "PHONE-NEW", "T-NEW", "MFG01", "MODEL-X",
                        "JT808_2019", "GCJ02", "浙A10003-NEW"),
                ACTOR_ID, "身份纠正"))
                .isInstanceOf(TerminalConflictException.class);
        assertThatThrownBy(() -> service.correctIdentity(
                "T-CORRECT-TARGET", currentVersion,
                new TerminalManagementService.IdentityCorrectionCommand(
                        duplicate.getTerminalPhone(), duplicate.getTerminalCode(), "MFG01", "MODEL-X",
                        "JT808_2019", "GCJ02", "浙A10003-NEW"),
                ACTOR_ID, "身份纠正"))
                .isInstanceOf(TerminalConflictException.class);

        service.completeRegistration(target.getId(), target.getAuthTokenVersion(), INITIAL_HASH, "gateway-a");
        JtTerminal registered = terminalRepository.findById(target.getId()).orElseThrow();
        assertThatThrownBy(() -> service.correctIdentity(
                registered.getTerminalCode(), registered.getVersion(),
                new TerminalManagementService.IdentityCorrectionCommand(
                        "PHONE-NEW", "T-NEW", "MFG01", "MODEL-X",
                        "JT808_2019", "GCJ02", "浙A10003-NEW"),
                ACTOR_ID, "身份纠正"))
                .isInstanceOf(TerminalConflictException.class);
    }

    @Test
    void previewsIdentityCorrectionWithoutWritingStateOrAudit() {
        JtTerminal terminal = preset("T-PREVIEW-OLD", "PHONE-PREVIEW-OLD");
        service.bind(terminal.getTerminalCode(), CORRECTION_VEHICLE_ID,
                terminal.getVersion(), "首配车辆", ACTOR_ID);
        terminal = terminalRepository.findById(terminal.getId()).orElseThrow();
        int auditCount = auditLogRepository.findAll().size();

        TerminalManagementService.IdentityCorrectionResult preview = service.previewIdentityCorrection(
                terminal.getTerminalCode(), terminal.getVersion(),
                new TerminalManagementService.IdentityCorrectionCommand(
                        "PHONE-PREVIEW-NEW", "T-PREVIEW-NEW", "MFG01", "MODEL-X",
                        "JT808_2019", "GCJ02", "浙A10003-NEW"));

        assertThat(preview.changedFields()).containsExactly(
                "terminalPhone", "terminalCode", "vehicleIdentifier");
        assertThat(terminalRepository.findByTerminalCode("T-PREVIEW-OLD")).isPresent();
        assertThat(terminalRepository.findByTerminalCode("T-PREVIEW-NEW")).isEmpty();
        assertThat(vehicleRepository.findById(CORRECTION_VEHICLE_ID).orElseThrow().getPlateNumber())
                .isEqualTo("浙A10003");
        assertThat(auditLogRepository.findAll()).hasSize(auditCount);
    }

    @Test
    void keepsIdentityCorrectionPreviewConsistentWithRegistrationVerification() {
        JtTerminal terminal = preset("T-CONSISTENCY", "013800000001");
        service.bind(terminal.getTerminalCode(), CORRECTION_VEHICLE_ID,
                terminal.getVersion(), "首配车辆", ACTOR_ID);
        terminal = terminalRepository.findById(terminal.getId()).orElseThrow();
        long versionBeforeCorrection = terminal.getVersion();
        String fixedWidthHeaderPhone = "00000000013800000001";
        TerminalManagementService.IdentityCorrectionCommand correction =
                new TerminalManagementService.IdentityCorrectionCommand(
                        fixedWidthHeaderPhone, terminal.getTerminalCode(), terminal.getManufacturerId(),
                        terminal.getModel(), terminal.getProtocolVersion(), terminal.getSourceCoordinateSystem(),
                        "浙A10003-NEW");

        TerminalManagementService.IdentityCorrectionResult preview = service.previewIdentityCorrection(
                terminal.getTerminalCode(), terminal.getVersion(), correction);
        TerminalManagementService.RegistrationDecision before = service.verifyRegistration(
                fixedWidthHeaderPhone, terminal.getTerminalCode(), terminal.getManufacturerId(), terminal.getModel(),
                "浙A10003-NEW", terminal.getProtocolVersion());

        assertThat(preview.changedFields()).containsExactly("vehicleIdentifier");
        assertThat(before.approved()).isFalse();
        assertThat(before.reasonCode()).isEqualTo("VEHICLE_IDENTIFIER_MISMATCH");

        TerminalManagementService.IdentityCorrectionResult applied = service.correctIdentity(
                terminal.getTerminalCode(), terminal.getVersion(), correction,
                ACTOR_ID, "PRE_ACCEPTANCE_IDENTITY_CORRECTION");
        JtTerminal corrected = terminalRepository.findById(terminal.getId()).orElseThrow();
        TerminalManagementService.RegistrationDecision after = service.verifyRegistration(
                fixedWidthHeaderPhone, corrected.getTerminalCode(), corrected.getManufacturerId(), corrected.getModel(),
                "浙A10003-NEW", corrected.getProtocolVersion());

        assertThat(applied.changedFields()).containsExactly("vehicleIdentifier");
        assertThat(corrected.getTerminalPhone()).isEqualTo("013800000001");
        assertThat(corrected.getProtocolVersion()).isEqualTo("JT808_2019");
        assertThat(corrected.getVersion()).isEqualTo(versionBeforeCorrection + 1);
        assertThat(after.approved()).isTrue();
        assertThat(after.reasonCode()).isNull();
    }

    @Test
    void rejectsSemanticallyEquivalentFixedWidthPhoneConflictDuringIdentityCorrection() {
        JtTerminal target = preset("T-SEMANTIC-TARGET", "013800000001");
        service.bind(target.getTerminalCode(), CORRECTION_VEHICLE_ID,
                target.getVersion(), "首配车辆", ACTOR_ID);
        target = terminalRepository.findById(target.getId()).orElseThrow();
        preset("T-SEMANTIC-DUPLICATE", "013800000002");
        TerminalManagementService.IdentityCorrectionCommand conflictingCorrection =
                new TerminalManagementService.IdentityCorrectionCommand(
                        "00000000013800000002", target.getTerminalCode(), target.getManufacturerId(),
                        target.getModel(), target.getProtocolVersion(), target.getSourceCoordinateSystem(),
                        "浙A10003");
        long expectedVersion = target.getVersion();

        assertThatThrownBy(() -> service.previewIdentityCorrection(
                "T-SEMANTIC-TARGET", expectedVersion, conflictingCorrection))
                .isInstanceOf(TerminalConflictException.class);
        assertThatThrownBy(() -> service.correctIdentity(
                "T-SEMANTIC-TARGET", expectedVersion, conflictingCorrection,
                ACTOR_ID, "PRE_ACCEPTANCE_IDENTITY_CORRECTION"))
                .isInstanceOf(TerminalConflictException.class);

        assertThat(terminalRepository.findByTerminalCode("T-SEMANTIC-TARGET").orElseThrow()
                .getTerminalPhone()).isEqualTo("013800000001");
        assertThat(auditActions()).doesNotContain("JT_TERMINAL_IDENTITY_CORRECTED");
    }

    @Test
    void rejectsProtocolOnlyCorrectionWhenItWouldCanonicalizeToAnotherTerminalPhoneIdentity() {
        JtTerminal legacyTarget = terminalRepository.saveAndFlush(JtTerminal.preset(
                UUID.fromString("77777777-7777-7777-7777-777777777777"),
                "123", "T-PROTOCOL-ONLY-TARGET", "MFG01", "MODEL-X",
                "LEGACY_UNKNOWN", "GCJ02", ACTOR_ID));
        service.bind(legacyTarget.getTerminalCode(), CORRECTION_VEHICLE_ID,
                legacyTarget.getVersion(), "首配车辆", ACTOR_ID);
        preset("T-PROTOCOL-ONLY-DUPLICATE", "00000000000000000123");
        legacyTarget = terminalRepository.findById(legacyTarget.getId()).orElseThrow();
        TerminalManagementService.IdentityCorrectionCommand correction =
                new TerminalManagementService.IdentityCorrectionCommand(
                        legacyTarget.getTerminalPhone(), legacyTarget.getTerminalCode(),
                        legacyTarget.getManufacturerId(), legacyTarget.getModel(),
                        "JT808_2019", legacyTarget.getSourceCoordinateSystem(), "浙A10003");
        String targetCode = legacyTarget.getTerminalCode();
        long targetVersion = legacyTarget.getVersion();

        assertThatThrownBy(() -> service.previewIdentityCorrection(
                targetCode, targetVersion, correction))
                .isInstanceOf(TerminalConflictException.class)
                .hasMessage("terminal identity is already in use");
        assertThatThrownBy(() -> service.correctIdentity(
                targetCode, targetVersion, correction,
                ACTOR_ID, "PRE_ACCEPTANCE_IDENTITY_CORRECTION"))
                .isInstanceOf(TerminalConflictException.class)
                .hasMessage("terminal identity is already in use");
    }

    @Test
    void allowsOpaqueLegacyPhoneWhenPersistentCanonicalIdentitiesDiffer() {
        JtTerminal target = preset("T-OPAQUE-TARGET", "013800000001");
        service.bind(target.getTerminalCode(), CORRECTION_VEHICLE_ID,
                target.getVersion(), "首配车辆", ACTOR_ID);
        target = terminalRepository.findById(target.getId()).orElseThrow();
        terminalRepository.saveAndFlush(JtTerminal.preset(
                UUID.fromString("66666666-6666-6666-6666-666666666666"),
                "123", "T-OPAQUE-LEGACY", "MFG01", "MODEL-X",
                "LEGACY_UNKNOWN", "GCJ02", ACTOR_ID));
        TerminalManagementService.IdentityCorrectionCommand correction =
                new TerminalManagementService.IdentityCorrectionCommand(
                        "00000000000000000123", target.getTerminalCode(), target.getManufacturerId(),
                        target.getModel(), target.getProtocolVersion(), target.getSourceCoordinateSystem(),
                        "浙A10003");
        String targetCode = target.getTerminalCode();
        long targetVersion = target.getVersion();

        assertThatCode(() -> service.previewIdentityCorrection(
                targetCode, targetVersion, correction))
                .doesNotThrowAnyException();
        TerminalManagementService.IdentityCorrectionResult preview = service.previewIdentityCorrection(
                targetCode, targetVersion, correction);

        assertThat(preview.changedFields()).containsExactly("terminalPhone");
    }

    @Test
    void enforcesSemanticPhoneUniquenessAtThePersistenceBoundary() {
        preset("T-PERSISTED-PHONE-SHORT", "013800000002");

        assertThatThrownBy(() -> preset(
                "T-PERSISTED-PHONE-FIXED-WIDTH", "00000000013800000002"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsRegistrationCompletionWithoutBindingOrWithAnyOtherTokenVersion() {
        JtTerminal terminal = preset("T-008", "PHONE-008");
        String unavailableHash = terminal.getAuthTokenHash();

        assertThatThrownBy(() -> service.completeRegistration(
                terminal.getId(), terminal.getAuthTokenVersion(), INITIAL_HASH, "gateway-a"))
                .isInstanceOf(TerminalConflictException.class);

        service.bind("T-008", VEHICLE_ID, terminal.getVersion(), "首配车辆", ACTOR_ID);
        JtTerminal bound = terminalRepository.findByTerminalCode("T-008").orElseThrow();
        addProfile(bound.getId());
        assertThatThrownBy(() -> service.completeRegistration(bound.getId(), 0, INITIAL_HASH, "gateway-a"))
                .isInstanceOf(TerminalConflictException.class);
        assertThatThrownBy(() -> service.completeRegistration(bound.getId(), 2, INITIAL_HASH, "gateway-a"))
                .isInstanceOf(TerminalConflictException.class);
        assertThat(terminalRepository.findByTerminalCode("T-008").orElseThrow().getAuthTokenHash())
                .isEqualTo(unavailableHash);
    }

    @Test
    void rejectsRepeatedRegistrationCompletionAndEveryNonPendingStateOverwrite() {
        JtTerminal terminal = registeredAndBound("T-009", "PHONE-009");
        UUID terminalId = terminal.getId();
        int currentTokenVersion = terminal.getAuthTokenVersion();

        assertThatThrownBy(() -> service.completeRegistration(
                terminalId, currentTokenVersion, ROTATED_HASH, "gateway-a"))
                .isInstanceOf(TerminalConflictException.class);

        service.activate("T-009", terminal.getVersion(), "正式启用", ACTOR_ID);
        terminal = terminalRepository.findByTerminalCode("T-009").orElseThrow();
        assertRegistrationOverwriteRejected(terminal);

        service.suspend("T-009", terminal.getVersion(), "暂停运营", ACTOR_ID);
        terminal = terminalRepository.findByTerminalCode("T-009").orElseThrow();
        assertRegistrationOverwriteRejected(terminal);

        service.retire("T-009", terminal.getVersion(), "永久退役", ACTOR_ID);
        assertRegistrationOverwriteRejected(terminalRepository.findByTerminalCode("T-009").orElseThrow());
    }

    private JtTerminal preset(String code, String phone) {
        return service.preset(new TerminalManagementService.PresetCommand(
                phone, code, "MFG01", "MODEL-X", "JT808_2019", "GCJ02", ACTOR_ID, "设备预置"));
    }

    private JtTerminal activeTerminal(String code, String phone) {
        JtTerminal terminal = preset(code, phone);
        terminal.completeRegistration(terminal.getAuthTokenVersion(), INITIAL_HASH);
        terminal.activate(true);
        return terminalRepository.saveAndFlush(terminal);
    }

    private OnboardSystem onboardSystem(UUID vehicleId, OnboardSystem.OperatingMode operatingMode) {
        OffsetDateTime now = OffsetDateTime.now();
        OnboardSystem system = onboardSystemRepository.saveAndFlush(
                OnboardSystem.create(vehicleId, operatingMode, ACTOR_ID, now));
        runtimeStateRepository.saveAndFlush(
                OnboardSystemRuntimeState.initialize(system.getId(), now));
        return system;
    }

    private OnboardDeviceMembership addMembership(UUID systemId, UUID terminalId) {
        return membershipRepository.saveAndFlush(OnboardDeviceMembership.join(
                systemId, terminalId, OnboardDeviceMembership.NetworkMode.DIRECT_CELLULAR,
                "Task 8 测试成员", ACTOR_ID, OffsetDateTime.now()));
    }

    private OnboardDeviceProtocolProfile addProfile(UUID terminalId) {
        return profileRepository.saveAndFlush(OnboardDeviceProtocolProfile.activate(
                terminalId,
                OnboardDeviceProtocolProfile.TransportProfile.JT808_2019,
                OnboardDeviceProtocolProfile.BusinessProfile.GBT28787_2023,
                OnboardDeviceProtocolProfile.SafetyProfile.NONE,
                OnboardDeviceProtocolProfile.MediaProfile.NONE,
                30, 60, "Task 8 测试协议档案", ACTOR_ID, OffsetDateTime.now()));
    }

    private OnboardDeviceCapability verifyCapability(
            UUID terminalId, OnboardDeviceCapability.Capability capability) {
        OffsetDateTime declaredAt = OffsetDateTime.now();
        OnboardDeviceCapability fact = OnboardDeviceCapability.declare(
                terminalId, capability, "Task 8 测试能力声明", declaredAt);
        fact.verify(
                "task-8-controlled-evidence", ACTOR_ID,
                "Task 8 测试能力验证", declaredAt.plusNanos(1));
        return capabilityRepository.saveAndFlush(fact);
    }

    private OnboardDeviceRoleAssignment addRole(
            UUID systemId, UUID terminalId, OnboardDeviceRoleAssignment.Role role) {
        return roleRepository.saveAndFlush(OnboardDeviceRoleAssignment.assign(
                systemId, terminalId, role, "Task 8 测试角色", ACTOR_ID, OffsetDateTime.now()));
    }

    private ReplacementFixture replacementFixture(boolean verifiedReplacement) {
        JtTerminal oldTerminal = activeTerminal("T-REPLACE-OLD", "PHONE-REPLACE-OLD");
        JtTerminal replacement = preset("T-REPLACE-NEW", "PHONE-REPLACE-NEW");
        OnboardSystem system = onboardSystem(
                VEHICLE_ID, OnboardSystem.OperatingMode.DISPATCH_SERVICE);
        addMembership(system.getId(), oldTerminal.getId());
        addMembership(system.getId(), replacement.getId());
        addProfile(oldTerminal.getId());
        addProfile(replacement.getId());
        verifyCapability(oldTerminal.getId(), OnboardDeviceCapability.Capability.GBT28787_DISPATCH);
        verifyCapability(oldTerminal.getId(), OnboardDeviceCapability.Capability.JT808_LOCATION);
        if (verifiedReplacement) {
            verifyCapability(
                    replacement.getId(), OnboardDeviceCapability.Capability.GBT28787_DISPATCH);
            verifyCapability(
                    replacement.getId(), OnboardDeviceCapability.Capability.JT808_LOCATION);
        } else {
            capabilityRepository.saveAndFlush(OnboardDeviceCapability.declare(
                    replacement.getId(), OnboardDeviceCapability.Capability.JT808_LOCATION,
                    "Task 8 未验证 replacement fixture", OffsetDateTime.now()));
        }
        addRole(system.getId(), oldTerminal.getId(), OnboardDeviceRoleAssignment.Role.DISPATCH);
        addRole(system.getId(), oldTerminal.getId(), OnboardDeviceRoleAssignment.Role.LOCATION_PRIMARY);
        OnboardSystemRuntimeState runtime = runtimeStateRepository.findById(system.getId()).orElseThrow();
        runtime.selectLocationSource(oldTerminal.getId(), OffsetDateTime.now());
        runtimeStateRepository.saveAndFlush(runtime);
        return new ReplacementFixture(system, oldTerminal, replacement);
    }

    private long touchTerminalInIndependentTransaction(UUID terminalId) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Long version = transaction.execute(status -> {
            JtTerminal terminal = terminalRepository.findById(terminalId).orElseThrow();
            terminal.touch();
            return terminalRepository.saveAndFlush(terminal).getVersion();
        });
        return java.util.Objects.requireNonNull(version);
    }

    private static Throwable failureOf(Future<?> future) throws Exception {
        try {
            future.get(5, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException failure) {
            return failure.getCause();
        }
    }

    private JtTerminal registeredAndBound(String code, String phone) {
        JtTerminal terminal = preset(code, phone);
        service.bind(code, VEHICLE_ID, terminal.getVersion(), "首配车辆", ACTOR_ID);
        terminal = terminalRepository.findByTerminalCode(code).orElseThrow();
        addProfile(terminal.getId());
        service.completeRegistration(terminal.getId(), terminal.getAuthTokenVersion(), INITIAL_HASH, "gateway-a");
        return terminalRepository.findByTerminalCode(code).orElseThrow();
    }

    private void assertRegistrationOverwriteRejected(JtTerminal terminal) {
        assertThatThrownBy(() -> service.completeRegistration(
                terminal.getId(), terminal.getAuthTokenVersion(), ROTATED_HASH, "gateway-a"))
                .isInstanceOf(TerminalConflictException.class);
        assertThat(terminalRepository.findById(terminal.getId()).orElseThrow().getAuthTokenHash())
                .isEqualTo(INITIAL_HASH);
    }

    private JtTerminal activate(String code, String phone) {
        JtTerminal terminal = registeredAndBound(code, phone);
        service.activate(code, terminal.getVersion(), "正式启用", ACTOR_ID);
        return terminalRepository.findByTerminalCode(code).orElseThrow();
    }

    private List<String> auditActions() {
        return auditLogRepository.findAllByOrderByCreatedAtAsc().stream()
                .map(audit -> audit.getAction())
                .toList();
    }

    @TestConfiguration
    static class ControlClientConfiguration {
        @Bean
        @Primary
        FakeControlClient fakeControlClient(DataSource dataSource) {
            return new FakeControlClient(dataSource);
        }

        @Bean
        LifecyclePause lifecyclePause() {
            return new LifecyclePause();
        }

        @Bean
        static BeanPostProcessor lifecyclePausePostProcessor(LifecyclePause pause) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof OnboardSystemConfigurationService)) {
                        return bean;
                    }
                    ProxyFactory proxyFactory = new ProxyFactory(bean);
                    proxyFactory.setProxyTargetClass(true);
                    proxyFactory.addAdvice((MethodInterceptor) invocation -> {
                        pause.before(invocation.getMethod().getName());
                        return invocation.proceed();
                    });
                    return proxyFactory.getProxy();
                }
            };
        }
    }

    static final class LifecyclePause {
        private final AtomicReference<String> armedMethod = new AtomicReference<>();
        private volatile CountDownLatch entered = new CountDownLatch(1);
        private volatile CountDownLatch released = new CountDownLatch(1);

        void arm(String methodName) {
            reset();
            if (!armedMethod.compareAndSet(null, methodName)) {
                throw new IllegalStateException("lifecycle pause is already armed");
            }
        }

        void before(String methodName) throws InterruptedException {
            String expected = armedMethod.get();
            if (methodName.equals(expected) && armedMethod.compareAndSet(expected, null)) {
                entered.countDown();
                if (!released.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("lifecycle pause release timed out");
                }
            }
        }

        void awaitEntered() throws InterruptedException {
            if (!entered.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("lifecycle method was not reached");
            }
        }

        void release() {
            released.countDown();
        }

        void reset() {
            released.countDown();
            armedMethod.set(null);
            entered = new CountDownLatch(1);
            released = new CountDownLatch(1);
        }
    }

    static final class FakeControlClient implements JtGatewayControlClient {
        private final DataSource dataSource;
        boolean available = true;
        boolean committedStateObserved;
        final java.util.ArrayList<DisconnectRequest> requests = new java.util.ArrayList<>();

        FakeControlClient(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public boolean disconnect(UUID terminalId, String reasonCode) {
            requests.add(new DisconnectRequest(terminalId, reasonCode));
            try (var connection = dataSource.getConnection();
                    var statement = connection.prepareStatement(
                            "select count(*) from audit_logs where entity_id = ? and action = 'JT_TERMINAL_DISCONNECT_REQUESTED'")) {
                statement.setObject(1, terminalId);
                try (var result = statement.executeQuery()) {
                    result.next();
                    committedStateObserved = result.getInt(1) == 1;
                }
            } catch (java.sql.SQLException exception) {
                throw new IllegalStateException(exception);
            }
            return available;
        }
    }

    record DisconnectRequest(UUID terminalId, String reasonCode) {
    }

    private record ReplacementFixture(
            OnboardSystem system, JtTerminal oldTerminal, JtTerminal replacement) {
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
