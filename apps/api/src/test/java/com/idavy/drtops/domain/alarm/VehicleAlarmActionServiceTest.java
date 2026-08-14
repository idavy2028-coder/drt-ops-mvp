package com.idavy.drtops.domain.alarm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idavy.drtops.domain.audit.AuditLogRepository;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:vehicle_alarm_actions;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import(VehicleAlarmActionServiceTest.AuthorizationConfiguration.class)
class VehicleAlarmActionServiceTest {
    private static final UUID ACTOR_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ADMIN_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID DENIED_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Autowired VehicleAlarmActionService service;
    @Autowired VehicleAlarmRepository alarms;
    @Autowired VehicleAlarmActionRepository actions;
    @Autowired VehicleAlarmOutboxRepository outbox;
    @Autowired AuditLogRepository auditLogs;
    private static volatile ReopenBarrier reopenBarrier = ReopenBarrier.disabled();

    @Test
    void mapsTheAlarmVersionForOptimisticConcurrencyControl() throws Exception {
        assertThat(VehicleAlarm.class.getDeclaredField("version").getAnnotation(Version.class)).isNotNull();
    }

    @BeforeEach
    void reset() {
        reopenBarrier = ReopenBarrier.disabled();
        auditLogs.deleteAll();
        actions.deleteAll();
        outbox.deleteAll();
        alarms.deleteAll();
    }

    @Test
    void recordsAHandledStateActionAuditAndOutboxInTheSameWorkflow() {
        VehicleAlarm alarm = saveNewAlarm();

        VehicleAlarm changed = service.transition(
                alarm.getId(), alarm.getVersion(), ACTOR_ID, VehicleAlarm.ProcessingStatus.ACKNOWLEDGED, "已核对");

        assertThat(changed.getProcessingStatus()).isEqualTo(VehicleAlarm.ProcessingStatus.ACKNOWLEDGED);
        assertThat(changed.getHandledBy()).isEqualTo(ACTOR_ID);
        assertThat(changed.getHandledAt()).isNotNull();
        assertThat(changed.getVersion()).isEqualTo(1);
        assertThat(actions.findAll()).singleElement().satisfies(action -> {
            assertThat(action.getActionType()).isEqualTo("ACKNOWLEDGE");
            assertThat(action.getFromStatus()).isEqualTo("NEW");
            assertThat(action.getToStatus()).isEqualTo("ACKNOWLEDGED");
            assertThat(action.getActorId()).isEqualTo(ACTOR_ID);
            assertThat(action.getReason()).isEqualTo("已核对");
        });
        assertThat(auditLogs.findAll()).singleElement().satisfies(audit -> {
            assertThat(audit.getEntityType()).isEqualTo("VEHICLE_ALARM");
            assertThat(audit.getEntityId()).isEqualTo(alarm.getId());
            assertThat(audit.getAction()).isEqualTo("VEHICLE_ALARM_ACKNOWLEDGED");
            assertThat(audit.getActorId()).isEqualTo(ACTOR_ID.toString());
            assertThat(audit.getReason()).isEqualTo("已核对");
        });
        assertThat(outbox.findAll()).singleElement().satisfies(event -> {
            assertThat(event.getVehicleAlarmId()).isEqualTo(alarm.getId());
            assertThat(event.getEventType()).isEqualTo("ALARM_STATUS_CHANGED");
            assertThat(event.getStatus()).isEqualTo("PENDING");
        });
    }

    @Test
    void allowsEverySpecifiedNonTerminalTransition() {
        assertTransition(saveNewAlarm(), ACTOR_ID, VehicleAlarm.ProcessingStatus.ACKNOWLEDGED);
        assertTransition(saveNewAlarm(), ACTOR_ID, VehicleAlarm.ProcessingStatus.FALSE_POSITIVE);

        VehicleAlarm acknowledged = assertTransition(
                saveNewAlarm(), ACTOR_ID, VehicleAlarm.ProcessingStatus.ACKNOWLEDGED);
        assertTransition(acknowledged, ACTOR_ID, VehicleAlarm.ProcessingStatus.PROCESSING);
        acknowledged = assertTransition(saveNewAlarm(), ACTOR_ID, VehicleAlarm.ProcessingStatus.ACKNOWLEDGED);
        assertTransition(acknowledged, ACTOR_ID, VehicleAlarm.ProcessingStatus.RESOLVED);
        acknowledged = assertTransition(saveNewAlarm(), ACTOR_ID, VehicleAlarm.ProcessingStatus.ACKNOWLEDGED);
        assertTransition(acknowledged, ACTOR_ID, VehicleAlarm.ProcessingStatus.FALSE_POSITIVE);

        VehicleAlarm processing = assertTransition(
                assertTransition(saveNewAlarm(), ACTOR_ID, VehicleAlarm.ProcessingStatus.ACKNOWLEDGED),
                ACTOR_ID, VehicleAlarm.ProcessingStatus.PROCESSING);
        assertTransition(processing, ACTOR_ID, VehicleAlarm.ProcessingStatus.RESOLVED);
        processing = assertTransition(
                assertTransition(saveNewAlarm(), ACTOR_ID, VehicleAlarm.ProcessingStatus.ACKNOWLEDGED),
                ACTOR_ID, VehicleAlarm.ProcessingStatus.PROCESSING);
        assertTransition(processing, ACTOR_ID, VehicleAlarm.ProcessingStatus.FALSE_POSITIVE);
    }

    @Test
    void rejectsIllegalTransitionsAndBlankReasonsBeforeAppendingAnyRecord() {
        VehicleAlarm alarm = saveNewAlarm();

        assertThatThrownBy(() -> service.transition(
                alarm.getId(), alarm.getVersion(), ACTOR_ID, VehicleAlarm.ProcessingStatus.PROCESSING, "非法跳转"))
                .isInstanceOf(VehicleAlarmActionConflictException.class)
                .hasMessage("invalid vehicle alarm status transition");
        assertThatThrownBy(() -> service.transition(
                alarm.getId(), alarm.getVersion(), ACTOR_ID, VehicleAlarm.ProcessingStatus.ACKNOWLEDGED, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("reason must not be blank");

        assertThat(alarms.findById(alarm.getId()).orElseThrow().getProcessingStatus())
                .isEqualTo(VehicleAlarm.ProcessingStatus.NEW);
        assertThat(actions.count()).isZero();
        assertThat(auditLogs.count()).isZero();
        assertThat(outbox.count()).isZero();
    }

    @Test
    void rejectsEveryOtherTerminalOrBackwardsTransition() {
        VehicleAlarm acknowledged = assertTransition(
                saveNewAlarm(), ACTOR_ID, VehicleAlarm.ProcessingStatus.ACKNOWLEDGED);
        VehicleAlarm processing = assertTransition(
                acknowledged, ACTOR_ID, VehicleAlarm.ProcessingStatus.PROCESSING);
        VehicleAlarm resolved = assertTransition(processing, ACTOR_ID, VehicleAlarm.ProcessingStatus.RESOLVED);
        VehicleAlarm falsePositive = assertTransition(
                saveNewAlarm(), ACTOR_ID, VehicleAlarm.ProcessingStatus.FALSE_POSITIVE);

        assertThatThrownBy(() -> service.transition(
                acknowledged.getId(), acknowledged.getVersion(), ACTOR_ID, VehicleAlarm.ProcessingStatus.NEW, "回退"))
                .isInstanceOf(VehicleAlarmActionConflictException.class);
        assertThatThrownBy(() -> service.transition(
                processing.getId(), processing.getVersion(), ACTOR_ID, VehicleAlarm.ProcessingStatus.ACKNOWLEDGED, "回退"))
                .isInstanceOf(VehicleAlarmActionConflictException.class);
        assertThatThrownBy(() -> service.transition(
                resolved.getId(), resolved.getVersion(), ADMIN_ID, VehicleAlarm.ProcessingStatus.RESOLVED, "重复结案"))
                .isInstanceOf(VehicleAlarmActionConflictException.class);
        assertThatThrownBy(() -> service.transition(
                falsePositive.getId(), falsePositive.getVersion(), ADMIN_ID, VehicleAlarm.ProcessingStatus.RESOLVED, "重复结案"))
                .isInstanceOf(VehicleAlarmActionConflictException.class);
        assertThat(service.transition(
                falsePositive.getId(), falsePositive.getVersion(), ADMIN_ID,
                VehicleAlarm.ProcessingStatus.PROCESSING, "恢复调查").getProcessingStatus())
                .isEqualTo(VehicleAlarm.ProcessingStatus.PROCESSING);
    }

    @Test
    void reopensTerminalAlarmsOnlyForTheDomainAuthorizedSystemAdministrator() {
        VehicleAlarm resolved = assertTransition(
                assertTransition(saveNewAlarm(), ACTOR_ID, VehicleAlarm.ProcessingStatus.ACKNOWLEDGED),
                ACTOR_ID, VehicleAlarm.ProcessingStatus.RESOLVED);

        assertThatThrownBy(() -> service.transition(
                resolved.getId(), resolved.getVersion(), ACTOR_ID, VehicleAlarm.ProcessingStatus.PROCESSING, "需要复核"))
                .isInstanceOf(VehicleAlarmAuthorizationException.class)
                .hasMessage("vehicle alarm reopen is forbidden");

        VehicleAlarm reopened = service.transition(
                resolved.getId(), resolved.getVersion(), ADMIN_ID, VehicleAlarm.ProcessingStatus.PROCESSING, "需要复核");

        assertThat(reopened.getProcessingStatus()).isEqualTo(VehicleAlarm.ProcessingStatus.PROCESSING);
        assertThat(actions.findAll()).filteredOn(action -> action.getActionType().equals("REOPEN"))
                .singleElement().satisfies(action -> assertThat(action.getActorId()).isEqualTo(ADMIN_ID));
    }

    @Test
    void rejectsUnauthorizedHandlingAndStaleVersionsWithoutOverwritingTheCommittedWorkflow() {
        VehicleAlarm alarm = saveNewAlarm();

        assertThatThrownBy(() -> service.transition(
                alarm.getId(), alarm.getVersion(), DENIED_ID, VehicleAlarm.ProcessingStatus.ACKNOWLEDGED, "越权尝试"))
                .isInstanceOf(VehicleAlarmAuthorizationException.class)
                .hasMessage("vehicle alarm handling is forbidden");

        long staleVersion = alarm.getVersion();
        VehicleAlarm acknowledged = service.transition(
                alarm.getId(), staleVersion, ACTOR_ID, VehicleAlarm.ProcessingStatus.ACKNOWLEDGED, "首次确认");

        assertThatThrownBy(() -> service.transition(
                alarm.getId(), staleVersion, ACTOR_ID, VehicleAlarm.ProcessingStatus.FALSE_POSITIVE, "陈旧覆盖"))
                .isInstanceOf(VehicleAlarmActionConflictException.class)
                .hasMessage("vehicle alarm version conflict");
        assertThat(alarms.findById(alarm.getId()).orElseThrow().getProcessingStatus())
                .isEqualTo(VehicleAlarm.ProcessingStatus.ACKNOWLEDGED);
        assertThat(acknowledged.getVersion()).isEqualTo(1);
        assertThat(actions.findAll()).hasSize(1);
        assertThat(auditLogs.findAll()).hasSize(1);
        assertThat(outbox.findAll()).hasSize(1);
    }

    @Test
    void mapsAFlushTimeOptimisticLockRaceToAStableVersionConflictAndRollsBackLosingSideEffects() throws Exception {
        VehicleAlarm resolved = assertTransition(
                assertTransition(saveNewAlarm(), ACTOR_ID, VehicleAlarm.ProcessingStatus.ACKNOWLEDGED),
                ACTOR_ID, VehicleAlarm.ProcessingStatus.RESOLVED);
        long actionsBeforeRace = actions.count();
        long auditLogsBeforeRace = auditLogs.count();
        long outboxBeforeRace = outbox.count();
        reopenBarrier = new ReopenBarrier(2);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Throwable>> attempts = List.of(
                    executor.submit(() -> transitionFailure(resolved)),
                    executor.submit(() -> transitionFailure(resolved)));
            List<Throwable> failures = new ArrayList<>();
            for (Future<Throwable> attempt : attempts) {
                Throwable failure = attempt.get(15, TimeUnit.SECONDS);
                if (failure != null) failures.add(failure);
            }

            assertThat(failures).singleElement().satisfies(failure -> {
                assertThat(failure).isInstanceOf(VehicleAlarmVersionConflictException.class);
                assertThat(failure).hasMessage("vehicle alarm version conflict");
            });
            assertThat(actions.count()).isEqualTo(actionsBeforeRace + 1);
            assertThat(auditLogs.count()).isEqualTo(auditLogsBeforeRace + 1);
            assertThat(outbox.count()).isEqualTo(outboxBeforeRace + 1);
        } finally {
            executor.shutdownNow();
            reopenBarrier = ReopenBarrier.disabled();
        }
    }

    @Test
    void deniesAllActionsUntilTaskTwelveProvidesAnAuthorizationAdapter() {
        DenyAllVehicleAlarmAuthorization authorization = new DenyAllVehicleAlarmAuthorization();

        assertThat(authorization.mayHandle(ACTOR_ID)).isFalse();
        assertThat(authorization.mayReopen(ADMIN_ID)).isFalse();
    }

    private VehicleAlarm assertTransition(
            VehicleAlarm alarm, UUID actorId, VehicleAlarm.ProcessingStatus status) {
        return service.transition(alarm.getId(), alarm.getVersion(), actorId, status, "测试处置原因");
    }

    private Throwable transitionFailure(VehicleAlarm resolved) {
        try {
            service.transition(
                    resolved.getId(), resolved.getVersion(), ADMIN_ID,
                    VehicleAlarm.ProcessingStatus.PROCESSING, "并发重开原因");
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static final class ReopenBarrier {
        private final CountDownLatch bothRequests;

        private ReopenBarrier(int participants) {
            this.bothRequests = new CountDownLatch(participants);
        }

        static ReopenBarrier disabled() {
            return new ReopenBarrier(0);
        }

        boolean awaitBothRequests() {
            bothRequests.countDown();
            try {
                if (!bothRequests.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("concurrent reopen requests did not both pass version checks");
                }
                return true;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("concurrent reopen barrier interrupted", interrupted);
            }
        }
    }

    private VehicleAlarm saveNewAlarm() {
        VehicleAlarmIngressService.AlarmFact fact = new VehicleAlarmIngressService.AlarmFact(
                UUID.randomUUID(), UUID.randomUUID(), "T/JSATL12-2017", "ADAS", 1,
                "FORWARD_COLLISION", 4097L, "START", 1, "00000001",
                Instant.parse("2026-01-15T02:00:00Z"), Instant.parse("2026-01-15T02:00:01Z"),
                new BigDecimal("118.0000000"), new BigDecimal("32.0000000"), new BigDecimal("60.00"),
                UUID.randomUUID(), "UNASSESSED", "a".repeat(64));
        return alarms.saveAndFlush(VehicleAlarm.start(fact, UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", ""),
                new AlarmStore.LocationReference(UUID.randomUUID(), "GOOD", "[]")));
    }

    @TestConfiguration
    static class AuthorizationConfiguration {
        @Bean
        @Primary
        VehicleAlarmAuthorization vehicleAlarmAuthorization() {
            return new VehicleAlarmAuthorization() {
                @Override
                public boolean mayHandle(UUID actorId) {
                    return !DENIED_ID.equals(actorId);
                }

                @Override
                public boolean mayReopen(UUID actorId) {
                    return ADMIN_ID.equals(actorId) && reopenBarrier.awaitBothRequests();
                }
            };
        }
    }
}
