package com.idavy.drtops.domain.location;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class LocationQualityEvaluator {
    public LocationQualityDecision evaluate(Input input) {
        Set<LocationQualityReason> reasons = EnumSet.noneOf(LocationQualityReason.class);
        if (invalidCoordinate(input.longitude(), input.latitude())) reasons.add(LocationQualityReason.INVALID_COORDINATE);
        Duration receiveDelay = Duration.between(input.terminalLocatedAt(), input.gatewayReceivedAt());
        if (receiveDelay.compareTo(Duration.ofSeconds(120)) > 0) reasons.add(LocationQualityReason.RECEIVE_DELAY_EXCEEDED);
        else if (receiveDelay.compareTo(Duration.ofSeconds(30)) > 0) reasons.add(LocationQualityReason.RECEIVE_DELAY);
        Duration ahead = Duration.between(input.gatewayReceivedAt(), input.terminalLocatedAt());
        if (ahead.compareTo(Duration.ofSeconds(120)) > 0) reasons.add(LocationQualityReason.TERMINAL_TIME_AHEAD_EXCEEDED);
        else if (ahead.compareTo(Duration.ZERO) > 0) reasons.add(LocationQualityReason.TERMINAL_TIME_AHEAD);
        if (input.latestSourceTerminalLocatedAt() != null
                && input.terminalLocatedAt().isBefore(input.latestSourceTerminalLocatedAt())) {
            reasons.add(LocationQualityReason.OUT_OF_ORDER);
        }
        if ((input.statusBits() & 0x02) == 0) reasons.add(LocationQualityReason.POSITION_INVALID);
        if (input.speedKph() != null && input.speedKph().compareTo(new BigDecimal("140")) > 0) reasons.add(LocationQualityReason.SPEED_EXCEEDED);
        else if (input.speedKph() != null && input.speedKph().compareTo(new BigDecimal("120")) >= 0) reasons.add(LocationQualityReason.SPEED_WARNING);
        if (input.impliedSpeedKph() != null && input.impliedSpeedKph() > 180d) reasons.add(LocationQualityReason.IMPLIED_SPEED_EXCEEDED);
        if (!input.insideServiceArea()) reasons.add(LocationQualityReason.OUTSIDE_SERVICE_AREA);
        if (input.satelliteCount() == null) reasons.add(LocationQualityReason.OPTIONAL_FIELDS_MISSING);
        if (input.consecutiveQuarantines() >= 3) reasons.add(LocationQualityReason.CONSECUTIVE_QUARANTINE);
        LocationQualityStatus status = status(reasons);
        boolean applySnapshot = status == LocationQualityStatus.GOOD || status == LocationQualityStatus.WARNING;
        if (reasons.contains(LocationQualityReason.OUT_OF_ORDER)) applySnapshot = false;
        return new LocationQualityDecision(status, reasons, status != LocationQualityStatus.REJECTED, applySnapshot);
    }

    private static boolean invalidCoordinate(BigDecimal longitude, BigDecimal latitude) {
        return longitude == null || latitude == null || longitude.signum() == 0 || latitude.signum() == 0
                || longitude.compareTo(new BigDecimal("-180")) < 0 || longitude.compareTo(new BigDecimal("180")) > 0
                || latitude.compareTo(new BigDecimal("-90")) < 0 || latitude.compareTo(new BigDecimal("90")) > 0;
    }
    private static LocationQualityStatus status(Set<LocationQualityReason> reasons) {
        if (reasons.contains(LocationQualityReason.INVALID_COORDINATE)) return LocationQualityStatus.REJECTED;
        if (reasons.stream().anyMatch(reason -> switch (reason) {
            case RECEIVE_DELAY_EXCEEDED, TERMINAL_TIME_AHEAD_EXCEEDED, POSITION_INVALID, SPEED_EXCEEDED,
                    IMPLIED_SPEED_EXCEEDED, CONSECUTIVE_QUARANTINE -> true;
            default -> false;
        })) return LocationQualityStatus.QUARANTINED;
        return reasons.isEmpty() ? LocationQualityStatus.GOOD : LocationQualityStatus.WARNING;
    }

    public record Input(BigDecimal longitude, BigDecimal latitude, Instant terminalLocatedAt, Instant gatewayReceivedAt,
                        Instant now, Instant latestSourceTerminalLocatedAt, long statusBits, BigDecimal speedKph,
                        Integer satelliteCount, boolean insideServiceArea, Double impliedSpeedKph,
                        int consecutiveQuarantines) { }
}
