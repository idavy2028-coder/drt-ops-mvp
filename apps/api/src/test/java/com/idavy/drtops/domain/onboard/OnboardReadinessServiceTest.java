package com.idavy.drtops.domain.onboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import com.idavy.drtops.domain.location.CanonicalPositionIngress;
import com.idavy.drtops.domain.location.CoordinateTransformer;
import com.idavy.drtops.domain.location.LocationQualityDecision;
import com.idavy.drtops.domain.location.LocationQualityStatus;
import com.idavy.drtops.domain.location.VehicleLocationEvent;
import com.idavy.drtops.domain.location.VehicleLocationEventRepository;
import com.idavy.drtops.domain.onboard.OnboardDeviceCapability.Capability;
import com.idavy.drtops.domain.onboard.OnboardDeviceCapability.CapabilityStatus;
import com.idavy.drtops.domain.onboard.OnboardDeviceMembership.NetworkMode;
import com.idavy.drtops.domain.onboard.OnboardDeviceProtocolProfile.BusinessProfile;
import com.idavy.drtops.domain.onboard.OnboardDeviceProtocolProfile.MediaProfile;
import com.idavy.drtops.domain.onboard.OnboardDeviceProtocolProfile.SafetyProfile;
import com.idavy.drtops.domain.onboard.OnboardDeviceProtocolProfile.TransportProfile;
import com.idavy.drtops.domain.onboard.OnboardDeviceRoleAssignment.Role;
import com.idavy.drtops.domain.onboard.OnboardReadinessService.OnboardReadiness;
import com.idavy.drtops.domain.onboard.OnboardReadinessService.ReadinessState;
import com.idavy.drtops.domain.terminal.JtTerminal;
import com.idavy.drtops.domain.terminal.JtTerminalRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:onboard_readiness;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import({OnboardTestFixtures.class, OnboardReadinessServiceTest.ClockConfiguration.class})
class OnboardReadinessServiceTest {

    @Autowired OnboardReadinessService service;
    @Autowired OnboardTestFixtures fixtures;
    @Autowired OnboardSystemRepository systemRepository;
    @Autowired OnboardSystemRuntimeStateRepository runtimeRepository;
    @Autowired OnboardDeviceMembershipRepository membershipRepository;
    @Autowired OnboardDeviceRoleAssignmentRepository roleRepository;
    @Autowired OnboardDeviceCapabilityRepository capabilityRepository;
    @Autowired OnboardDeviceProtocolProfileRepository profileRepository;
    @Autowired JtTerminalRepository terminalRepository;
    @Autowired VehicleRepository vehicleRepository;
    @Autowired VehicleLocationEventRepository locationEventRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired MutableClock clock;

    private final List<ExecutorService> executors = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fixtures.clear();
        clock.set(Instant.now().plusSeconds(5));
    }

    @AfterEach
    void tearDown() {
        executors.forEach(ExecutorService::shutdownNow);
    }

    @Test
    void recorderOnlySystemIsSafetyReadyButNotDispatchReady() {
        // Mutation caught: treating every installed vehicle as dispatch-ready.
        OnboardReadiness readiness = service.evaluate(fixtures.recorderOnlyVehicleId());

        assertThat(readiness.connectivity()).isEqualTo(ReadinessState.READY);
        assertThat(readiness.location()).isEqualTo(ReadinessState.READY);
        assertThat(readiness.activeSafety()).isEqualTo(ReadinessState.READY);
        assertThat(readiness.video()).isEqualTo(ReadinessState.READY);
        assertThat(readiness.dispatch()).isEqualTo(ReadinessState.NOT_INSTALLED);
        assertThat(readiness.dispatchEligible()).isFalse();
        assertThat(readiness.overallStatus()).isEqualTo("OPERATIONAL");
    }

    @Test
    void vendorDispatchRequiresAuthenticationAndCurrentLocation() {
        // Mutations caught: omitting authentication or authoritative-location gates.
        OnboardReadiness noAuthentication = service.evaluate(
                readyVehicleWithoutAuthentication());
        OnboardReadiness noLocation = service.evaluate(
                readyVehicleWithoutCurrentLocation());
        OnboardReadiness ready = service.evaluate(fixtures.readyDispatchSystemVehicleId());

        assertThat(noAuthentication.dispatch()).isEqualTo(ReadinessState.UNAVAILABLE);
        assertThat(noAuthentication.dispatchEligible()).isFalse();
        assertThat(noAuthentication.overallStatus()).isEqualTo("OFFLINE");
        assertThat(noLocation.dispatch()).isEqualTo(ReadinessState.READY);
        assertThat(noLocation.location()).isEqualTo(ReadinessState.UNAVAILABLE);
        assertThat(noLocation.dispatchEligible()).isFalse();
        assertThat(noLocation.overallStatus()).isEqualTo("DEGRADED");
        assertThat(ready.dispatch()).isEqualTo(ReadinessState.READY);
        assertThat(ready.location()).isEqualTo(ReadinessState.READY);
        assertThat(ready.dispatchEligible()).isTrue();
        assertThat(ready.overallStatus()).isEqualTo("OPERATIONAL");
    }

    @Test
    void lastSeenDoesNotReplaceExplicitSuccessfulAuthentication() {
        // Mutation caught: inferring authentication from lastSeenAt or registration state.
        UUID vehicleId = readyVehicleWithoutAuthentication();
        UUID terminalId = terminalForRole(vehicleId, Role.DISPATCH).getId();
        OffsetDateTime observedAt = now();
        jdbcTemplate.update("""
                update jt_terminals
                   set status = 'ACTIVE', last_registered_at = ?,
                       last_authenticated_at = null, last_seen_at = ?
                 where id = ?
                """, observedAt, observedAt, terminalId);
        entityManager.clear();

        assertThat(service.evaluate(vehicleId).dispatch()).isEqualTo(ReadinessState.UNAVAILABLE);
        assertThat(service.evaluate(vehicleId).dispatchEligible()).isFalse();
    }

    @Test
    void vehicleFlagAndOperatingModeRemainIndependentEligibilityGates() {
        // Mutations caught: dropping Vehicle.dispatchable or DISPATCH_SERVICE checks.
        UUID nonDispatchable = fixtures.readyDispatchSystemVehicleId();
        jdbcTemplate.update("update vehicles set dispatchable = false where id = ?", nonDispatchable);
        entityManager.clear();

        UUID wrongMode = fixtures.readyDispatchSystemVehicleId();
        OnboardSystem system = system(wrongMode);
        system.changeOperatingMode(
                OnboardSystem.OperatingMode.SAFETY_MONITOR_ONLY,
                OnboardTestFixtures.ACTOR_ID,
                after(system.getUpdatedAt()));
        systemRepository.saveAndFlush(system);

        assertThat(service.evaluate(nonDispatchable).dispatch()).isEqualTo(ReadinessState.READY);
        assertThat(service.evaluate(nonDispatchable).dispatchEligible()).isFalse();
        assertThat(service.evaluate(wrongMode).dispatchEligible()).isFalse();
    }

    @Test
    void disabledCapabilitySuspendedTerminalAndMissingProfileFailClosed() {
        // Mutations caught: accepting DECLARED/DISABLED capability, non-ACTIVE terminal, or no profile.
        UUID disabledCapability = fixtures.readyDispatchSystemVehicleId();
        OnboardDeviceCapability capability = capabilityForRole(
                disabledCapability, Role.DISPATCH, Capability.VENDOR_DISPATCH);
        capability.disable("synthetic readiness disable", after(capability.getUpdatedAt()));
        capabilityRepository.saveAndFlush(capability);

        UUID suspendedTerminal = fixtures.readyDispatchSystemVehicleId();
        JtTerminal suspended = terminalForRole(suspendedTerminal, Role.DISPATCH);
        suspended.suspend();
        terminalRepository.saveAndFlush(suspended);

        UUID missingProfile = fixtures.readyDispatchSystemVehicleId();
        OnboardDeviceProtocolProfile profile = profileForRole(missingProfile, Role.DISPATCH);
        profile.supersede(
                "synthetic profile removal",
                OnboardTestFixtures.ACTOR_ID,
                after(profile.getUpdatedAt()));
        profileRepository.saveAndFlush(profile);

        assertThat(service.evaluate(disabledCapability).dispatch()).isEqualTo(ReadinessState.UNAVAILABLE);
        assertThat(service.evaluate(disabledCapability).dispatchEligible()).isFalse();
        assertThat(service.evaluate(suspendedTerminal).dispatch()).isEqualTo(ReadinessState.UNAVAILABLE);
        assertThat(service.evaluate(suspendedTerminal).dispatchEligible()).isFalse();
        assertThat(service.evaluate(missingProfile).dispatch()).isEqualTo(ReadinessState.UNAVAILABLE);
        assertThat(service.evaluate(missingProfile).location()).isEqualTo(ReadinessState.UNAVAILABLE);
        assertThat(service.evaluate(missingProfile).dispatchEligible()).isFalse();
    }

    @Test
    void gbtProfileWithoutBusinessRegistrationIsUnavailableButReviewedVendorProfileIsReady() {
        // Mutation caught: treating JT808 registration as GB/T 28787 bus-business registration.
        DualSystem gbt = configureGbtDualSystem("gbt-business");
        authenticate(gbt.dispatchTerminal());
        authenticate(gbt.recorderTerminal());
        makeCurrentLocation(gbt.vehicleId(), gbt.dispatchTerminal().getId(), Role.LOCATION_PRIMARY,
                clock.instant(), LocationQualityStatus.GOOD);

        UUID vendorVehicleId = fixtures.readyDispatchSystemVehicleId();

        assertThat(service.evaluate(gbt.vehicleId()).dispatch()).isEqualTo(ReadinessState.UNAVAILABLE);
        assertThat(service.evaluate(gbt.vehicleId()).dispatchEligible()).isFalse();
        assertThat(service.evaluate(vendorVehicleId).dispatch()).isEqualTo(ReadinessState.READY);
        assertThat(service.evaluate(vendorVehicleId).dispatchEligible()).isTrue();
    }

    @Test
    void configuredOperationalBackupIsDegradedButStillDispatchUsable() {
        // Mutation caught: rejecting every backup source or misreporting it as primary READY.
        UUID vehicleId = fixtures.readyDispatchSystemVehicleId();
        JtTerminal backup = addOperationalBackup(vehicleId, "vendor-backup");
        makeCurrentLocation(vehicleId, backup.getId(), Role.LOCATION_BACKUP,
                clock.instant(), LocationQualityStatus.WARNING);

        OnboardReadiness readiness = service.evaluate(vehicleId);

        assertThat(readiness.connectivity()).isEqualTo(ReadinessState.READY);
        assertThat(readiness.location()).isEqualTo(ReadinessState.DEGRADED);
        assertThat(readiness.dispatch()).isEqualTo(ReadinessState.READY);
        assertThat(readiness.dispatchEligible()).isTrue();
        assertThat(readiness.overallStatus()).isEqualTo("DEGRADED");
    }

    @Test
    void primaryEligibilityAndCurrentSourceConsistencyFailClosed() {
        // Mutations caught: ignoring runtime.primaryEligible or accepting mismatched snapshot provenance.
        UUID ineligiblePrimary = fixtures.readyDispatchSystemVehicleId();
        OnboardSystemRuntimeState runtime = runtime(ineligiblePrimary);
        runtime.setPrimaryEligible(false, after(runtime.getUpdatedAt()));
        runtimeRepository.saveAndFlush(runtime);

        UUID mismatchedTerminal = fixtures.readyDispatchSystemVehicleId();
        jdbcTemplate.update(
                "update vehicles set current_location_terminal_id = ? where id = ?",
                UUID.randomUUID(), mismatchedTerminal);
        entityManager.clear();

        UUID nonGpsSource = fixtures.readyDispatchSystemVehicleId();
        jdbcTemplate.update(
                "update vehicles set current_location_source = 'MANUAL_DISPATCHER' where id = ?",
                nonGpsSource);
        entityManager.clear();

        UUID rejectedQuality = fixtures.readyDispatchSystemVehicleId();
        jdbcTemplate.update(
                "update vehicles set current_location_quality_status = 'REJECTED' where id = ?",
                rejectedQuality);
        entityManager.clear();

        assertUnavailableLocation(ineligiblePrimary);
        assertUnavailableLocation(mismatchedTerminal);
        assertUnavailableLocation(nonGpsSource);
        assertUnavailableLocation(rejectedQuality);
    }

    @Test
    void idleFreshnessUsesExactTaskSixMaximumThreshold() {
        // Mutation caught: using 30 seconds alone, one interval, active interval, or a > instead of >= boundary.
        UUID vehicleId = fixtures.readyDispatchSystemVehicleId();
        Instant receivedAt = vehicleRepository.findById(vehicleId).orElseThrow()
                .getCurrentLocationGatewayReceivedAt().toInstant();

        clock.set(receivedAt.plusSeconds(119));
        assertThat(service.evaluate(vehicleId).location()).isEqualTo(ReadinessState.READY);
        assertThat(service.evaluate(vehicleId).dispatchEligible()).isTrue();

        clock.set(receivedAt.plusSeconds(120));
        assertThat(service.evaluate(vehicleId).location()).isEqualTo(ReadinessState.UNAVAILABLE);
        assertThat(service.evaluate(vehicleId).dispatchEligible()).isFalse();
    }

    @Test
    void staleFlagAndMissingTimestampAreNeverGuessedFresh() {
        // Mutations caught: deriving freshness only from terminal state or accepting a missing timestamp.
        UUID stale = fixtures.readyDispatchSystemVehicleId();
        jdbcTemplate.update("update vehicles set current_location_stale = true where id = ?", stale);
        entityManager.clear();

        UUID missingTimestamp = fixtures.readyDispatchSystemVehicleId();
        jdbcTemplate.update(
                "update vehicles set current_location_gateway_received_at = null where id = ?",
                missingTimestamp);
        entityManager.clear();

        assertUnavailableLocation(stale);
        assertUnavailableLocation(missingTimestamp);
    }

    @Test
    void connectivityCoversNotInstalledUnavailableDegradedAndReady() {
        // Mutations caught: collapsing physical-member availability into a boolean online flag.
        UUID notInstalled = fixtures.createVehicle("NO-SYSTEM-" + suffix()).getId();
        UUID unavailable = readyVehicleWithoutAuthentication();
        DualSystem degraded = configureGbtDualSystem("connectivity-degraded");
        authenticate(degraded.dispatchTerminal());
        UUID ready = fixtures.readyDispatchSystemVehicleId();

        assertThat(service.evaluate(notInstalled).connectivity()).isEqualTo(ReadinessState.NOT_INSTALLED);
        assertThat(service.evaluate(notInstalled).overallStatus()).isEqualTo("OFFLINE");
        assertThat(service.evaluate(unavailable).connectivity()).isEqualTo(ReadinessState.UNAVAILABLE);
        assertThat(service.evaluate(unavailable).overallStatus()).isEqualTo("OFFLINE");
        assertThat(service.evaluate(degraded.vehicleId()).connectivity()).isEqualTo(ReadinessState.DEGRADED);
        assertThat(service.evaluate(ready).connectivity()).isEqualTo(ReadinessState.READY);
    }

    @Test
    void missingVehicleOrSystemReturnsSafeOfflineReadiness() {
        // Mutation caught: throwing a 500 for absent vehicle/system state.
        OnboardReadiness missingVehicle = service.evaluate(UUID.randomUUID());
        UUID noSystemVehicle = fixtures.createVehicle("NO-ONBOARD-" + suffix()).getId();
        OnboardReadiness noSystem = service.evaluate(noSystemVehicle);

        assertThat(missingVehicle.connectivity()).isEqualTo(ReadinessState.NOT_INSTALLED);
        assertThat(missingVehicle.dispatchEligible()).isFalse();
        assertThat(missingVehicle.overallStatus()).isEqualTo("OFFLINE");
        assertThat(noSystem.connectivity()).isEqualTo(ReadinessState.NOT_INSTALLED);
        assertThat(noSystem.location()).isEqualTo(ReadinessState.NOT_INSTALLED);
        assertThat(noSystem.overallStatus()).isEqualTo("OFFLINE");
    }

    @Test
    void duplicateOrMismatchedExclusiveRoleTopologyFailsClosed() {
        // Mutations caught: selecting the first duplicate role or trusting a role outside active membership.
        UUID duplicateVehicle = fixtures.readyDispatchSystemVehicleId();
        OnboardSystem duplicateSystem = system(duplicateVehicle);
        JtTerminal duplicateTerminal = fixtures.terminal("dup-" + suffix());
        roleRepository.saveAndFlush(OnboardDeviceRoleAssignment.assign(
                duplicateSystem.getId(), duplicateTerminal.getId(), Role.DISPATCH,
                "synthetic duplicate dispatch", OnboardTestFixtures.ACTOR_ID, now()));

        UUID mismatchVehicle = fixtures.readyDispatchSystemVehicleId();
        OnboardSystem mismatchSystem = system(mismatchVehicle);
        OnboardDeviceRoleAssignment original = roleFor(mismatchVehicle, Role.DISPATCH);
        original.revoke(
                "synthetic mismatch replacement",
                OnboardTestFixtures.ACTOR_ID,
                after(original.getUpdatedAt()));
        roleRepository.saveAndFlush(original);
        JtTerminal outsider = fixtures.terminal("out-" + suffix());
        verifyCapability(outsider.getId(), Capability.VENDOR_DISPATCH);
        roleRepository.saveAndFlush(OnboardDeviceRoleAssignment.assign(
                mismatchSystem.getId(), outsider.getId(), Role.DISPATCH,
                "synthetic non-member dispatch", OnboardTestFixtures.ACTOR_ID, now()));

        assertThat(service.evaluate(duplicateVehicle).dispatch()).isEqualTo(ReadinessState.UNAVAILABLE);
        assertThat(service.evaluate(duplicateVehicle).dispatchEligible()).isFalse();
        assertThat(service.evaluate(mismatchVehicle).dispatch()).isEqualTo(ReadinessState.UNAVAILABLE);
        assertThat(service.evaluate(mismatchVehicle).dispatchEligible()).isFalse();
    }

    @Test
    void evaluationDoesNotAdvanceConfigurationRuntimeOrDeviceVersions() {
        // Mutation caught: a supposedly read-only evaluation touching aggregate/runtime/history rows.
        UUID vehicleId = fixtures.readyDispatchSystemVehicleId();
        VersionSnapshot before = versions(vehicleId);

        service.evaluate(vehicleId);
        service.evaluate(vehicleId);
        entityManager.clear();

        assertThat(versions(vehicleId)).isEqualTo(before);
    }

    @Test
    void readinessValueDoesNotExposeRawIdentityCredentialOrEvidence() {
        // Mutation caught: adding raw physical identity, token digest, or evidence to the returned contract.
        UUID vehicleId = fixtures.readyDispatchSystemVehicleId();
        JtTerminal terminal = terminalForRole(vehicleId, Role.DISPATCH);
        OnboardDeviceCapability capability = capabilityForRole(
                vehicleId, Role.DISPATCH, Capability.VENDOR_DISPATCH);

        String rendered = service.evaluate(vehicleId).toString();

        assertThat(rendered)
                .doesNotContain(terminal.getTerminalPhone())
                .doesNotContain(terminal.getTerminalCode())
                .doesNotContain(terminal.getAuthTokenHash())
                .doesNotContain(capability.getEvidenceRef());
    }

    @Test
    void evaluationWaitsForSystemMutationLockAndReturnsOneStableSnapshot() throws Exception {
        // Mutation caught: reading members/roles/runtime without first taking the system lock.
        UUID vehicleId = fixtures.readyDispatchSystemVehicleId();
        UUID systemId = system(vehicleId).getId();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = executor();
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        Future<?> blocker = executor.submit(() -> transactions.executeWithoutResult(status -> {
            systemRepository.findLockedById(systemId).orElseThrow();
            locked.countDown();
            await(release);
        }));
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

        Future<OnboardReadiness> evaluation = executor.submit(() -> service.evaluate(vehicleId));
        try {
            evaluation.get(200, TimeUnit.MILLISECONDS);
            throw new AssertionError("readiness evaluation bypassed the onboard-system lock");
        } catch (TimeoutException expectedBlocking) {
            assertThat(evaluation.isDone()).isFalse();
        } finally {
            release.countDown();
        }

        blocker.get(5, TimeUnit.SECONDS);
        assertThat(evaluation.get(5, TimeUnit.SECONDS).dispatchEligible()).isTrue();
    }

    @Test
    void evaluationRefreshesVehiclePreloadedBeforeTheSystemLock() {
        // Mutation caught: using a vehicle snapshot cached before the system lock was acquired.
        UUID vehicleId = fixtures.readyDispatchSystemVehicleId();
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        OnboardReadiness readiness = transactions.execute(status -> {
            vehicleRepository.findById(vehicleId).orElseThrow();
            jdbcTemplate.update(
                    "update vehicles set current_location_stale = true where id = ?",
                    vehicleId);
            return service.evaluate(vehicleId);
        });

        assertThat(readiness).isNotNull();
        assertThat(readiness.location()).isEqualTo(ReadinessState.UNAVAILABLE);
        assertThat(readiness.dispatchEligible()).isFalse();
    }

    @Test
    void evaluationRefreshesTerminalPreloadedBeforeTheSystemLock() {
        // Mutation caught: using terminal authentication cached before the system lock was acquired.
        UUID vehicleId = fixtures.readyDispatchSystemVehicleId();
        UUID terminalId = terminalForRole(vehicleId, Role.DISPATCH).getId();
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        OnboardReadiness readiness = transactions.execute(status -> {
            terminalRepository.findById(terminalId).orElseThrow();
            jdbcTemplate.update(
                    "update jt_terminals set last_authenticated_at = null where id = ?",
                    terminalId);
            return service.evaluate(vehicleId);
        });

        assertThat(readiness).isNotNull();
        assertThat(readiness.connectivity()).isEqualTo(ReadinessState.UNAVAILABLE);
        assertThat(readiness.dispatch()).isEqualTo(ReadinessState.UNAVAILABLE);
        assertThat(readiness.dispatchEligible()).isFalse();
    }

    @Test
    void multiTerminalAndMultiVehicleEvaluationAddsOnlyConstantQueriesPerVehicle() {
        // Mutation caught: querying capabilities/profiles and refreshing rows once per terminal.
        UUID singleTerminalVehicle = fixtures.readyDispatchSystemVehicleId();
        UUID multiTerminalVehicle = fixtures.readyDispatchSystemVehicleId();
        for (int index = 0; index < 5; index++) {
            addOperationalMember(multiTerminalVehicle, "m");
        }
        List<UUID> candidateVehicles = new ArrayList<>();
        for (int vehicleIndex = 0; vehicleIndex < 3; vehicleIndex++) {
            UUID candidateVehicle = fixtures.readyDispatchSystemVehicleId();
            for (int terminalIndex = 0; terminalIndex < 5; terminalIndex++) {
                addOperationalMember(candidateVehicle, "c" + vehicleIndex);
            }
            candidateVehicles.add(candidateVehicle);
        }
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            service.evaluate(singleTerminalVehicle);
            long singleTerminalStatements = statistics.getPrepareStatementCount();

            statistics.clear();
            service.evaluate(multiTerminalVehicle);
            long multiTerminalStatements = statistics.getPrepareStatementCount();

            statistics.clear();
            candidateVehicles.forEach(service::evaluate);
            long multiVehicleStatements = statistics.getPrepareStatementCount();

            assertThat(multiTerminalStatements - singleTerminalStatements)
                    .as("adding terminals must not add per-terminal SQL")
                    .isLessThanOrEqualTo(3);
            assertThat(multiVehicleStatements)
                    .as("several multi-terminal candidate vehicles stay linear only by vehicle")
                    .isLessThanOrEqualTo(singleTerminalStatements * candidateVehicles.size() + 9);
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    @Test
    void evaluationReloadsPreloadedCapabilityFromTheDatabase() {
        UUID vehicleId = fixtures.readyDispatchSystemVehicleId();
        OnboardDeviceCapability capability = capabilityForRole(
                vehicleId, Role.DISPATCH, Capability.VENDOR_DISPATCH);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        OnboardReadiness readiness = transactions.execute(status -> {
            capabilityRepository.findById(capability.getId()).orElseThrow();
            jdbcTemplate.update(
                    "update onboard_device_capabilities set status = 'DISABLED' where id = ?",
                    capability.getId());
            return service.evaluate(vehicleId);
        });

        assertThat(readiness).isNotNull();
        assertThat(readiness.dispatch()).isEqualTo(ReadinessState.UNAVAILABLE);
        assertThat(readiness.dispatchEligible()).isFalse();
    }

    @Test
    void evaluationReloadsPreloadedProfileFromTheDatabase() {
        UUID vehicleId = fixtures.readyDispatchSystemVehicleId();
        OnboardDeviceProtocolProfile profile = profileForRole(vehicleId, Role.DISPATCH);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        OnboardReadiness readiness = transactions.execute(status -> {
            profileRepository.findById(profile.getId()).orElseThrow();
            jdbcTemplate.update(
                    "update onboard_device_protocol_profiles set business_profile = 'GBT28787_2023' where id = ?",
                    profile.getId());
            return service.evaluate(vehicleId);
        });

        assertThat(readiness).isNotNull();
        assertThat(readiness.dispatch()).isEqualTo(ReadinessState.UNAVAILABLE);
        assertThat(readiness.dispatchEligible()).isFalse();
    }

    private void assertUnavailableLocation(UUID vehicleId) {
        OnboardReadiness readiness = service.evaluate(vehicleId);
        assertThat(readiness.location()).isEqualTo(ReadinessState.UNAVAILABLE);
        assertThat(readiness.dispatchEligible()).isFalse();
    }

    private UUID readyVehicleWithoutAuthentication() {
        UUID vehicleId = fixtures.readyDispatchSystemVehicleId();
        UUID terminalId = terminalForRole(vehicleId, Role.DISPATCH).getId();
        jdbcTemplate.update(
                "update jt_terminals set last_authenticated_at = null where id = ?",
                terminalId);
        entityManager.clear();
        return vehicleId;
    }

    private UUID readyVehicleWithoutCurrentLocation() {
        UUID vehicleId = fixtures.readyDispatchSystemVehicleId();
        jdbcTemplate.update(
                "update vehicles set current_location_terminal_id = null where id = ?",
                vehicleId);
        entityManager.clear();
        return vehicleId;
    }

    private DualSystem configureGbtDualSystem(String prefix) {
        String suffix = suffix();
        String compactPrefix = prefix.substring(0, 1);
        String dispatchCode = compactPrefix + "d-" + suffix;
        String recorderCode = compactPrefix + "r-" + suffix;
        String plate = "GBT-" + suffix;
        fixtures.configureDualDeviceSystem(dispatchCode, recorderCode, plate);
        UUID vehicleId = vehicleRepository.findByPlateNumber(plate).orElseThrow().getId();
        return new DualSystem(
                vehicleId,
                fixtures.terminal(dispatchCode),
                fixtures.terminal(recorderCode));
    }

    private JtTerminal addOperationalBackup(UUID vehicleId, String prefix) {
        OnboardSystem system = system(vehicleId);
        JtTerminal terminal = fixtures.terminal(prefix + "-" + suffix());
        membershipRepository.saveAndFlush(OnboardDeviceMembership.join(
                system.getId(), terminal.getId(), NetworkMode.SHARED_LAN_CLIENT,
                "synthetic readiness backup", OnboardTestFixtures.ACTOR_ID, now()));
        verifyCapability(terminal.getId(), Capability.JT808_LOCATION);
        profileRepository.saveAndFlush(OnboardDeviceProtocolProfile.activate(
                terminal.getId(),
                TransportProfile.JT808_2019,
                BusinessProfile.NONE,
                SafetyProfile.NONE,
                MediaProfile.NONE,
                30,
                60,
                "synthetic readiness backup profile",
                OnboardTestFixtures.ACTOR_ID,
                now()));
        roleRepository.saveAndFlush(OnboardDeviceRoleAssignment.assign(
                system.getId(), terminal.getId(), Role.LOCATION_BACKUP,
                "synthetic readiness backup role", OnboardTestFixtures.ACTOR_ID, now()));
        authenticate(terminal);
        return terminal;
    }

    private JtTerminal addOperationalMember(UUID vehicleId, String prefix) {
        OnboardSystem system = system(vehicleId);
        JtTerminal terminal = fixtures.terminal(prefix + "-" + suffix());
        membershipRepository.saveAndFlush(OnboardDeviceMembership.join(
                system.getId(), terminal.getId(), NetworkMode.SHARED_LAN_CLIENT,
                "synthetic readiness member", OnboardTestFixtures.ACTOR_ID, now()));
        verifyCapability(terminal.getId(), Capability.JT808_LOCATION);
        profileRepository.saveAndFlush(OnboardDeviceProtocolProfile.activate(
                terminal.getId(),
                TransportProfile.JT808_2019,
                BusinessProfile.NONE,
                SafetyProfile.NONE,
                MediaProfile.NONE,
                30,
                60,
                "synthetic readiness member profile",
                OnboardTestFixtures.ACTOR_ID,
                now()));
        authenticate(terminal);
        return terminal;
    }

    private void authenticate(JtTerminal terminal) {
        if (terminal.getLastRegisteredAt() == null) {
            terminal.completeRegistration(1, "a".repeat(64));
        }
        if (terminal.getStatus() != JtTerminal.Status.ACTIVE) {
            terminal.activate(true);
        }
        terminal.recordSuccessfulAuthentication(now());
        terminalRepository.saveAndFlush(terminal);
    }

    private void makeCurrentLocation(
            UUID vehicleId,
            UUID terminalId,
            Role role,
            Instant receivedAt,
            LocationQualityStatus qualityStatus) {
        OnboardSystem system = system(vehicleId);
        CanonicalPositionIngress ingress = new CanonicalPositionIngress(
                terminalId,
                system.getId(),
                vehicleId,
                role.name(),
                "JT808_2019",
                1,
                new BigDecimal("120.155"),
                new BigDecimal("30.274"),
                "GCJ02",
                receivedAt,
                receivedAt,
                0L,
                0L,
                BigDecimal.ZERO,
                0,
                0,
                8,
                "b".repeat(64));
        VehicleLocationEvent event = VehicleLocationEvent.recordGps(
                vehicleId,
                terminalId,
                ingress,
                new CoordinateTransformer.StandardizedCoordinate(
                        new BigDecimal("120.155"),
                        new BigDecimal("30.274"),
                        "GCJ02",
                        "SYNTHETIC_IDENTITY"),
                new LocationQualityDecision(qualityStatus, Set.of(), true, true),
                UUID.randomUUID(),
                "c".repeat(64),
                OffsetDateTime.ofInstant(receivedAt, ZoneOffset.UTC),
                receivedAt,
                false);
        event.markSnapshotApplied();
        event = locationEventRepository.saveAndFlush(event);
        Vehicle vehicle = vehicleRepository.findById(vehicleId).orElseThrow();
        vehicle.applyGpsLocationSnapshot(event);
        vehicleRepository.saveAndFlush(vehicle);
        OnboardSystemRuntimeState runtime = runtime(vehicleId);
        runtime.selectLocationSource(terminalId, after(runtime.getUpdatedAt()));
        runtimeRepository.saveAndFlush(runtime);
    }

    private void verifyCapability(UUID terminalId, Capability capability) {
        OnboardDeviceCapability fact = OnboardDeviceCapability.declare(
                terminalId, capability, "synthetic readiness declaration", now());
        fact.verify(
                "synthetic-readiness-evidence",
                OnboardTestFixtures.ACTOR_ID,
                "synthetic readiness verification",
                now());
        capabilityRepository.saveAndFlush(fact);
    }

    private OnboardSystem system(UUID vehicleId) {
        return systemRepository.findActiveByVehicleId(vehicleId).orElseThrow();
    }

    private OnboardSystemRuntimeState runtime(UUID vehicleId) {
        return runtimeRepository.findById(system(vehicleId).getId()).orElseThrow();
    }

    private OnboardDeviceRoleAssignment roleFor(UUID vehicleId, Role role) {
        return roleRepository.findActiveByOnboardSystemIdOrderByValidFromAsc(system(vehicleId).getId()).stream()
                .filter(candidate -> candidate.getRole() == role)
                .findFirst()
                .orElseThrow();
    }

    private JtTerminal terminalForRole(UUID vehicleId, Role role) {
        return terminalRepository.findById(roleFor(vehicleId, role).getTerminalId()).orElseThrow();
    }

    private OnboardDeviceCapability capabilityForRole(
            UUID vehicleId,
            Role role,
            Capability capability) {
        return capabilityRepository.findCurrentByTerminalIdAndCapability(
                terminalForRole(vehicleId, role).getId(), capability).orElseThrow();
    }

    private OnboardDeviceProtocolProfile profileForRole(UUID vehicleId, Role role) {
        return profileRepository.findActiveByTerminalId(
                terminalForRole(vehicleId, role).getId()).orElseThrow();
    }

    private VersionSnapshot versions(UUID vehicleId) {
        OnboardSystem system = system(vehicleId);
        List<OnboardDeviceMembership> memberships = membershipRepository
                .findActiveByOnboardSystemIdOrderByValidFromAsc(system.getId());
        List<UUID> terminalIds = memberships.stream()
                .map(OnboardDeviceMembership::getTerminalId)
                .toList();
        List<Long> membershipVersions = memberships.stream()
                .map(OnboardDeviceMembership::getVersion)
                .sorted()
                .toList();
        List<Long> roleVersions = roleRepository
                .findActiveByOnboardSystemIdOrderByValidFromAsc(system.getId()).stream()
                .map(OnboardDeviceRoleAssignment::getVersion)
                .sorted()
                .toList();
        List<Long> capabilityVersions = terminalIds.stream()
                .flatMap(terminalId -> capabilityRepository
                        .findCurrentByTerminalIdOrderByCreatedAtAsc(terminalId).stream())
                .map(OnboardDeviceCapability::getVersion)
                .sorted()
                .toList();
        List<Long> profileVersions = terminalIds.stream()
                .map(terminalId -> profileRepository.findActiveByTerminalId(terminalId).orElseThrow().getVersion())
                .sorted()
                .toList();
        List<Long> terminalVersions = terminalRepository.findAllById(terminalIds).stream()
                .map(JtTerminal::getVersion)
                .sorted()
                .toList();
        return new VersionSnapshot(
                system.getVersion(),
                runtime(vehicleId).getRuntimeVersion(),
                membershipVersions,
                roleVersions,
                capabilityVersions,
                profileVersions,
                terminalVersions);
    }

    private ExecutorService executor() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        executors.add(executor);
        return executor;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for test latch");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test latch wait interrupted", interrupted);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private OffsetDateTime after(OffsetDateTime timestamp) {
        OffsetDateTime candidate = now();
        return candidate.isAfter(timestamp) ? candidate : timestamp.plusNanos(1);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record DualSystem(UUID vehicleId, JtTerminal dispatchTerminal, JtTerminal recorderTerminal) {
    }

    private record VersionSnapshot(
            long systemVersion,
            long runtimeVersion,
            List<Long> membershipVersions,
            List<Long> roleVersions,
            List<Long> capabilityVersions,
            List<Long> profileVersions,
            List<Long> terminalVersions) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {

        @Bean
        @Primary
        MutableClock mutableReadinessClock() {
            return new MutableClock(Instant.now().plusSeconds(5), ZoneOffset.UTC);
        }
    }

    static final class MutableClock extends Clock {

        private final AtomicReference<Instant> instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = new AtomicReference<>(instant);
            this.zone = zone;
        }

        void set(Instant value) {
            instant.set(value);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId requestedZone) {
            return new MutableClock(instant(), requestedZone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
