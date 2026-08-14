package com.idavy.drtops.domain.location;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idavy.drtops.domain.alarm.VehicleAlarmIngressService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GatewayIngressRouterTest {
    @Test
    void routesMixedPositionAndAlarmBatchesToTheirOwnIngresses() {
        RecordingRouterPort port = new RecordingRouterPort();
        GatewayIngressRouter router = new GatewayIngressRouter(port);
        GatewayIngressEnvelope position = new GatewayIngressEnvelope(1, UUID.randomUUID(), "POSITION", Instant.now(), "{}");
        GatewayIngressEnvelope alarm = new GatewayIngressEnvelope(1, UUID.randomUUID(), "ALARM", Instant.now(), "{}");
        GatewayIngressEnvelope audit = new GatewayIngressEnvelope(1, UUID.randomUUID(), "PROTOCOL_AUDIT", Instant.now(), "{}");

        router.ingest(List.of(position, alarm, audit));

        assertThat(port.positionBatches).containsExactly(List.of(position));
        assertThat(port.alarmBatches).containsExactly(List.of(alarm));
        assertThat(port.auditBatches).containsExactly(List.of(audit));
    }

    @Test
    void rejectsTheWholeMixedBatchBeforeGpsWhenAlarmDecodeFails() {
        RecordingRouterPort port = new RecordingRouterPort(); port.failAlarm = true;
        GatewayIngressRouter router = new GatewayIngressRouter(port);

        assertThatThrownBy(() -> router.ingest(List.of(
                new GatewayIngressEnvelope(1, UUID.randomUUID(), "POSITION", Instant.now(), "{}"),
                new GatewayIngressEnvelope(1, UUID.randomUUID(), "ALARM", Instant.now(), "bad"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(port.positionBatches).isEmpty();
    }

    static final class RecordingRouterPort implements GatewayIngressRouter.Port {
        final java.util.ArrayList<List<GatewayIngressEnvelope>> positionBatches = new java.util.ArrayList<>();
        final java.util.ArrayList<List<GatewayIngressEnvelope>> alarmBatches = new java.util.ArrayList<>();
        final java.util.ArrayList<List<GatewayIngressEnvelope>> auditBatches = new java.util.ArrayList<>();
        boolean failAlarm;
        @Override public void alarms(List<GatewayIngressEnvelope> batch) { if (failAlarm) throw new IllegalArgumentException("bad alarm"); alarmBatches.add(List.copyOf(batch)); }
        @Override public void audits(List<GatewayIngressEnvelope> batch) { auditBatches.add(List.copyOf(batch)); }
        @Override public void positions(List<GatewayIngressEnvelope> batch) { positionBatches.add(List.copyOf(batch)); }
    }
}
