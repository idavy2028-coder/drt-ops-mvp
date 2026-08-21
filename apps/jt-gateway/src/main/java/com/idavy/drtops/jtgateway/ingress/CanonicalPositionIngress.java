package com.idavy.drtops.jtgateway.ingress;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable gateway-to-operations representation of an unconverted terminal position. */
public record CanonicalPositionIngress(
        UUID terminalId,
        UUID vehicleId,
        String protocolVersion,
        int messageSerialNo,
        BigDecimal rawLongitude,
        BigDecimal rawLatitude,
        String rawCoordinateSystem,
        Instant terminalLocatedAt,
        Instant gatewayReceivedAt,
        int alarmBits,
        int statusBits,
        BigDecimal speedKph,
        Integer directionDegrees,
        Integer altitudeMeters,
        Integer satelliteCount,
        String payloadDigest) {
    public CanonicalPositionIngress {
        Objects.requireNonNull(terminalId, "terminalId");
        Objects.requireNonNull(vehicleId, "vehicleId");
        Objects.requireNonNull(protocolVersion, "protocolVersion");
        Objects.requireNonNull(rawLongitude, "rawLongitude");
        Objects.requireNonNull(rawLatitude, "rawLatitude");
        if (!"WGS84".equals(rawCoordinateSystem) && !"GCJ02".equals(rawCoordinateSystem)) {
            throw new IllegalArgumentException("rawCoordinateSystem must be WGS84 or GCJ02");
        }
        Objects.requireNonNull(terminalLocatedAt, "terminalLocatedAt");
        Objects.requireNonNull(gatewayReceivedAt, "gatewayReceivedAt");
        Objects.requireNonNull(speedKph, "speedKph");
        Objects.requireNonNull(directionDegrees, "directionDegrees");
        Objects.requireNonNull(altitudeMeters, "altitudeMeters");
        if (payloadDigest == null || payloadDigest.isBlank()) {
            throw new IllegalArgumentException("payloadDigest must not be blank");
        }
    }
}
