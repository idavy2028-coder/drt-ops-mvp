package com.idavy.drtops.domain.onboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import com.idavy.drtops.domain.location.CanonicalPositionIngress;
import com.idavy.drtops.domain.location.GatewayIngressEnvelope;
import com.idavy.drtops.domain.location.GatewayIngressRouter;
import com.idavy.drtops.domain.location.GpsLocationIngressService;
import com.idavy.drtops.domain.location.LocationQualityDecision;
import com.idavy.drtops.domain.location.LocationQualityStatus;
import com.idavy.drtops.domain.location.CoordinateTransformer;
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
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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

@SpringBootTest
@Import({OnboardTestFixtures.class,
        OnboardSystemConfigurationServiceTest.PreviewPauseConfiguration.class})
class OnboardSystemConfigurationServiceTest {

    @org.springframework.test.context.DynamicPropertySource
    static void dataSourceProperties(
            org.springframework.test.context.DynamicPropertyRegistry registry) {
        DataSourceBootstrap bootstrap = bootstrapDataSource(
                System.getProperty("drt.integration.r4-configuration.jdbc-url", ""),
                System.getProperty("drt.integration.r4-configuration.username", ""),
                System.getProperty("drt.integration.r4-configuration.password", ""),
                Boolean.getBoolean("drt.integration.r4-configuration.external-ephemeral"),
                System.getProperty("drt.integration.r4-configuration.cleanup-nonce", ""),
                System.getProperty("drt.integration.r4-configuration.ddl-auto", ""),
                OnboardSystemConfigurationServiceTest::verifyExternalBootstrapTarget);
        registry.add("spring.datasource.url", bootstrap::jdbcUrl);
        registry.add("spring.datasource.username", bootstrap::username);
        registry.add("spring.datasource.password", bootstrap::password);
        registry.add("spring.datasource.driver-class-name", bootstrap::driverClassName);
        registry.add("spring.flyway.enabled", () -> false);
        registry.add("spring.jpa.hibernate.ddl-auto", bootstrap::ddlAuto);
    }

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
    GatewayIngressRouter ingressRouter;

    @Autowired
    com.fasterxml.jackson.databind.ObjectMapper objectMapper;

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

    @Autowired
    ConfigurationIngressRacePause configurationIngressRacePause;

    @BeforeEach
    void setUp() {
        if (isPostgreSql()) {
            requirePostgreSqlEphemeralCleanupPermission();
            jdbcTemplate.execute("""
                    truncate table
                      audit_logs,
                      jt_gateway_ingress_receipts,
                      jt_gateway_audit_events,
                      vehicles,
                      jt_terminals
                    cascade
                    """);
        } else {
            fixtures.clear();
        }
    }

    private void requirePostgreSqlEphemeralCleanupPermission() {
        boolean explicitlyEnabled = Boolean.getBoolean(
                "drt.integration.r4-configuration.external-ephemeral");
        String nonce = System.getProperty(
                "drt.integration.r4-configuration.cleanup-nonce", "");
        String metadataUrl;
        try (java.sql.Connection connection = java.util.Objects.requireNonNull(
                jdbcTemplate.getDataSource(), "dataSource").getConnection()) {
            metadataUrl = connection.getMetaData().getURL();
        } catch (SQLException failure) {
            throw cleanupForbidden();
        }
        requireExternalEphemeralCleanupTarget(explicitlyEnabled, metadataUrl, nonce);
        try {
            int totalSentinels = jdbcTemplate.queryForObject(
                    "select count(*) from r4_configuration_test_sentinel",
                    Integer.class);
            int matchingSentinels = jdbcTemplate.queryForObject(
                    "select count(*) from r4_configuration_test_sentinel where cleanup_nonce = ?",
                    Integer.class,
                    nonce);
            requireExternalEphemeralCleanupPermission(
                    explicitlyEnabled,
                    metadataUrl,
                    nonce,
                    totalSentinels,
                    matchingSentinels);
        } catch (org.springframework.dao.DataAccessException failure) {
            throw cleanupForbidden();
        }
    }

    private static void requireExternalEphemeralCleanupPermission(
            boolean explicitlyEnabled,
            String metadataUrl,
            String nonce,
            int totalSentinels,
            int matchingSentinels) {
        requireExternalEphemeralCleanupTarget(explicitlyEnabled, metadataUrl, nonce);
        if (totalSentinels != 1 || matchingSentinels != 1) {
            throw cleanupForbidden();
        }
    }

    private static void requireExternalEphemeralCleanupTarget(
            boolean explicitlyEnabled,
            String metadataUrl,
            String nonce) {
        if (!explicitlyEnabled
                || nonce == null
                || !nonce.matches("[0-9a-f]{32,}")) {
            throw cleanupForbidden();
        }
        java.util.regex.Matcher target = java.util.regex.Pattern.compile(
                        "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):(\\d+)/"
                                + "r4_configuration_[0-9a-f]{12,}(?:\\?.*)?")
                .matcher(metadataUrl == null ? "" : metadataUrl);
        if (!target.matches()) {
            throw cleanupForbidden();
        }
        int port;
        try {
            port = Integer.parseInt(target.group(1));
        } catch (NumberFormatException invalidPort) {
            throw cleanupForbidden();
        }
        if (port < 1 || port > 65_535 || port == 5_432) {
            throw cleanupForbidden();
        }
    }

    private static IllegalStateException cleanupForbidden() {
        return new IllegalStateException("R4_EXTERNAL_EPHEMERAL_CLEANUP_FORBIDDEN");
    }

    private static DataSourceBootstrap bootstrapDataSource(
            String externalJdbcUrl,
            String externalUsername,
            String externalPassword,
            boolean explicitlyEnabled,
            String nonce,
            String callerDdlAuto,
            ExternalBootstrapVerifier verifier) {
        if (externalJdbcUrl == null || externalJdbcUrl.isBlank()) {
            return new DataSourceBootstrap(
                    "jdbc:h2:mem:onboard_configuration_"
                            + UUID.randomUUID().toString().replace("-", "")
                            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                    "sa",
                    "",
                    "org.h2.Driver",
                    "create-drop");
        }
        requireExternalEphemeralCleanupTarget(
                explicitlyEnabled, externalJdbcUrl, nonce);
        java.util.Objects.requireNonNull(verifier, "verifier").verify(
                externalJdbcUrl,
                externalUsername == null ? "" : externalUsername,
                externalPassword == null ? "" : externalPassword,
                nonce);
        return new DataSourceBootstrap(
                externalJdbcUrl,
                externalUsername == null ? "" : externalUsername,
                externalPassword == null ? "" : externalPassword,
                "org.postgresql.Driver",
                "none");
    }

    private static void verifyExternalBootstrapTarget(
            String targetJdbcUrl,
            String username,
            String password,
            String nonce) {
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                targetJdbcUrl, username, password)) {
            String metadataUrl = connection.getMetaData().getURL();
            if (!targetJdbcUrl.equals(metadataUrl)) {
                throw cleanupForbidden();
            }
            requireExternalEphemeralCleanupTarget(true, metadataUrl, nonce);
            int totalSentinels;
            try (java.sql.Statement statement = connection.createStatement();
                    java.sql.ResultSet rows = statement.executeQuery(
                            "select count(*) from r4_configuration_test_sentinel")) {
                if (!rows.next()) {
                    throw cleanupForbidden();
                }
                totalSentinels = rows.getInt(1);
            }
            int matchingSentinels;
            try (java.sql.PreparedStatement query = connection.prepareStatement(
                    "select count(*) from r4_configuration_test_sentinel "
                            + "where cleanup_nonce = ?")) {
                query.setString(1, nonce);
                try (java.sql.ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) {
                        throw cleanupForbidden();
                    }
                    matchingSentinels = rows.getInt(1);
                }
            }
            requireExternalEphemeralCleanupPermission(
                    true, metadataUrl, nonce, totalSentinels, matchingSentinels);
        } catch (SQLException failure) {
            throw cleanupForbidden();
        }
    }

    private boolean isPostgreSql() {
        try (java.sql.Connection connection = java.util.Objects.requireNonNull(
                jdbcTemplate.getDataSource(), "dataSource").getConnection()) {
            return "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName());
        } catch (SQLException failure) {
            throw new IllegalStateException("failed to inspect test database", failure);
        }
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
    void rejectsRoleProfileMismatchAndTransportIdentityMismatch() {
        OnboardSystem dispatchSystem = fixtures.activeSystem(OperatingMode.SAFETY_MONITOR_ONLY);
        fixtures.verifyDispatchAndLocation("profile-dispatch");
        assertThatThrownBy(() -> service.preview(
                dispatchSystem.getVehicleId(),
                command(dispatchSystem.getVersion(), OperatingMode.SAFETY_MONITOR_ONLY,
                        List.of(deviceWithProfiles(
                                "profile-dispatch", NetworkMode.DIRECT_CELLULAR,
                                Set.of(Role.DISPATCH),
                                new ProtocolProfiles(
                                        "JT808_2019", "NONE", "NONE", "NONE", 30, 60))))))
                .isInstanceOf(OnboardConfigurationConflictException.class)
                .hasMessage("ROLE_PROTOCOL_PROFILE_MISMATCH:DISPATCH");

        OnboardSystem safetySystem = fixtures.activeSystem(OperatingMode.SAFETY_MONITOR_ONLY);
        fixtures.verifySafetyVideoAndLocation("profile-safety");
        assertThatThrownBy(() -> service.preview(
                safetySystem.getVehicleId(),
                command(safetySystem.getVersion(), OperatingMode.SAFETY_MONITOR_ONLY,
                        List.of(deviceWithProfiles(
                                "profile-safety", NetworkMode.DIRECT_CELLULAR,
                                Set.of(Role.ACTIVE_SAFETY),
                                new ProtocolProfiles(
                                        "JT808_2019", "NONE", "NONE", "NONE", 30, 60))))))
                .isInstanceOf(OnboardConfigurationConflictException.class)
                .hasMessage("ROLE_PROTOCOL_PROFILE_MISMATCH:ACTIVE_SAFETY");

        OnboardSystem videoSystem = fixtures.activeSystem(OperatingMode.SAFETY_MONITOR_ONLY);
        fixtures.verifySafetyVideoAndLocation("profile-video");
        assertThatThrownBy(() -> service.preview(
                videoSystem.getVehicleId(),
                command(videoSystem.getVersion(), OperatingMode.SAFETY_MONITOR_ONLY,
                        List.of(deviceWithProfiles(
                                "profile-video", NetworkMode.DIRECT_CELLULAR,
                                Set.of(Role.VIDEO),
                                new ProtocolProfiles(
                                        "JT808_2019", "NONE", "NONE", "NONE", 30, 60))))))
                .isInstanceOf(OnboardConfigurationConflictException.class)
                .hasMessage("ROLE_PROTOCOL_PROFILE_MISMATCH:VIDEO");

        OnboardSystem transportSystem = fixtures.activeSystem(OperatingMode.SAFETY_MONITOR_ONLY);
        fixtures.terminal("profile-transport");
        assertThatThrownBy(() -> service.preview(
                transportSystem.getVehicleId(),
                command(transportSystem.getVersion(), OperatingMode.SAFETY_MONITOR_ONLY,
                        List.of(deviceWithProfiles(
                                "profile-transport", NetworkMode.DIRECT_CELLULAR,
                                Set.of(),
                                new ProtocolProfiles(
                                        "JT808_2013", "NONE", "NONE", "NONE", 30, 60))))))
                .isInstanceOf(OnboardConfigurationConflictException.class)
                .hasMessage("TRANSPORT_PROFILE_IDENTITY_MISMATCH");
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
                        new ProtocolProfiles("JT808_2019", "GBT28787_2023", "NONE", "NONE", 15, 60)),
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

    @Test
    void removingActiveLocationMemberClearsRuntimeAndMarksSnapshotStale() {
        ActiveLocationFixture fixture = activeDualDeviceLocationFixture();

        service.apply(
                fixture.vehicleId(),
                fixture.commandWithoutActiveTerminal(),
                OnboardTestFixtures.ACTOR_ID);

        OnboardSystemRuntimeState runtime = runtimeStateRepository
                .findById(fixture.systemId()).orElseThrow();
        Vehicle vehicle = vehicleRepository
                .findById(fixture.vehicleId()).orElseThrow();
        assertThat(runtime.getActiveLocationTerminalId()).isNull();
        assertThat(runtime.getPrimaryRecoveryStreak()).isZero();
        assertThat(runtime.getPrimaryTerminalCursorAt()).isNull();
        assertThat(runtime.getBackupTerminalCursorAt()).isNull();
        assertThat(vehicle.isCurrentLocationStale()).isTrue();
    }

    @Test
    void unrelatedWanChangePreservesStillLegalActiveLocationSource() {
        ActiveLocationFixture fixture = activeDualDeviceLocationFixture();

        service.apply(
                fixture.vehicleId(),
                fixture.commandMovingOnlyWanUplink(),
                OnboardTestFixtures.ACTOR_ID);

        assertThat(runtimeStateRepository.findById(fixture.systemId())
                .orElseThrow().getActiveLocationTerminalId())
                .isEqualTo(fixture.activeTerminalId());
        assertThat(vehicleRepository.findById(fixture.vehicleId())
                .orElseThrow().isCurrentLocationStale()).isFalse();
    }

    @Test
    void movingPrimaryRoleLetsTheNextLegalPrimarySelectWithoutProvenanceMismatch()
            throws Exception {
        ActiveLocationFixture fixture = activeDualDeviceLocationFixture();
        service.apply(
                fixture.vehicleId(),
                fixture.commandWithoutActiveTerminal(),
                OnboardTestFixtures.ACTOR_ID);
        UUID nextPrimary = roleRepository
                .findActiveByOnboardSystemIdOrderByValidFromAsc(fixture.systemId()).stream()
                .filter(role -> role.getRole() == Role.LOCATION_PRIMARY)
                .map(OnboardDeviceRoleAssignment::getTerminalId)
                .findFirst().orElseThrow();
        Instant locatedAt = Instant.now().plusSeconds(1);
        CanonicalPositionIngress ingress = locationIngress(
                fixture.vehicleId(), fixture.systemId(), nextPrimary,
                Role.LOCATION_PRIMARY, locatedAt, "d".repeat(64));
        GatewayIngressEnvelope envelope = new GatewayIngressEnvelope(
                1,
                UUID.randomUUID(),
                "POSITION",
                locatedAt,
                objectMapper.writeValueAsString(ingress));

        assertThat(ingressRouter.ingest(List.of(envelope)).getFirst().status())
                .isEqualTo("ACCEPTED");
        assertThat(runtimeStateRepository.findById(fixture.systemId()).orElseThrow()
                .getActiveLocationTerminalId()).isEqualTo(nextPrimary);
        assertThat(vehicleRepository.findById(fixture.vehicleId()).orElseThrow()
                .getCurrentLocationOnboardSystemId()).isEqualTo(fixture.systemId());
    }

    @Test
    void replacementNeverClaimsTheOldSnapshotForTheNewTerminal() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String oldCode = "r4-old-" + suffix;
        String backupCode = "r4-backup-" + suffix;
        String replacementCode = "r4-new-" + suffix;
        String plate = "R4R-" + suffix;
        fixtures.configureDualDeviceSystem(oldCode, backupCode, plate);
        fixtures.verifyDispatchAndLocation(replacementCode);
        Vehicle vehicle = vehicleRepository.findByPlateNumber(plate).orElseThrow();
        OnboardSystem system = systemRepository
                .findActiveByVehicleId(vehicle.getId()).orElseThrow();
        JtTerminal oldTerminal = fixtures.terminal(oldCode);
        JtTerminal replacement = fixtures.terminal(replacementCode);
        service.apply(
                vehicle.getId(),
                command(
                        system.getVersion(),
                        OperatingMode.DISPATCH_SERVICE,
                        List.of(
                                device(oldCode, NetworkMode.DIRECT_CELLULAR,
                                        Set.of(Role.DISPATCH, Role.LOCATION_PRIMARY,
                                                Role.WAN_UPLINK)),
                                device(backupCode, NetworkMode.SHARED_LAN_CLIENT,
                                        Set.of(Role.LOCATION_BACKUP, Role.ACTIVE_SAFETY,
                                                Role.VIDEO)),
                                deviceWithProfiles(
                                        replacementCode,
                                        NetworkMode.DIRECT_CELLULAR,
                                        Set.of(),
                                        new ProtocolProfiles(
                                                "JT808_2019", "GBT28787_2023",
                                                "NONE", "NONE", 30, 60)))),
                OnboardTestFixtures.ACTOR_ID);
        persistActiveLocation(system.getId(), vehicle.getId(), oldTerminal.getId());

        service.replaceTerminal(
                oldTerminal.getId(),
                fixtures.terminal(oldCode).getVersion(),
                replacement.getId(),
                fixtures.terminal(replacementCode).getVersion(),
                "replace synthetic active location terminal",
                OnboardTestFixtures.ACTOR_ID);

        OnboardSystemRuntimeState runtime = runtimeStateRepository
                .findById(system.getId()).orElseThrow();
        Vehicle after = vehicleRepository.findById(vehicle.getId()).orElseThrow();
        assertThat(runtime.getActiveLocationTerminalId()).isNull();
        assertThat(after.getCurrentLocationTerminalId()).isEqualTo(oldTerminal.getId());
        assertThat(after.getCurrentLocationTerminalId()).isNotEqualTo(replacement.getId());
        assertThat(after.isCurrentLocationStale()).isTrue();
    }

    @Test
    void configurationLockFirstMakesIngressWaitAndRejectCompleteNewAuthority()
            throws Exception {
        ActiveLocationFixture fixture = activeDualDeviceLocationFixture();
        Vehicle before = vehicleRepository.findById(fixture.vehicleId()).orElseThrow();
        UUID originalSnapshotEventId = before.getCurrentLocationEventId();
        long originalEventCount = locationEventRepository.count();
        UUID rejectedKey = UUID.randomUUID();
        GatewayIngressEnvelope envelope = nextPrimaryEnvelope(
                fixture, before, rejectedKey, "e".repeat(64));
        CountDownLatch configurationLocked = new CountDownLatch(1);
        CountDownLatch releaseConfiguration = new CountDownLatch(1);
        CountDownLatch contenderBeforeSystemLock = new CountDownLatch(1);
        CountDownLatch contenderAfterSystemLock = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        configurationIngressRacePause.arm(
                "r4-configuration-lock-first",
                configurationLocked,
                releaseConfiguration,
                "r4-ingress-waits-for-configuration",
                contenderBeforeSystemLock,
                contenderAfterSystemLock);
        try {
            Future<?> configuration = executor.submit(() -> {
                Thread.currentThread().setName("r4-configuration-lock-first");
                service.apply(
                        fixture.vehicleId(), fixture.commandWithoutActiveTerminal(),
                        OnboardTestFixtures.ACTOR_ID);
            });
            assertThat(configurationLocked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<GpsLocationIngressService.Result> position = executor.submit(() -> {
                Thread.currentThread().setName("r4-ingress-waits-for-configuration");
                return ingressRouter.ingest(List.of(envelope)).getFirst();
            });
            assertThat(contenderBeforeSystemLock.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(contenderAfterSystemLock.await(250, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(completedWithin(position, 250)).isFalse();

            releaseConfiguration.countDown();
            configuration.get(5, TimeUnit.SECONDS);
            assertThat(contenderAfterSystemLock.await(5, TimeUnit.SECONDS)).isTrue();
            GpsLocationIngressService.Result result = position.get(5, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo("REJECTED");
            assertThat(result.reasonCodes())
                    .containsExactly("ONBOARD_PROVENANCE_MISMATCH");
        } finally {
            releaseConfiguration.countDown();
            configurationIngressRacePause.disarm();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        OnboardSystemRuntimeState runtime = runtimeStateRepository
                .findById(fixture.systemId()).orElseThrow();
        Vehicle vehicle = vehicleRepository.findById(fixture.vehicleId()).orElseThrow();
        assertThat(locationEventRepository.count()).isEqualTo(originalEventCount);
        assertThat(locationEventRepository.findByIdempotencyKey(rejectedKey)).isEmpty();
        assertThat(vehicle.getCurrentLocationEventId()).isEqualTo(originalSnapshotEventId);
        assertThat(runtime.getActiveLocationTerminalId()).isNull();
        assertThat(runtime.getLastPrimaryValidGatewayReceivedAt()).isNull();
        assertThat(runtime.getPrimaryTerminalCursorAt()).isNull();
        assertThat(runtime.getBackupTerminalCursorAt()).isNull();
        assertThat(vehicle.isCurrentLocationStale()).isTrue();
        assertNewPrimaryExcludesOldTerminal(fixture);
    }

    @Test
    void ingressLockFirstMakesConfigurationWaitThenResetsAcceptedOldAuthority()
            throws Exception {
        ActiveLocationFixture fixture = activeDualDeviceLocationFixture();
        Vehicle before = vehicleRepository.findById(fixture.vehicleId()).orElseThrow();
        long originalEventCount = locationEventRepository.count();
        UUID acceptedKey = UUID.randomUUID();
        GatewayIngressEnvelope envelope = nextPrimaryEnvelope(
                fixture, before, acceptedKey, "f".repeat(64));
        CountDownLatch ingressLocked = new CountDownLatch(1);
        CountDownLatch releaseIngress = new CountDownLatch(1);
        CountDownLatch contenderBeforeSystemLock = new CountDownLatch(1);
        CountDownLatch contenderAfterSystemLock = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        configurationIngressRacePause.arm(
                "r4-ingress-lock-first",
                ingressLocked,
                releaseIngress,
                "r4-configuration-waits-for-ingress",
                contenderBeforeSystemLock,
                contenderAfterSystemLock);
        try {
            Future<GpsLocationIngressService.Result> position = executor.submit(() -> {
                Thread.currentThread().setName("r4-ingress-lock-first");
                return ingressRouter.ingest(List.of(envelope)).getFirst();
            });
            assertThat(ingressLocked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> configuration = executor.submit(() -> {
                Thread.currentThread().setName("r4-configuration-waits-for-ingress");
                service.apply(
                        fixture.vehicleId(), fixture.commandWithoutActiveTerminal(),
                        OnboardTestFixtures.ACTOR_ID);
            });
            assertThat(contenderBeforeSystemLock.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(contenderAfterSystemLock.await(250, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(completedWithin(configuration, 250)).isFalse();

            releaseIngress.countDown();
            GpsLocationIngressService.Result result = position.get(5, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo("ACCEPTED");
            assertThat(result.reasonCodes()).isEmpty();
            assertThat(contenderAfterSystemLock.await(5, TimeUnit.SECONDS)).isTrue();
            configuration.get(5, TimeUnit.SECONDS);
        } finally {
            releaseIngress.countDown();
            configurationIngressRacePause.disarm();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        VehicleLocationEvent accepted = locationEventRepository
                .findByIdempotencyKey(acceptedKey).orElseThrow();
        assertThat(locationEventRepository.count()).isEqualTo(originalEventCount + 1);
        assertThat(accepted.isSnapshotApplied()).isTrue();
        assertThat(accepted.getVehicleId()).isEqualTo(fixture.vehicleId());
        assertThat(accepted.getOnboardSystemId()).isEqualTo(fixture.systemId());
        assertThat(accepted.getTerminalId()).isEqualTo(fixture.activeTerminalId());

        OnboardSystemRuntimeState runtime = runtimeStateRepository
                .findById(fixture.systemId()).orElseThrow();
        Vehicle vehicle = vehicleRepository.findById(fixture.vehicleId()).orElseThrow();
        assertThat(vehicle.getCurrentLocationEventId()).isEqualTo(accepted.getId());
        assertThat(vehicle.getCurrentLocationOnboardSystemId()).isEqualTo(fixture.systemId());
        assertThat(vehicle.getCurrentLocationTerminalId()).isEqualTo(fixture.activeTerminalId());
        assertThat(vehicle.isCurrentLocationStale()).isTrue();
        assertThat(runtime.getActiveLocationTerminalId()).isNull();
        assertThat(runtime.getLastPrimaryValidGatewayReceivedAt()).isNull();
        assertThat(runtime.getPrimaryTerminalCursorAt()).isNull();
        assertThat(runtime.getBackupTerminalCursorAt()).isNull();
        assertNewPrimaryExcludesOldTerminal(fixture);
    }

    @Test
    void cleanupGuardRejectsMissingExplicitOptInWithoutTouchingADatabase() {
        assertThatThrownBy(() -> requireExternalEphemeralCleanupPermission(
                false,
                "jdbc:postgresql://127.0.0.1:15432/r4_configuration_0123456789ab",
                "0123456789abcdef0123456789abcdef",
                1,
                1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("R4_EXTERNAL_EPHEMERAL_CLEANUP_FORBIDDEN");
    }

    @Test
    void cleanupGuardRejectsNonLoopbackMetadataUrlWithoutTouchingADatabase() {
        assertThatThrownBy(() -> requireExternalEphemeralCleanupPermission(
                true,
                "jdbc:postgresql://database.example:15432/r4_configuration_0123456789ab",
                "0123456789abcdef0123456789abcdef",
                1,
                1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("R4_EXTERNAL_EPHEMERAL_CLEANUP_FORBIDDEN");
    }

    @Test
    void cleanupGuardRejectsWrongDatabaseNameWithoutTouchingADatabase() {
        assertThatThrownBy(() -> requireExternalEphemeralCleanupPermission(
                true,
                "jdbc:postgresql://127.0.0.1:15432/composite_onboard",
                "0123456789abcdef0123456789abcdef",
                1,
                1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("R4_EXTERNAL_EPHEMERAL_CLEANUP_FORBIDDEN");
    }

    @Test
    void cleanupGuardRejectsSentinelNonceMismatchWithoutTouchingADatabase() {
        assertThatThrownBy(() -> requireExternalEphemeralCleanupPermission(
                true,
                "jdbc:postgresql://localhost:15432/r4_configuration_0123456789ab",
                "0123456789abcdef0123456789abcdef",
                1,
                0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("R4_EXTERNAL_EPHEMERAL_CLEANUP_FORBIDDEN");
    }

    @Test
    void bootstrapDefaultsToUniqueH2AndCreateDropWithoutCallingExternalVerifier() {
        AtomicBoolean verifierCalled = new AtomicBoolean();

        DataSourceBootstrap first = bootstrapDataSource(
                "", "", "", false, "", "none",
                (target, username, password, nonce) -> verifierCalled.set(true));
        DataSourceBootstrap second = bootstrapDataSource(
                null, "", "", false, "", "none",
                (target, username, password, nonce) -> verifierCalled.set(true));

        assertThat(first.jdbcUrl()).startsWith("jdbc:h2:mem:onboard_configuration_");
        assertThat(second.jdbcUrl()).startsWith("jdbc:h2:mem:onboard_configuration_");
        assertThat(first.jdbcUrl()).isNotEqualTo(second.jdbcUrl());
        assertThat(first.driverClassName()).isEqualTo("org.h2.Driver");
        assertThat(first.username()).isEqualTo("sa");
        assertThat(first.password()).isEmpty();
        assertThat(first.ddlAuto()).isEqualTo("create-drop");
        assertThat(verifierCalled).isFalse();
    }

    @Test
    void bootstrapExternalForcesNoneEvenWhenCallerRequestsCreateDrop() {
        AtomicBoolean verifierCalled = new AtomicBoolean();
        String nonce = "0123456789abcdef0123456789abcdef";

        DataSourceBootstrap bootstrap = bootstrapDataSource(
                "jdbc:postgresql://127.0.0.1:15432/r4_configuration_0123456789ab",
                "composite",
                "synthetic-password",
                true,
                nonce,
                "create-drop",
                (target, username, password, actualNonce) -> {
                    verifierCalled.set(true);
                    assertThat(actualNonce).isEqualTo(nonce);
                });

        assertThat(bootstrap.driverClassName()).isEqualTo("org.postgresql.Driver");
        assertThat(bootstrap.ddlAuto()).isEqualTo("none");
        assertThat(verifierCalled).isTrue();
    }

    @Test
    void bootstrapRejectsMissingExternalOptInBeforeCallingVerifier() {
        AtomicBoolean verifierCalled = new AtomicBoolean();

        assertThatThrownBy(() -> bootstrapDataSource(
                "jdbc:postgresql://127.0.0.1:15432/r4_configuration_0123456789ab",
                "composite",
                "synthetic-password",
                false,
                "0123456789abcdef0123456789abcdef",
                "create-drop",
                (target, username, password, nonce) -> verifierCalled.set(true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("R4_EXTERNAL_EPHEMERAL_CLEANUP_FORBIDDEN");
        assertThat(verifierCalled).isFalse();
    }

    private GatewayIngressEnvelope nextPrimaryEnvelope(
            ActiveLocationFixture fixture,
            Vehicle current,
            UUID idempotencyKey,
            String digest) throws Exception {
        Instant terminalAt = current.getCurrentLocationReportedAt().toInstant().plusSeconds(1);
        Instant gatewayAt = current.getCurrentLocationGatewayReceivedAt().toInstant().plusSeconds(1);
        CanonicalPositionIngress ingress = locationIngress(
                fixture.vehicleId(), fixture.systemId(), fixture.activeTerminalId(),
                Role.LOCATION_PRIMARY, terminalAt, digest);
        ingress = new CanonicalPositionIngress(
                ingress.terminalId(), ingress.onboardSystemId(), ingress.vehicleId(),
                ingress.sourceRole(), ingress.protocolVersion(), ingress.messageSerialNo(),
                ingress.rawLongitude(), ingress.rawLatitude(), ingress.rawCoordinateSystem(),
                terminalAt, gatewayAt, ingress.alarmBits(), ingress.statusBits(),
                ingress.speedKph(), ingress.directionDegrees(), ingress.altitudeMeters(),
                ingress.satelliteCount(), ingress.payloadDigest());
        return new GatewayIngressEnvelope(
                1, idempotencyKey, "POSITION", gatewayAt,
                objectMapper.writeValueAsString(ingress));
    }

    private void assertNewPrimaryExcludesOldTerminal(ActiveLocationFixture fixture) {
        List<OnboardDeviceRoleAssignment> primaryHistory = roleRepository.findAll().stream()
                .filter(role -> role.getOnboardSystemId().equals(fixture.systemId()))
                .filter(role -> role.getRole() == Role.LOCATION_PRIMARY)
                .sorted(java.util.Comparator.comparing(
                        OnboardDeviceRoleAssignment::getValidFrom))
                .toList();
        OnboardDeviceRoleAssignment oldPrimary = primaryHistory.stream()
                .filter(role -> role.getTerminalId().equals(fixture.activeTerminalId()))
                .findFirst().orElseThrow();
        OnboardDeviceRoleAssignment newPrimary = primaryHistory.stream()
                .filter(role -> role.getStatus()
                        == OnboardDeviceRoleAssignment.Status.ACTIVE)
                .findFirst().orElseThrow();
        assertThat(oldPrimary.getStatus())
                .isEqualTo(OnboardDeviceRoleAssignment.Status.REVOKED);
        assertThat(oldPrimary.getValidTo()).isNotNull();
        assertThat(newPrimary.getTerminalId())
                .isNotEqualTo(fixture.activeTerminalId());
        assertThat(newPrimary.getStatus())
                .isEqualTo(OnboardDeviceRoleAssignment.Status.ACTIVE);
        assertThat(newPrimary.getValidTo()).isNull();
        assertThat(primaryHistory).filteredOn(role ->
                        role.getStatus() == OnboardDeviceRoleAssignment.Status.ACTIVE)
                .singleElement();
        assertThat(oldPrimary.getValidTo())
                .isBeforeOrEqualTo(newPrimary.getValidFrom());

        List<OnboardDeviceMembership> membershipHistory = membershipRepository
                .findAll().stream()
                .filter(membership -> membership.getOnboardSystemId()
                        .equals(fixture.systemId()))
                .toList();
        OnboardDeviceMembership oldMembership = membershipHistory.stream()
                .filter(membership -> membership.getTerminalId()
                        .equals(fixture.activeTerminalId()))
                .findFirst().orElseThrow();
        assertThat(oldMembership.getStatus())
                .isEqualTo(OnboardDeviceMembership.Status.REMOVED);
        assertThat(oldMembership.getValidTo()).isNotNull();
        assertThat(membershipHistory).filteredOn(membership ->
                        membership.getTerminalId().equals(newPrimary.getTerminalId())
                                && membership.getStatus()
                                        == OnboardDeviceMembership.Status.ACTIVE
                                && membership.getValidTo() == null)
                .singleElement();
        membershipHistory.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        OnboardDeviceMembership::getTerminalId))
                .values()
                .forEach(history -> {
                    List<OnboardDeviceMembership> ordered = history.stream()
                            .sorted(java.util.Comparator.comparing(
                                    OnboardDeviceMembership::getValidFrom))
                            .toList();
                    for (int index = 1; index < ordered.size(); index++) {
                        assertThat(ordered.get(index - 1).getValidTo()).isNotNull();
                        assertThat(ordered.get(index - 1).getValidTo())
                                .isBeforeOrEqualTo(ordered.get(index).getValidFrom());
                    }
                });
    }

    private ActiveLocationFixture activeDualDeviceLocationFixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String dispatchCode = "r4-dispatch-" + suffix;
        String recorderCode = "r4-recorder-" + suffix;
        String plate = "R4-" + suffix;
        fixtures.configureDualDeviceSystem(dispatchCode, recorderCode, plate);
        Vehicle vehicle = vehicleRepository.findByPlateNumber(plate).orElseThrow();
        OnboardSystem system = systemRepository
                .findActiveByVehicleId(vehicle.getId()).orElseThrow();
        JtTerminal dispatch = fixtures.terminal(dispatchCode);
        OnboardSystemRuntimeState runtime = runtimeStateRepository
                .findById(system.getId()).orElseThrow();
        OffsetDateTime locatedAt = runtime.getUpdatedAt();
        CanonicalPositionIngress ingress = locationIngress(
                vehicle.getId(), system.getId(), dispatch.getId(),
                Role.LOCATION_PRIMARY, locatedAt.toInstant(), "c".repeat(64));
        VehicleLocationEvent event = VehicleLocationEvent.recordGps(
                vehicle.getId(),
                dispatch.getId(),
                ingress,
                new CoordinateTransformer.StandardizedCoordinate(
                        new java.math.BigDecimal("120.155"),
                        new java.math.BigDecimal("30.274"),
                        "GCJ02",
                        "SYNTHETIC_R4"),
                new LocationQualityDecision(
                        LocationQualityStatus.GOOD, Set.of(), true, true),
                UUID.randomUUID(),
                ingress.payloadDigest(),
                locatedAt,
                locatedAt.toInstant(),
                false);
        event.markSnapshotApplied();
        event = locationEventRepository.saveAndFlush(event);
        vehicle.applyGpsLocationSnapshot(event);
        vehicleRepository.saveAndFlush(vehicle);
        runtime.applyLocationArbitration(
                dispatch.getId(),
                true,
                0,
                locatedAt,
                locatedAt,
                null,
                true,
                locatedAt);
        runtimeStateRepository.saveAndFlush(runtime);
        long version = systemRepository.findById(system.getId()).orElseThrow().getVersion();
        ConfigurationCommand withoutActive = command(
                version,
                OperatingMode.SAFETY_MONITOR_ONLY,
                List.of(device(
                        recorderCode,
                        NetworkMode.DIRECT_CELLULAR,
                        Set.of(Role.LOCATION_PRIMARY, Role.ACTIVE_SAFETY,
                                Role.VIDEO, Role.WAN_UPLINK))));
        ConfigurationCommand wanOnly = command(
                version,
                OperatingMode.DISPATCH_SERVICE,
                List.of(
                        device(dispatchCode, NetworkMode.SHARED_LAN_CLIENT,
                                Set.of(Role.DISPATCH, Role.LOCATION_PRIMARY)),
                        device(recorderCode, NetworkMode.DIRECT_CELLULAR,
                                Set.of(Role.LOCATION_BACKUP, Role.ACTIVE_SAFETY,
                                        Role.VIDEO, Role.WAN_UPLINK))));
        return new ActiveLocationFixture(
                vehicle.getId(), system.getId(), dispatch.getId(),
                withoutActive, wanOnly);
    }

    private void persistActiveLocation(
            UUID systemId,
            UUID vehicleId,
            UUID terminalId) {
        OnboardSystemRuntimeState runtime = runtimeStateRepository
                .findById(systemId).orElseThrow();
        OffsetDateTime locatedAt = runtime.getUpdatedAt();
        CanonicalPositionIngress ingress = locationIngress(
                vehicleId, systemId, terminalId,
                Role.LOCATION_PRIMARY, locatedAt.toInstant(), "f".repeat(64));
        VehicleLocationEvent event = VehicleLocationEvent.recordGps(
                vehicleId,
                terminalId,
                ingress,
                new CoordinateTransformer.StandardizedCoordinate(
                        new java.math.BigDecimal("120.155"),
                        new java.math.BigDecimal("30.274"),
                        "GCJ02",
                        "SYNTHETIC_R4_REPLACEMENT"),
                new LocationQualityDecision(
                        LocationQualityStatus.GOOD, Set.of(), true, true),
                UUID.randomUUID(),
                ingress.payloadDigest(),
                locatedAt,
                locatedAt.toInstant(),
                false);
        event.markSnapshotApplied();
        event = locationEventRepository.saveAndFlush(event);
        Vehicle vehicle = vehicleRepository.findById(vehicleId).orElseThrow();
        vehicle.applyGpsLocationSnapshot(event);
        vehicleRepository.saveAndFlush(vehicle);
        runtime.applyLocationArbitration(
                terminalId, true, 0,
                locatedAt, locatedAt, null,
                true, locatedAt);
        runtimeStateRepository.saveAndFlush(runtime);
    }

    private static CanonicalPositionIngress locationIngress(
            UUID vehicleId,
            UUID systemId,
            UUID terminalId,
            Role role,
            Instant locatedAt,
            String digest) {
        return new CanonicalPositionIngress(
                terminalId,
                systemId,
                vehicleId,
                role.name(),
                "JT808_2019",
                1,
                new java.math.BigDecimal("120.155"),
                new java.math.BigDecimal("30.274"),
                "GCJ02",
                locatedAt,
                locatedAt,
                0L,
                0x02L,
                java.math.BigDecimal.ZERO,
                0,
                0,
                8,
                digest);
    }

    private record ActiveLocationFixture(
            UUID vehicleId,
            UUID systemId,
            UUID activeTerminalId,
            ConfigurationCommand commandWithoutActiveTerminal,
            ConfigurationCommand commandMovingOnlyWanUplink) { }

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

    private record DataSourceBootstrap(
            String jdbcUrl,
            String username,
            String password,
            String driverClassName,
            String ddlAuto) {
    }

    @FunctionalInterface
    private interface ExternalBootstrapVerifier {
        void verify(String jdbcUrl, String username, String password, String nonce);
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

    static final class ConfigurationIngressRacePause {
        private final AtomicBoolean pauseOnce = new AtomicBoolean();
        private final AtomicBoolean contenderBeforeOnce = new AtomicBoolean();
        private final AtomicBoolean contenderAfterOnce = new AtomicBoolean();
        private volatile String ownerThreadName;
        private volatile CountDownLatch ownerLocked;
        private volatile CountDownLatch releaseOwner;
        private volatile String contenderThreadName;
        private volatile CountDownLatch contenderBeforeSystemLock;
        private volatile CountDownLatch contenderAfterSystemLock;

        void arm(
                String ownerThreadName,
                CountDownLatch ownerLocked,
                CountDownLatch releaseOwner,
                String contenderThreadName,
                CountDownLatch contenderBeforeSystemLock,
                CountDownLatch contenderAfterSystemLock) {
            this.ownerThreadName = ownerThreadName;
            this.ownerLocked = ownerLocked;
            this.releaseOwner = releaseOwner;
            this.contenderThreadName = contenderThreadName;
            this.contenderBeforeSystemLock = contenderBeforeSystemLock;
            this.contenderAfterSystemLock = contenderAfterSystemLock;
            pauseOnce.set(false);
            contenderBeforeOnce.set(false);
            contenderAfterOnce.set(false);
        }

        void afterVehicleLock() {
            if (Thread.currentThread().getName().equals(ownerThreadName)
                    && pauseOnce.compareAndSet(false, true)) {
                ownerLocked.countDown();
                await(releaseOwner);
            }
        }

        void beforeSystemLock() {
            if (Thread.currentThread().getName().equals(contenderThreadName)
                    && contenderBeforeOnce.compareAndSet(false, true)) {
                contenderBeforeSystemLock.countDown();
            }
        }

        void afterSystemLock() {
            if (Thread.currentThread().getName().equals(contenderThreadName)
                    && contenderAfterOnce.compareAndSet(false, true)) {
                contenderAfterSystemLock.countDown();
            }
        }

        void disarm() {
            ownerThreadName = null;
            ownerLocked = null;
            releaseOwner = null;
            contenderThreadName = null;
            contenderBeforeSystemLock = null;
            contenderAfterSystemLock = null;
            pauseOnce.set(false);
            contenderBeforeOnce.set(false);
            contenderAfterOnce.set(false);
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
        @org.springframework.context.annotation.Primary
        com.idavy.drtops.domain.location.ServiceAreaLocationChecker
                configurationGpsAreaChecker() {
            return (longitude, latitude) -> true;
        }

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
        ConfigurationIngressRacePause configurationIngressRacePause() {
            return new ConfigurationIngressRacePause();
        }

        private static EntityManager systemLockEntityManager(
                EntityManager delegate,
                ConfigurationIngressRacePause configurationIngressRacePause) {
            return (EntityManager) Proxy.newProxyInstance(
                    EntityManager.class.getClassLoader(),
                    new Class<?>[] {EntityManager.class},
                    (proxy, method, arguments) -> {
                        Object result = invokeRepository(delegate, method, arguments);
                        if (method.getName().equals("createNativeQuery")
                                && arguments != null
                                && arguments.length > 0
                                && arguments[0] instanceof String sql
                                && systemLockSql(sql)) {
                            return systemLockQuery(
                                    (jakarta.persistence.Query) result,
                                    configurationIngressRacePause);
                        }
                        return result;
                    });
        }

        private static boolean systemLockSql(String sql) {
            return sql.replaceAll("\\s+", " ")
                    .trim()
                    .equalsIgnoreCase(
                            "select id from onboard_systems "
                                    + "where id = :onboardSystemId for update");
        }

        private static jakarta.persistence.Query systemLockQuery(
                jakarta.persistence.Query delegate,
                ConfigurationIngressRacePause configurationIngressRacePause) {
            Object[] proxyReference = new Object[1];
            jakarta.persistence.Query proxy = (jakarta.persistence.Query) Proxy.newProxyInstance(
                    jakarta.persistence.Query.class.getClassLoader(),
                    new Class<?>[] {jakarta.persistence.Query.class},
                    (ignored, method, arguments) -> {
                        boolean lockingCall = method.getName().equals("getResultList");
                        if (lockingCall) {
                            configurationIngressRacePause.beforeSystemLock();
                        }
                        Object result = invokeRepository(delegate, method, arguments);
                        if (lockingCall) {
                            configurationIngressRacePause.afterSystemLock();
                        }
                        return result == delegate ? proxyReference[0] : result;
                    });
            proxyReference[0] = proxy;
            return proxy;
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
                ConfigurationIngressRacePause configurationIngressRacePause,
                ConstraintFailureInjector constraintFailureInjector) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof EntityManager entityManager) {
                        return systemLockEntityManager(
                                entityManager, configurationIngressRacePause);
                    }
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
                    if (bean instanceof VehicleRepository repository) {
                        return Proxy.newProxyInstance(
                                VehicleRepository.class.getClassLoader(),
                                new Class<?>[] {VehicleRepository.class},
                                (proxy, method, arguments) -> {
                                    Object result = invokeRepository(repository, method, arguments);
                                    if (method.getName().equals("findByIdForLocationUpdate")) {
                                        configurationIngressRacePause.afterVehicleLock();
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
        String businessProfile = roles.contains(Role.DISPATCH)
                ? "GBT28787_2023" : "NONE";
        String safetyProfile = roles.contains(Role.ACTIVE_SAFETY)
                ? "JSATL12_2017" : "NONE";
        String mediaProfile = roles.contains(Role.VIDEO)
                ? "JT1078_2016" : "NONE";
        return deviceWithProfiles(terminalCode, networkMode, roles,
                new ProtocolProfiles(
                        "JT808_2019", businessProfile, safetyProfile, mediaProfile, 30, 60));
    }

    private static DeviceConfiguration deviceWithProfiles(
            String terminalCode,
            NetworkMode networkMode,
            Set<Role> roles,
            ProtocolProfiles profiles) {
        return new DeviceConfiguration(terminalCode, networkMode, roles, profiles);
    }
}
