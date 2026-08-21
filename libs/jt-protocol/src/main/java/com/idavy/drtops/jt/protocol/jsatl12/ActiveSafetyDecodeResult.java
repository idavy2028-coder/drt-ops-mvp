package com.idavy.drtops.jt.protocol.jsatl12;

import java.util.List;

public record ActiveSafetyDecodeResult(
        List<DecodedActiveSafetyAlarm> alarms,
        List<Rejection> rejections) {
    public ActiveSafetyDecodeResult {
        alarms = List.copyOf(alarms);
        rejections = List.copyOf(rejections);
    }

    public static ActiveSafetyDecodeResult rejected(String reasonCode) {
        return new ActiveSafetyDecodeResult(List.of(), List.of(new Rejection(reasonCode)));
    }

    public record Rejection(String module, String reasonCode) {
        public Rejection(String reasonCode) {
            this(null, reasonCode);
        }
    }
}
