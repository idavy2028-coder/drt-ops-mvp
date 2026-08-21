package com.idavy.drtops.jtgateway.ingress;

import com.idavy.drtops.jt.protocol.core.LocationReport;
import com.idavy.drtops.jt.protocol.jsatl12.ActiveSafetyCapabilityProfile;
import com.idavy.drtops.jt.protocol.jsatl12.ActiveSafetyDecodeResult;
import com.idavy.drtops.jt.protocol.jsatl12.ActiveSafetyExtensionRegistry;
import com.idavy.drtops.jt.protocol.jsatl12.Jsatl12ActiveSafetyModule;
import com.idavy.drtops.jtgateway.session.TerminalSession;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Decodes only the standard/modules recorded on the authenticated terminal session. */
public final class ActiveSafetyAlarmRouter {
    private final ActiveSafetyExtensionRegistry extensions;

    public ActiveSafetyAlarmRouter() {
        this(new ActiveSafetyExtensionRegistry(List.of(new Jsatl12ActiveSafetyModule())));
    }

    ActiveSafetyAlarmRouter(ActiveSafetyExtensionRegistry extensions) {
        this.extensions = Objects.requireNonNull(extensions, "extensions");
    }

    public Result route(
            TerminalSession session,
            LocationReport position,
            Instant gatewayReceivedAt,
            java.util.UUID positionIdempotencyKey) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(gatewayReceivedAt, "gatewayReceivedAt");
        Objects.requireNonNull(positionIdempotencyKey, "positionIdempotencyKey");
        boolean hasActiveSafetyItem = position.additionalItems().stream()
                .anyMatch(item -> item.id() == 0x64 || item.id() == 0x65);
        if (!hasActiveSafetyItem) {
            return new Result(List.of(), List.of());
        }
        ActiveSafetyDecodeResult decoded = extensions.decode(
                new ActiveSafetyCapabilityProfile(session.activeSafetyStandard(), session.activeSafetyModules()), position);
        List<CanonicalVehicleAlarm> alarms = decoded.alarms().stream()
                .map(alarm -> new CanonicalVehicleAlarm(session.terminalId(), session.vehicleId(),
                        session.activeSafetyStandard(), alarm.module(), alarm.alarmId(), alarm.typeCode(), alarm.alarmType(),
                        alarm.state(), alarm.level(), alarm.terminalAlarmIdentifier(), alarm.occurredAt(), gatewayReceivedAt,
                        alarm.longitude(), alarm.latitude(), alarm.speedKph(), alarm.vehicleStatus(),
                        alarm.alarmSequenceNumber(), alarm.attachmentCount(), positionIdempotencyKey, "UNASSESSED",
                        alarm.extensionPayloadDigest()))
                .toList();
        return new Result(alarms, decoded.rejections().stream().map(value -> new Rejection(value.reasonCode())).toList());
    }

    public record Result(List<CanonicalVehicleAlarm> alarms, List<Rejection> rejections) {
        public Result { alarms = List.copyOf(alarms); rejections = List.copyOf(rejections); }
    }

    public record Rejection(String reasonCode) { }
}
