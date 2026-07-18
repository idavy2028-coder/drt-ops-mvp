package com.idavy.drtops.domain.dispatch;

public record TravelEstimate(
        int distanceMeters,
        int durationSeconds,
        String provider,
        boolean degraded,
        String degradedReason) {

    public TravelEstimate {
        if (distanceMeters < 0 || durationSeconds < 0) {
            throw new IllegalArgumentException("行程距离和时间不能为负数");
        }
    }
}
