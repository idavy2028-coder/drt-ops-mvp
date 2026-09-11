package com.idavy.drtops.domain.audit;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.domain.alarm.VehicleAlarm;
import com.idavy.drtops.domain.terminal.JtTerminalSessionLease;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;


class GatewayStateAuditTest {
    @Test
    void leaseAuditHasSafeCorrelatableMetadataAndNoCredentials() throws Exception {
        var rows = new java.util.ArrayList<AuditLog>();
        var repository = (AuditLogRepository) java.lang.reflect.Proxy.newProxyInstance(
                AuditLogRepository.class.getClassLoader(), new Class<?>[]{AuditLogRepository.class},
                (proxy, method, args) -> { if (method.getName().equals("save")) { rows.add((AuditLog)args[0]); return args[0]; } throw new AssertionError(method.getName()); });
        var audit = new GatewayStateAudit(repository);
        var lease = org.springframework.beans.BeanUtils.instantiateClass(JtTerminalSessionLease.class);
        UUID terminal = UUID.randomUUID(), connection = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-09-11T00:00:00Z");
        org.springframework.test.util.ReflectionTestUtils.setField(lease, "terminalId", terminal);
        org.springframework.test.util.ReflectionTestUtils.setField(lease, "connectionId", connection);
        org.springframework.test.util.ReflectionTestUtils.setField(lease, "gatewayInstance", "\"".repeat(120));
        org.springframework.test.util.ReflectionTestUtils.setField(lease, "leaseGeneration", 2L);
        org.springframework.test.util.ReflectionTestUtils.setField(lease, "version", 3L);
        org.springframework.test.util.ReflectionTestUtils.setField(lease, "lastValidMessageAt", now);
        org.springframework.test.util.ReflectionTestUtils.setField(lease, "expiresAt", now.plusSeconds(180));
        audit.leaseRenewed(lease, now.plusSeconds(150));
        assertThat(rows).hasSize(1);
        var row = rows.getFirst();
        assertThat(row.getEntityId()).isEqualTo(terminal);
        assertThat(row.getAction()).isEqualTo("SESSION_LEASE_RENEWED");
        assertThat(row.getMetadataJson()).hasSizeLessThan(1000).doesNotContain("token", "password");
        var json = new ObjectMapper().readTree(row.getMetadataJson());
        assertThat(json.path("connectionId").asText()).isEqualTo(connection.toString());
        assertThat(json.path("gatewayInstanceSha256").asText()).matches("[0-9a-f]{64}");
        assertThat(json.path("version").asLong()).isEqualTo(3);
        assertThat(json.path("leaseGeneration").asLong()).isEqualTo(2);
    }
    @Test
    void unknownAlarmActionCannotCreateMisleadingAudit() {
        var rows = new java.util.ArrayList<AuditLog>();
        var repository = (AuditLogRepository) java.lang.reflect.Proxy.newProxyInstance(
                AuditLogRepository.class.getClassLoader(), new Class<?>[]{AuditLogRepository.class},
                (proxy, method, args) -> { if (method.getName().equals("save")) { rows.add((AuditLog)args[0]); return args[0]; } throw new AssertionError(method.getName()); });
        assertThatThrownBy(() -> new GatewayStateAudit(repository).alarmChanged(null, "UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(rows).isEmpty();
    }
}
