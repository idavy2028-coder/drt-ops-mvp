package com.idavy.drtops.domain.audit;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.idavy.drtops.domain.alarm.VehicleAlarm;
import com.idavy.drtops.domain.terminal.JtTerminalSessionLease;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Successful state transitions only; failure to persist audit rolls back the caller's transaction. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class GatewayStateAudit {
    private final AuditLogRepository repository;
    public GatewayStateAudit(AuditLogRepository repository) { this.repository = repository; }

    public void leaseRenewed(JtTerminalSessionLease lease, OffsetDateTime previousExpiry) {
        leaseChanged(lease, "SESSION_LEASE_RENEWED", "VALID_OWNER_RENEWAL", previousExpiry);
    }

    public void leaseReleased(JtTerminalSessionLease lease) {
        leaseChanged(lease, "SESSION_LEASE_RELEASED", lease.getReleaseReason(), lease.getExpiresAt());
    }

    private void leaseChanged(JtTerminalSessionLease lease, String action, String reason,
                              OffsetDateTime previousExpiry) {
        var metadata = JsonNodeFactory.instance.objectNode()
                .put("connectionId", lease.getConnectionId().toString())
                .put("gatewayInstanceSha256", digest(lease.getGatewayInstance()))
                .put("leaseGeneration", lease.getLeaseGeneration())
                .put("version", lease.getVersion())
                .put("previousExpiresAt", previousExpiry.toString())
                .put("expiresAt", lease.getExpiresAt().toString())
                .put("lastValidMessageAt", lease.getLastValidMessageAt().toString());
        if (lease.getReleasedAt() != null) metadata.put("releasedAt", lease.getReleasedAt().toString());
        repository.save(AuditLog.record("JT_TERMINAL_SESSION_LEASE", lease.getTerminalId(),
                action, "GATEWAY", "jt-gateway", reason, metadata.toString()));
    }

    public void alarmChanged(VehicleAlarm alarm, String eventType) {
        if (!"ALARM_CREATED".equals(eventType) && !"ALARM_ENDED".equals(eventType)) {
            throw new IllegalArgumentException("unsupported alarm audit event");
        }
        var metadata = JsonNodeFactory.instance.objectNode()
                .put("terminalId", alarm.getTerminalId().toString())
                .put("onboardSystemId", alarm.getOnboardSystemId().toString())
                .put("vehicleId", alarm.getVehicleId().toString())
                .put("locationEventId", alarm.getLocationEventId().toString())
                .put("module", alarm.getModule())
                .put("alarmTypeCode", alarm.getAlarmTypeCode())
                .put("occurredAt", alarm.getOccurredAt().toString());
        if (alarm.getEndedAt() != null) metadata.put("endedAt", alarm.getEndedAt().toString());
        repository.save(AuditLog.record("VEHICLE_ALARM", alarm.getId(), "VEHICLE_" + eventType,
                "GATEWAY", "jt-gateway", eventType, metadata.toString()));
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
