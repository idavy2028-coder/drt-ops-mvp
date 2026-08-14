package com.idavy.drtops.jtgateway.ingress;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** Gateway-normalized alarm without a raw frame or full terminal identity string. */
public record CanonicalVehicleAlarm(
        UUID terminalId, UUID vehicleId, String standard, String module, long terminalAlarmId,
        int typeCode, String alarmType,
        String state, int level, String terminalAlarmIdentifier, Instant occurredAt, Instant gatewayReceivedAt,
        BigDecimal longitude, BigDecimal latitude, BigDecimal speedKph, int vehicleStatus,
        int alarmSequenceNumber, int attachmentCount, UUID positionIdempotencyKey, String locationQualityStatus,
        String extensionPayloadDigest) {
    public CanonicalVehicleAlarm {
        Objects.requireNonNull(terminalId, "terminalId");
        Objects.requireNonNull(vehicleId, "vehicleId");
        Objects.requireNonNull(positionIdempotencyKey, "positionIdempotencyKey");
    }
}
