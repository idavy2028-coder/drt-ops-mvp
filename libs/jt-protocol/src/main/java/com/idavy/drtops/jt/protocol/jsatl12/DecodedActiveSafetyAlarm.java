package com.idavy.drtops.jt.protocol.jsatl12;

import java.math.BigDecimal;
import java.time.Instant;

/** Standard-neutral decoded alarm. Sensitive terminal bytes are represented only by a digest. */
public record DecodedActiveSafetyAlarm(
        String module,
        long alarmId,
        int typeCode,
        String alarmType,
        String state,
        int level,
        String terminalAlarmIdentifier,
        Instant occurredAt,
        BigDecimal longitude,
        BigDecimal latitude,
        BigDecimal speedKph,
        int vehicleStatus,
        int alarmSequenceNumber,
        int attachmentCount,
        String extensionPayloadDigest) { }
