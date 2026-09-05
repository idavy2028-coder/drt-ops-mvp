package com.idavy.drtops.domain.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import com.idavy.drtops.domain.terminal.JtTerminalSessionLeaseService.SessionLeaseGrant;
import com.idavy.drtops.domain.terminal.JtTerminalSessionLeaseService.SessionLeaseOwner;
import com.idavy.drtops.domain.terminal.JtTerminalSessionLeaseService.SessionLeaseReleaseResult;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:terminal_session_lease;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import(JtTerminalSessionLeaseServiceTest.ClockConfiguration.class)
class JtTerminalSessionLeaseServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-04T02:00:00Z");
    private static final UUID TERMINAL_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONNECTION_A =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CONNECTION_B =
            UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID CONNECTION_C =
            UUID.fromString("55555555-5555-5555-5555-555555555555");

    @Autowired
    JtTerminalSessionLeaseService service;

    @Autowired
    JtTerminalSessionLeaseRepository repository;

    @Autowired
    JtTerminalRepository terminalRepository;

    @Autowired
    MutableClock clock;

    @Autowired
    FirstLeaseCreationRace firstLeaseCreationRace;

    @BeforeEach
    void setUp() {
        firstLeaseCreationRace.reset();
        repository.deleteAll();
        terminalRepository.deleteAll();
        clock.set(NOW);
        JtTerminal terminal = JtTerminal.preset(
                TERMINAL_ID,
                "00000000000000000001",
                "LEASE-TEST-TERMINAL",
                "MFG01",
                "MODEL-X",
                "JT808_2019",
                "GCJ02",
                UUID.fromString("44444444-4444-4444-4444-444444444444"));
        ReflectionTestUtils.setField(terminal, "authTokenVersion", 7);
        terminalRepository.saveAndFlush(terminal);
    }

    @Test
    void acquireRenewReleaseAndExpiryUseOnlyApiClock() {
        SessionLeaseGrant first = service.acquire(
                TERMINAL_ID, "gateway-a", CONNECTION_A, 7);
        assertThat(first.owner().leaseGeneration()).isEqualTo(1);
        assertThat(first.expiresAt()).isEqualTo(NOW.plusSeconds(180));
        assertThat(service.isLiveAt(
                TERMINAL_ID, 7, NOW.plusSeconds(179))).isTrue();
        assertThat(service.isLiveAt(
                TERMINAL_ID, 7, NOW.plusSeconds(180))).isFalse();

        clock.set(NOW.plusSeconds(30));
        SessionLeaseGrant renewed = service.renew(first.owner()).orElseThrow();
        assertThat(renewed.lastValidMessageAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(renewed.expiresAt()).isEqualTo(NOW.plusSeconds(210));

        SessionLeaseReleaseResult released =
                service.release(renewed.owner(), "SESSION_OFFLINE");
        assertThat(released.status()).isEqualTo("RELEASED");
        assertThat(service.isLiveAt(
                TERMINAL_ID, 7, NOW.plusSeconds(31))).isFalse();
    }

    @Test
    void staleReleaseCannotClearTheTakeoverLease() {
        SessionLeaseGrant oldOwner =
                service.acquire(TERMINAL_ID, "gateway-a", CONNECTION_A, 7);
        SessionLeaseGrant takeover =
                service.acquire(TERMINAL_ID, "gateway-b", CONNECTION_B, 7);

        assertThat(takeover.owner().leaseGeneration()).isEqualTo(2);
        assertThat(service.release(
                oldOwner.owner(), "SESSION_OFFLINE").status())
                .isEqualTo("STALE_OWNER_IGNORED");
        assertThat(service.isLiveAt(
                TERMINAL_ID, 7, clock.instant())).isTrue();
        assertThat(repository.findById(TERMINAL_ID)
                .orElseThrow().getConnectionId()).isEqualTo(CONNECTION_B);
    }

    @Test
    void tokenRotationInvalidatesAnOtherwiseFreshLease() {
        SessionLeaseGrant lease =
                service.acquire(TERMINAL_ID, "gateway-a", CONNECTION_A, 7);

        assertThat(service.isLiveAt(
                TERMINAL_ID, 8, clock.instant())).isFalse();
        assertThat(service.renew(new SessionLeaseOwner(
                TERMINAL_ID,
                "gateway-a",
                CONNECTION_A,
                8,
                lease.owner().leaseGeneration()))).isEmpty();
    }

    @Test
    void concurrentAcquireRenewReleasePreservesGenerationAndStaleOwnerFencing()
            throws Exception {
        SessionLeaseGrant initial =
                service.acquire(TERMINAL_ID, "gateway-a", CONNECTION_A, 7);
        CyclicBarrier acquireStart = new CyclicBarrier(3);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<SessionLeaseGrant> firstAcquire = executor.submit(() -> {
                acquireStart.await(5, TimeUnit.SECONDS);
                return service.acquire(TERMINAL_ID, "gateway-b", CONNECTION_B, 7);
            });
            Future<SessionLeaseGrant> secondAcquire = executor.submit(() -> {
                acquireStart.await(5, TimeUnit.SECONDS);
                return service.acquire(TERMINAL_ID, "gateway-c", CONNECTION_C, 7);
            });
            acquireStart.await(5, TimeUnit.SECONDS);
            List<SessionLeaseGrant> takeovers = List.of(
                            firstAcquire.get(5, TimeUnit.SECONDS),
                            secondAcquire.get(5, TimeUnit.SECONDS))
                    .stream()
                    .sorted(Comparator.comparingLong(
                            grant -> grant.owner().leaseGeneration()))
                    .toList();
            SessionLeaseGrant stale = takeovers.getFirst();
            SessionLeaseGrant current = takeovers.getLast();

            assertThat(initial.owner().leaseGeneration()).isEqualTo(1);
            assertThat(takeovers).extracting(
                    grant -> grant.owner().leaseGeneration())
                    .containsExactly(2L, 3L);

            CyclicBarrier mutationStart = new CyclicBarrier(3);
            Future<SessionLeaseReleaseResult> staleRelease = executor.submit(() -> {
                mutationStart.await(5, TimeUnit.SECONDS);
                return service.release(stale.owner(), "SESSION_OFFLINE");
            });
            Future<java.util.Optional<SessionLeaseGrant>> currentRenew = executor.submit(() -> {
                mutationStart.await(5, TimeUnit.SECONDS);
                return service.renew(current.owner());
            });
            mutationStart.await(5, TimeUnit.SECONDS);

            assertThat(staleRelease.get(5, TimeUnit.SECONDS).status())
                    .isEqualTo("STALE_OWNER_IGNORED");
            assertThat(currentRenew.get(5, TimeUnit.SECONDS)).isPresent();
            JtTerminalSessionLease persisted = repository.findById(TERMINAL_ID)
                    .orElseThrow();
            assertThat(persisted.getLeaseGeneration()).isEqualTo(3);
            assertThat(persisted.getConnectionId())
                    .isEqualTo(current.owner().connectionId());
            assertThat(persisted.getReleasedAt()).isNull();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void terminalLockSerializesConcurrentFirstAcquireAndFencesTheStaleOwner()
            throws Exception {
        firstLeaseCreationRace.arm();
        CyclicBarrier start = new CyclicBarrier(3);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<SessionLeaseGrant> first = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return service.acquire(TERMINAL_ID, "gateway-a", CONNECTION_A, 7);
            });
            Future<SessionLeaseGrant> second = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return service.acquire(TERMINAL_ID, "gateway-b", CONNECTION_B, 7);
            });
            start.await(5, TimeUnit.SECONDS);

            List<SessionLeaseGrant> grants = List.of(
                            first.get(5, TimeUnit.SECONDS),
                            second.get(5, TimeUnit.SECONDS))
                    .stream()
                    .sorted(Comparator.comparingLong(
                            grant -> grant.owner().leaseGeneration()))
                    .toList();
            SessionLeaseGrant stale = grants.getFirst();
            SessionLeaseGrant current = grants.getLast();

            assertThat(grants).extracting(
                    grant -> grant.owner().leaseGeneration())
                    .containsExactly(1L, 2L);
            JtTerminalSessionLease persisted = repository.findById(TERMINAL_ID)
                    .orElseThrow();
            assertThat(persisted.getLeaseGeneration()).isEqualTo(2);
            assertThat(persisted.getGatewayInstance())
                    .isEqualTo(current.owner().gatewayInstance());
            assertThat(persisted.getConnectionId())
                    .isEqualTo(current.owner().connectionId());
            assertThat(persisted.getReleasedAt()).isNull();

            assertThat(service.release(stale.owner(), "SESSION_OFFLINE").status())
                    .isEqualTo("STALE_OWNER_IGNORED");
            JtTerminalSessionLease afterStaleRelease = repository.findById(TERMINAL_ID)
                    .orElseThrow();
            assertThat(afterStaleRelease.getLeaseGeneration()).isEqualTo(2);
            assertThat(afterStaleRelease.getConnectionId())
                    .isEqualTo(current.owner().connectionId());
            assertThat(afterStaleRelease.getReleasedAt()).isNull();
        } finally {
            firstLeaseCreationRace.reset();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {

        @Bean
        @Primary
        MutableClock leaseClock() {
            return new MutableClock(NOW, ZoneOffset.UTC);
        }

        @Bean
        FirstLeaseCreationRace firstLeaseCreationRace() {
            return new FirstLeaseCreationRace();
        }

        @Bean
        static BeanPostProcessor firstLeaseCreationRaceRepositoryPostProcessor(
                FirstLeaseCreationRace race) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof JtTerminalSessionLeaseRepository repository)) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(
                            JtTerminalSessionLeaseRepository.class.getClassLoader(),
                            new Class<?>[] {JtTerminalSessionLeaseRepository.class},
                            (proxy, method, arguments) -> {
                                Object result = invokeRepository(repository, method, arguments);
                                race.afterLookup(method.getName(), result);
                                return result;
                            });
                }
            };
        }
    }

    static final class FirstLeaseCreationRace {

        private final AtomicBoolean armed = new AtomicBoolean();
        private final AtomicReference<CountDownLatch> emptyLookups =
                new AtomicReference<>(new CountDownLatch(2));

        void arm() {
            emptyLookups.set(new CountDownLatch(2));
            if (!armed.compareAndSet(false, true)) {
                throw new IllegalStateException("first lease creation race is already armed");
            }
        }

        void afterLookup(String methodName, Object result) {
            if (!armed.get()
                    || !methodName.equals("findLockedByTerminalId")
                    || !(result instanceof java.util.Optional<?> optional)
                    || optional.isPresent()) {
                return;
            }
            CountDownLatch rendezvous = emptyLookups.get();
            rendezvous.countDown();
            try {
                rendezvous.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "first lease creation race was interrupted", interrupted);
            }
        }

        void reset() {
            armed.set(false);
            emptyLookups.set(new CountDownLatch(2));
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
