package com.idavy.drtops.domain.location;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CanonicalPositionIngress(UUID terminalId, UUID vehicleId, String protocolVersion, int messageSerialNo,
        BigDecimal rawLongitude, BigDecimal rawLatitude, String rawCoordinateSystem, Instant terminalLocatedAt,
        Instant gatewayReceivedAt, Long alarmBits, Long statusBits, BigDecimal speedKph, Integer directionDegrees,
        Integer altitudeMeters, Integer satelliteCount, String payloadDigest) { }
