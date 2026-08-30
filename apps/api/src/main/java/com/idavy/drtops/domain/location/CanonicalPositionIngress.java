package com.idavy.drtops.domain.location;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

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
        if (messageSerialNo < 0 || messageSerialNo > 0xffff) {
            throw new IllegalArgumentException("messageSerialNo must be an unsigned short");
        }
    }
}
