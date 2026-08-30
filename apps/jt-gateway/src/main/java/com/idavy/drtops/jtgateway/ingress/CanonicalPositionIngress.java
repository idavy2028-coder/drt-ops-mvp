package com.idavy.drtops.jtgateway.ingress;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable gateway-to-operations representation of an unconverted terminal position. */
public record CanonicalPositionIngress(
        UUID terminalId,
        UUID onboardSystemId,
        UUID vehicleId,
        String sourceRole,
        String protocolVersion,
        int messageSerialNo,
        BigDecimal rawLongitude,
        BigDecimal rawLatitude,
        String rawCoordinateSystem,
        Instant terminalLocatedAt,
        Instant gatewayReceivedAt,
        Long alarmBits,
        Long statusBits,
        BigDecimal speedKph,
        Integer directionDegrees,
        Integer altitudeMeters,
        Integer satelliteCount,
        String payloadDigest) {
    public CanonicalPositionIngress {
        Objects.requireNonNull(terminalId, "terminalId");
        Objects.requireNonNull(onboardSystemId, "onboardSystemId");
        Objects.requireNonNull(vehicleId, "vehicleId");
        if (!"LOCATION_PRIMARY".equals(sourceRole) && !"LOCATION_BACKUP".equals(sourceRole)) {
            throw new IllegalArgumentException(
                    "sourceRole must be LOCATION_PRIMARY or LOCATION_BACKUP");
        }
        if (protocolVersion == null || protocolVersion.isBlank()) {
            throw new IllegalArgumentException("protocolVersion must not be blank");
        }
        if (messageSerialNo < 0 || messageSerialNo > 0xffff) {
            throw new IllegalArgumentException("messageSerialNo must be an unsigned short");
        }
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
