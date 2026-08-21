package com.idavy.drtops.jt.protocol.jsatl12;

import com.idavy.drtops.jt.protocol.core.LocationReport;
import java.util.List;

/** T/JSATL12-2017 implementation of the standard-neutral active-safety extension point. */
public final class Jsatl12ActiveSafetyModule implements ActiveSafetyExtension {
    private final Jsatl12AlarmExtensionCodec codec = new Jsatl12AlarmExtensionCodec();

    @Override public String standardCode() {
        return Jsatl12AlarmExtensionCodec.STANDARD_CODE;
    }

    @Override public boolean supports(ActiveSafetyCapabilityProfile profile) {
        return standardCode().equals(profile.standardCode());
    }

    @Override public ActiveSafetyDecodeResult decode(LocationReport position) {
        Jsatl12AlarmExtensionCodec.DecodeResult decoded = codec.decode(position);
        return new ActiveSafetyDecodeResult(
                decoded.alarms().stream().map(value -> new DecodedActiveSafetyAlarm(
                        value.module(), value.alarmId(), value.typeCode(), value.alarmType(), value.state(), value.level(),
                        value.terminalAlarmIdentifier(), value.occurredAt(), value.longitude(), value.latitude(),
                        value.speedKph(), value.vehicleStatus(), value.alarmSequenceNumber(), value.attachmentCount(),
                        value.extensionPayloadDigest())).toList(),
                decoded.rejections().stream()
                        .map(value -> new ActiveSafetyDecodeResult.Rejection(
                                value.itemId() == 0x64 ? "ADAS" : value.itemId() == 0x65 ? "DMS" : null,
                                value.reasonCode()))
                        .toList());
    }
}
