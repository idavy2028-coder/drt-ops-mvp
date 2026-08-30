package com.idavy.drtops.domain.onboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import com.idavy.drtops.domain.onboard.OnboardDeviceRoleAssignment.Role;
import com.idavy.drtops.domain.onboard.OnboardRegistrationResolver.RegistrationDecision;
import com.idavy.drtops.domain.onboard.OnboardRegistrationResolver.RegistrationRequest;
import com.idavy.drtops.domain.terminal.JtTerminal;
import com.idavy.drtops.domain.terminal.JtTerminalRepository;
import jakarta.persistence.EntityManager;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:onboard_registration_resolver;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import({OnboardTestFixtures.class,
        OnboardRegistrationResolverTest.IdentityRaceConfiguration.class})
class OnboardRegistrationResolverTest {

    @Autowired
    OnboardRegistrationResolver resolver;

    @Autowired
    OnboardTestFixtures fixtures;

    @Autowired
    OnboardSystemRepository systemRepository;

    @Autowired
    OnboardSystemRuntimeStateRepository runtimeRepository;

    @Autowired
    OnboardDeviceMembershipRepository membershipRepository;

    @Autowired
    VehicleRepository vehicleRepository;

    @Autowired
    JtTerminalRepository terminalRepository;

    @Autowired
    AuditLogRepository auditLogRepository;

    @Autowired
    EntityManager entityManager;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    IdentityRacePause identityRacePause;

    @BeforeEach
    void setUp() {
        fixtures.clear();
    }

    @Test
    void allowsTwoPhysicalIdentitiesToShareOneBoundVehicleIdentifier() {
        fixtures.configureDualDeviceSystem("dispatch-01", "recorder-01", "VEHICLE-A");

        RegistrationDecision dispatch = resolver.verify(registration(
                "dispatch-01", "PHONE-DISPATCH", "VEHICLE-A"));
        RegistrationDecision recorder = resolver.verify(registration(
                "recorder-01", "PHONE-RECORDER", "VEHICLE-A"));

        assertThat(dispatch.approved()).isTrue();
        assertThat(recorder.approved()).isTrue();
        assertThat(dispatch.warnings()).isEmpty();
        assertThat(recorder.warnings()).isEmpty();
        assertThat(dispatch.context().onboardSystemId())
                .isEqualTo(recorder.context().onboardSystemId());
        assertThat(dispatch.context().vehicleId()).isEqualTo(recorder.context().vehicleId());
        assertThat(dispatch.context().terminalId()).isNotEqualTo(recorder.context().terminalId());
        assertThat(dispatch.context().roles()).containsExactlyInAnyOrder(
                Role.DISPATCH, Role.LOCATION_PRIMARY, Role.WAN_UPLINK);
        assertThat(recorder.context().roles()).containsExactlyInAnyOrder(
                Role.LOCATION_BACKUP, Role.ACTIVE_SAFETY, Role.VIDEO);
        assertThatThrownBy(() -> dispatch.context().roles().add(Role.VIDEO))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> dispatch.warnings().add("MUTATION"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void warnsOnUnknownIdentifierThenClearsOnlyMismatchOnLaterMatch() {
        fixtures.configureRecorderSystem("recorder-01", "VEHICLE-A");
        OnboardSystem system = systemRepository.findAll().getFirst();
        OnboardSystemRuntimeState runtime = runtimeRepository.findById(system.getId()).orElseThrow();
        runtime.replaceWarningCodes(List.of("UNRELATED_WARNING"), OffsetDateTime.now());
        runtimeRepository.saveAndFlush(runtime);
        long configurationVersion = system.getVersion();

        RegistrationDecision warning = resolver.verify(registration(
                "recorder-01", "PHONE-RECORDER", "UNASSIGNED-PLATE"));

        assertThat(warning.approved()).isTrue();
        assertThat(warning.warnings()).containsExactly("VEHICLE_IDENTIFIER_MISMATCH");
        assertThat(runtimeRepository.findById(system.getId()).orElseThrow().getWarningCodes())
                .containsExactly("UNRELATED_WARNING", "VEHICLE_IDENTIFIER_MISMATCH");
        assertThat(systemRepository.findById(system.getId()).orElseThrow().getVersion())
                .isEqualTo(configurationVersion);
        assertThat(auditLogRepository.findAll().stream()
                .filter(audit -> "VEHICLE_IDENTIFIER_MISMATCH".equals(audit.getAction()))
                .toList()).singleElement().satisfies(audit -> {
            assertThat(audit.getAction()).isEqualTo("VEHICLE_IDENTIFIER_MISMATCH");
            assertThat(audit.getMetadataJson()).contains("warningCount")
                    .doesNotContain(
                            "UNASSIGNED-PLATE", "recorder-01", "PHONE-RECORDER", "SYNTHETIC");
        });

        RegistrationDecision matching = resolver.verify(registration(
                "recorder-01", "PHONE-RECORDER", "VEHICLE-A"));

        assertThat(matching.approved()).isTrue();
        assertThat(matching.warnings()).isEmpty();
        assertThat(runtimeRepository.findById(system.getId()).orElseThrow().getWarningCodes())
                .containsExactly("UNRELATED_WARNING");
        assertThat(systemRepository.findById(system.getId()).orElseThrow().getVersion())
                .isEqualTo(configurationVersion);
    }

    @Test
    void rejectsAnotherKnownVehicleWithoutChangingRuntimeConfigurationOrRegistration() {
        fixtures.configureRecorderSystem("recorder-01", "VEHICLE-A");
        fixtures.createVehicle("VEHICLE-B");
        OnboardSystem system = systemRepository.findAll().getFirst();
        OnboardSystemRuntimeState runtime = runtimeRepository.findById(system.getId()).orElseThrow();
        runtime.replaceWarningCodes(List.of("UNRELATED_WARNING"), OffsetDateTime.now());
        runtimeRepository.saveAndFlush(runtime);
        long runtimeVersion = runtimeRepository.findById(system.getId()).orElseThrow()
                .getRuntimeVersion();
        long configurationVersion = system.getVersion();

        RegistrationDecision conflict = resolver.verify(registration(
                "recorder-01", "PHONE-RECORDER", "VEHICLE-B"));

        assertThat(conflict.approved()).isFalse();
        assertThat(conflict.context()).isNull();
        assertThat(conflict.warnings()).isEmpty();
        assertThat(conflict.reasonCode()).isEqualTo("VEHICLE_IDENTIFIER_CONFLICT");
        assertThat(runtimeRepository.findById(system.getId()).orElseThrow().getWarningCodes())
                .containsExactly("UNRELATED_WARNING");
        assertThat(runtimeRepository.findById(system.getId()).orElseThrow().getRuntimeVersion())
                .isEqualTo(runtimeVersion);
        assertThat(systemRepository.findById(system.getId()).orElseThrow().getVersion())
                .isEqualTo(configurationVersion);
        assertThat(terminalRepository.findByTerminalCode("recorder-01").orElseThrow()
                .getLastRegisteredAt()).isNull();
        assertThat(auditLogRepository.findAll().stream()
                .filter(audit -> "VEHICLE_IDENTIFIER_CONFLICT".equals(audit.getAction()))
                .toList()).singleElement().satisfies(audit -> {
            assertThat(audit.getAction()).isEqualTo("VEHICLE_IDENTIFIER_CONFLICT");
            assertThat(audit.getMetadataJson()).doesNotContain(
                    "VEHICLE-A", "VEHICLE-B", "recorder-01", "PHONE-RECORDER", "SYNTHETIC");
        });
    }

    @Test
    void rejectsPhoneAndCodeThatResolveDifferentPhysicalDevices() {
        fixtures.configureDualDeviceSystem("dispatch-01", "recorder-01", "VEHICLE-A");

        RegistrationDecision decision = resolver.verify(registration(
                "dispatch-01", "PHONE-RECORDER", "VEHICLE-A"));

        assertThat(decision.approved()).isFalse();
        assertThat(decision.context()).isNull();
        assertThat(decision.warnings()).isEmpty();
        assertThat(decision.reasonCode()).isEqualTo("TERMINAL_IDENTITY_MISMATCH");
        assertThat(decision.toString()).doesNotContain(
                "dispatch-01", "recorder-01", "PHONE-DISPATCH", "PHONE-RECORDER", "VEHICLE-A");
    }

    @Test
    void rejectsRegistrationWhenPresentedIdentitiesAreReusedBeforeTerminalLock()
            throws Exception {
        fixtures.configureDualDeviceSystem("dispatch-01", "recorder-01", "VEHICLE-A");
        JtTerminal original = terminalRepository.findByTerminalCode("recorder-01").orElseThrow();
        JtTerminal replacement = terminalRepository.findByTerminalCode("dispatch-01").orElseThrow();
        OnboardSystem system = systemRepository.findAll().getFirst();
        OnboardSystemRuntimeState runtime = runtimeRepository.findById(system.getId()).orElseThrow();
        long configurationVersion = system.getVersion();
        long runtimeVersion = runtime.getRuntimeVersion();
        List<String> warningCodes = List.copyOf(runtime.getWarningCodes());
        long auditCount = auditLogRepository.count();
        CountDownLatch identityResolved = new CountDownLatch(1);
        CountDownLatch releaseResolver = new CountDownLatch(1);
        identityRacePause.arm(
                "registration-identity-race-thread", "findAllByTerminalCode",
                identityResolved, releaseResolver);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<RegistrationDecision> pending = executor.submit(() -> {
                Thread.currentThread().setName("registration-identity-race-thread");
                return resolver.verify(registration(
                        "recorder-01", "PHONE-RECORDER", "VEHICLE-A"));
            });
            assertThat(identityResolved.await(5, TimeUnit.SECONDS)).isTrue();
            reusePendingIdentity(original.getId(), replacement.getId());
            releaseResolver.countDown();

            RegistrationDecision decision = pending.get(5, TimeUnit.SECONDS);
            assertThat(decision.approved()).isFalse();
            assertThat(decision.context()).isNull();
            assertThat(decision.warnings()).isEmpty();
            assertThat(decision.reasonCode()).isEqualTo("TERMINAL_IDENTITY_CHANGED");
            assertThat(decision.toString()).doesNotContain(
                    "recorder-01", "PHONE-RECORDER", "VEHICLE-A",
                    original.getId().toString(), replacement.getId().toString());
            assertThat(terminalRepository.findById(original.getId()).orElseThrow()
                    .getLastRegisteredAt()).isNull();
            assertThat(terminalRepository.findById(replacement.getId()).orElseThrow()
                    .getLastRegisteredAt()).isNull();
            assertThat(runtimeRepository.findById(system.getId()).orElseThrow().getWarningCodes())
                    .isEqualTo(warningCodes);
            assertThat(runtimeRepository.findById(system.getId()).orElseThrow()
                    .getRuntimeVersion()).isEqualTo(runtimeVersion);
            assertThat(systemRepository.findById(system.getId()).orElseThrow().getVersion())
                    .isEqualTo(configurationVersion);
            assertThat(membershipRepository.findActiveByTerminalId(original.getId()))
                    .isPresent();
            assertThat(membershipRepository.findActiveByTerminalId(replacement.getId()))
                    .isPresent();
            assertThat(auditLogRepository.count()).isEqualTo(auditCount);
        } finally {
            releaseResolver.countDown();
            identityRacePause.disarm();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void rejectsIdentityAuthenticationWhenPhoneIsReusedBeforeTerminalLock()
            throws Exception {
        fixtures.configureDualDeviceSystem("dispatch-01", "recorder-01", "VEHICLE-A");
        JtTerminal original = terminalRepository.findByTerminalCode("recorder-01").orElseThrow();
        JtTerminal replacement = terminalRepository.findByTerminalCode("dispatch-01").orElseThrow();
        String originalToken = "a".repeat(64);
        registerAndActivate(original.getId(), originalToken);
        registerAndActivate(replacement.getId(), "b".repeat(64));
        long auditCount = auditLogRepository.count();
        CountDownLatch identityResolved = new CountDownLatch(1);
        CountDownLatch releaseResolver = new CountDownLatch(1);
        identityRacePause.arm(
                "authentication-identity-race-thread", "findAllBySemanticPhone",
                identityResolved, releaseResolver);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<OnboardRegistrationResolver.AuthenticationDecision> pending =
                    executor.submit(() -> {
                        Thread.currentThread().setName("authentication-identity-race-thread");
                        return resolver.authenticateByIdentity(
                                "JT808_2019", "PHONE-RECORDER", originalToken, "gateway-a");
                    });
            assertThat(identityResolved.await(5, TimeUnit.SECONDS)).isTrue();
            reuseActivePhoneIdentity(original.getId(), replacement.getId());
            releaseResolver.countDown();

            OnboardRegistrationResolver.AuthenticationDecision decision =
                    pending.get(5, TimeUnit.SECONDS);
            assertThat(decision.approved()).isFalse();
            assertThat(decision.context()).isNull();
            assertThat(decision.reasonCode()).isEqualTo("AUTHENTICATION_REJECTED");
            assertThat(decision.toString()).doesNotContain(
                    "PHONE-RECORDER", originalToken,
                    original.getId().toString(), replacement.getId().toString());
            assertThat(terminalRepository.findById(original.getId()).orElseThrow()
                    .getLastAuthenticatedAt()).isNull();
            assertThat(terminalRepository.findById(replacement.getId()).orElseThrow()
                    .getLastAuthenticatedAt()).isNull();
            assertThat(auditLogRepository.count()).isEqualTo(auditCount);
        } finally {
            releaseResolver.countDown();
            identityRacePause.disarm();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void rejectsRemovedMembership() {
        fixtures.configureRecorderSystem("recorder-01", "VEHICLE-A");
        var terminal = terminalRepository.findByTerminalCode("recorder-01").orElseThrow();
        var membership = membershipRepository.findActiveByTerminalId(terminal.getId()).orElseThrow();
        membership.remove("synthetic removal", OnboardTestFixtures.ACTOR_ID, OffsetDateTime.now());
        membershipRepository.saveAndFlush(membership);

        RegistrationDecision decision = resolver.verify(registration(
                "recorder-01", "PHONE-RECORDER", "VEHICLE-A"));

        assertThat(decision.approved()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo("ACTIVE_MEMBERSHIP_MISSING");
    }

    @Test
    void rejectsSuspendedSystemAndUnavailableVehicle() {
        fixtures.configureRecorderSystem("recorder-01", "VEHICLE-A");
        OnboardSystem system = systemRepository.findAll().getFirst();
        system.suspend(OnboardTestFixtures.ACTOR_ID, OffsetDateTime.now());
        systemRepository.saveAndFlush(system);

        assertThat(resolver.verify(registration(
                "recorder-01", "PHONE-RECORDER", "VEHICLE-A")).reasonCode())
                .isEqualTo("ONBOARD_SYSTEM_UNAVAILABLE");

        OnboardSystem suspended = systemRepository.findById(system.getId()).orElseThrow();
        suspended.activate(OnboardTestFixtures.ACTOR_ID, OffsetDateTime.now());
        systemRepository.saveAndFlush(suspended);
        vehicleRepository.deleteById(system.getVehicleId());

        assertThat(resolver.verify(registration(
                "recorder-01", "PHONE-RECORDER", "VEHICLE-A")).reasonCode())
                .isEqualTo("VEHICLE_UNAVAILABLE");
    }

    @Test
    void concurrentMismatchWarningsPreserveExistingCodesWithoutConfigurationChange()
            throws Exception {
        fixtures.configureRecorderSystem("recorder-01", "VEHICLE-A");
        OnboardSystem system = systemRepository.findAll().getFirst();
        OnboardSystemRuntimeState runtime = runtimeRepository.findById(system.getId()).orElseThrow();
        runtime.replaceWarningCodes(List.of("UNRELATED_WARNING"), OffsetDateTime.now());
        runtimeRepository.saveAndFlush(runtime);
        long configurationVersion = system.getVersion();
        CountDownLatch workersReady = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<RegistrationDecision> first = executor.submit(() -> {
                workersReady.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return resolver.verify(registration(
                        "recorder-01", "PHONE-RECORDER", "UNKNOWN-A"));
            });
            Future<RegistrationDecision> second = executor.submit(() -> {
                workersReady.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return resolver.verify(registration(
                        "recorder-01", "PHONE-RECORDER", "UNKNOWN-B"));
            });
            assertThat(workersReady.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(
                    first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .allSatisfy(decision -> {
                        assertThat(decision.approved()).isTrue();
                        assertThat(decision.warnings())
                                .containsExactly("VEHICLE_IDENTIFIER_MISMATCH");
                    });
            assertThat(runtimeRepository.findById(system.getId()).orElseThrow().getWarningCodes())
                    .containsExactly("UNRELATED_WARNING", "VEHICLE_IDENTIFIER_MISMATCH");
            assertThat(systemRepository.findById(system.getId()).orElseThrow().getVersion())
                    .isEqualTo(configurationVersion);
            assertThat(auditLogRepository.findAll().stream()
                    .filter(audit -> "VEHICLE_IDENTIFIER_MISMATCH".equals(audit.getAction())))
                    .hasSize(2);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static RegistrationRequest registration(
            String terminalCode, String terminalPhone, String vehicleIdentifier) {
        return new RegistrationRequest(
                terminalPhone, terminalCode, "SYNTH", "SYNTHETIC",
                vehicleIdentifier, "JT808_2019");
    }

    private void reusePendingIdentity(
            java.util.UUID originalId, java.util.UUID replacementId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            JtTerminal original = entityManager.find(JtTerminal.class, originalId);
            original.correctIdentity(
                    "PHONE-RELEASED", "released-recorder-01",
                    original.getManufacturerId(), original.getModel(),
                    original.getProtocolVersion(), original.getSourceCoordinateSystem());
            entityManager.flush();
            JtTerminal replacement = entityManager.find(JtTerminal.class, replacementId);
            replacement.correctIdentity(
                    "PHONE-RECORDER", "recorder-01",
                    replacement.getManufacturerId(), replacement.getModel(),
                    replacement.getProtocolVersion(), replacement.getSourceCoordinateSystem());
            entityManager.flush();
        });
    }

    private void registerAndActivate(java.util.UUID terminalId, String tokenSha256) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            JtTerminal terminal = entityManager.find(JtTerminal.class, terminalId);
            terminal.completeRegistration(1, tokenSha256);
            terminal.activate(true);
            entityManager.flush();
        });
    }

    private void reuseActivePhoneIdentity(
            java.util.UUID originalId, java.util.UUID replacementId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery("""
                            update jt_terminals
                            set terminal_phone = 'PHONE-RELEASED',
                                terminal_phone_identity = 'PHONE-RELEASED',
                                version = version + 1
                            where id = :terminalId
                            """)
                    .setParameter("terminalId", originalId)
                    .executeUpdate();
            entityManager.createNativeQuery("""
                            update jt_terminals
                            set terminal_phone = 'PHONE-RECORDER',
                                terminal_phone_identity = 'PHONE-RECORDER',
                                version = version + 1
                            where id = :terminalId
                            """)
                    .setParameter("terminalId", replacementId)
                    .executeUpdate();
        });
    }

    static final class IdentityRacePause {
        private final AtomicBoolean pauseOnce = new AtomicBoolean();
        private volatile String threadName;
        private volatile String lookupMethod;
        private volatile CountDownLatch identityResolved;
        private volatile CountDownLatch release;

        void arm(
                String threadName,
                String lookupMethod,
                CountDownLatch identityResolved,
                CountDownLatch release) {
            this.threadName = threadName;
            this.lookupMethod = lookupMethod;
            this.identityResolved = identityResolved;
            this.release = release;
            pauseOnce.set(false);
        }

        void afterLookup(String methodName) {
            if (Thread.currentThread().getName().equals(threadName)
                    && methodName.equals(lookupMethod)
                    && pauseOnce.compareAndSet(false, true)) {
                identityResolved.countDown();
                await(release);
            }
        }

        void disarm() {
            threadName = null;
            lookupMethod = null;
            identityResolved = null;
            release = null;
            pauseOnce.set(false);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class IdentityRaceConfiguration {

        @Bean
        IdentityRacePause identityRacePause() {
            return new IdentityRacePause();
        }

        @Bean
        static BeanPostProcessor identityRaceRepositoryPostProcessor(
                IdentityRacePause identityRacePause) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof JtTerminalRepository repository)) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(
                            JtTerminalRepository.class.getClassLoader(),
                            new Class<?>[] {JtTerminalRepository.class},
                            (proxy, method, arguments) -> {
                                Object result = invokeRepository(repository, method, arguments);
                                identityRacePause.afterLookup(method.getName());
                                return result;
                            });
                }
            };
        }
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

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for identity race release");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("identity race wait interrupted", interrupted);
        }
    }
}
