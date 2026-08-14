package com.idavy.drtops.domain.alarm;

import com.idavy.drtops.domain.audit.AuditLog;
import com.idavy.drtops.domain.audit.AuditLogRepository;
import jakarta.persistence.OptimisticLockException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class VehicleAlarmActionService {
    private final VehicleAlarmRepository alarms;
    private final VehicleAlarmActionRepository actions;
    private final VehicleAlarmOutboxRepository outbox;
    private final AuditLogRepository auditLogs;
    private final VehicleAlarmAuthorization authorization;

    public VehicleAlarmActionService(
            VehicleAlarmRepository alarms,
            VehicleAlarmActionRepository actions,
            VehicleAlarmOutboxRepository outbox,
            AuditLogRepository auditLogs,
            VehicleAlarmAuthorization authorization) {
        this.alarms = Objects.requireNonNull(alarms);
        this.actions = Objects.requireNonNull(actions);
        this.outbox = Objects.requireNonNull(outbox);
        this.auditLogs = Objects.requireNonNull(auditLogs);
        this.authorization = Objects.requireNonNull(authorization);
    }

    @Transactional
    public VehicleAlarm transition(
            UUID alarmId,
            long expectedVersion,
            UUID actorId,
            VehicleAlarm.ProcessingStatus targetStatus,
            String reason) {
        requireCommand(alarmId, actorId, targetStatus, reason);
        if (!authorization.mayHandle(actorId)) {
            throw new VehicleAlarmAuthorizationException("vehicle alarm handling is forbidden");
        }
        VehicleAlarm alarm = alarms.findById(alarmId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "vehicle alarm not found"));
        if (alarm.getVersion() != expectedVersion) {
            throw new VehicleAlarmVersionConflictException("vehicle alarm version conflict");
        }
        VehicleAlarm.ProcessingStatus currentStatus = alarm.getProcessingStatus();
        boolean reopening = isTerminal(currentStatus) && targetStatus == VehicleAlarm.ProcessingStatus.PROCESSING;
        if (reopening && !authorization.mayReopen(actorId)) {
            throw new VehicleAlarmAuthorizationException("vehicle alarm reopen is forbidden");
        }
        if (!isAllowed(currentStatus, targetStatus)) {
            throw new VehicleAlarmActionConflictException("invalid vehicle alarm status transition");
        }
        Instant occurredAt = Instant.now();
        String actionType = actionType(currentStatus, targetStatus);
        alarm.transitionTo(targetStatus, actorId, occurredAt);
        actions.save(VehicleAlarmAction.record(
                alarm.getId(), actionType, currentStatus, targetStatus, actorId, reason, occurredAt));
        auditLogs.save(AuditLog.record(
                "VEHICLE_ALARM", alarm.getId(), auditAction(actionType), "USER", actorId.toString(), reason,
                metadata(currentStatus, targetStatus)));
        outbox.save(VehicleAlarmOutboxEvent.pending(alarm.getId(), "ALARM_STATUS_CHANGED"));
        try {
            return alarms.saveAndFlush(alarm);
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException exception) {
            throw new VehicleAlarmVersionConflictException("vehicle alarm version conflict");
        }
    }

    private static void requireCommand(
            UUID alarmId, UUID actorId, VehicleAlarm.ProcessingStatus targetStatus, String reason) {
        if (alarmId == null || actorId == null || targetStatus == null) {
            throw new IllegalArgumentException("invalid vehicle alarm action command");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        if (reason.length() > 300) {
            throw new IllegalArgumentException("reason is too long");
        }
    }

    private static boolean isAllowed(
            VehicleAlarm.ProcessingStatus current, VehicleAlarm.ProcessingStatus target) {
        return switch (current) {
            case NEW -> EnumSet.of(VehicleAlarm.ProcessingStatus.ACKNOWLEDGED,
                    VehicleAlarm.ProcessingStatus.FALSE_POSITIVE).contains(target);
            case ACKNOWLEDGED -> EnumSet.of(VehicleAlarm.ProcessingStatus.PROCESSING,
                    VehicleAlarm.ProcessingStatus.RESOLVED, VehicleAlarm.ProcessingStatus.FALSE_POSITIVE).contains(target);
            case PROCESSING -> EnumSet.of(VehicleAlarm.ProcessingStatus.RESOLVED,
                    VehicleAlarm.ProcessingStatus.FALSE_POSITIVE).contains(target);
            case RESOLVED, FALSE_POSITIVE -> target == VehicleAlarm.ProcessingStatus.PROCESSING;
        };
    }

    private static boolean isTerminal(VehicleAlarm.ProcessingStatus status) {
        return status == VehicleAlarm.ProcessingStatus.RESOLVED
                || status == VehicleAlarm.ProcessingStatus.FALSE_POSITIVE;
    }

    private static String actionType(
            VehicleAlarm.ProcessingStatus current, VehicleAlarm.ProcessingStatus target) {
        if (isTerminal(current) && target == VehicleAlarm.ProcessingStatus.PROCESSING) return "REOPEN";
        return switch (target) {
            case ACKNOWLEDGED -> "ACKNOWLEDGE";
            case PROCESSING -> "TAKE_OVER";
            case RESOLVED -> "RESOLVE";
            case FALSE_POSITIVE -> "MARK_FALSE_POSITIVE";
            case NEW -> throw new VehicleAlarmActionConflictException("invalid vehicle alarm status transition");
        };
    }

    private static String auditAction(String actionType) {
        return Map.of(
                "ACKNOWLEDGE", "VEHICLE_ALARM_ACKNOWLEDGED",
                "TAKE_OVER", "VEHICLE_ALARM_PROCESSING",
                "RESOLVE", "VEHICLE_ALARM_RESOLVED",
                "MARK_FALSE_POSITIVE", "VEHICLE_ALARM_FALSE_POSITIVE",
                "REOPEN", "VEHICLE_ALARM_REOPENED").get(actionType);
    }

    private static String metadata(
            VehicleAlarm.ProcessingStatus currentStatus, VehicleAlarm.ProcessingStatus targetStatus) {
        return "{\"fromStatus\":\"" + currentStatus + "\",\"toStatus\":\"" + targetStatus + "\"}";
    }
}
