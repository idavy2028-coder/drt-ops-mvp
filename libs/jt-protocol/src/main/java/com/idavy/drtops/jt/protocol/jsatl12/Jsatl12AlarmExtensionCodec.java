package com.idavy.drtops.jt.protocol.jsatl12;

import com.idavy.drtops.jt.protocol.core.LocationReport;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** T/JSATL12-2017 0x0200 ADAS(0x64) and source DSM(0x65) alarm decoder. */
public final class Jsatl12AlarmExtensionCodec {
    public static final String STANDARD_CODE = "T/JSATL12-2017";
    private static final int ADAS = 0x64;
    private static final int DSM_SOURCE = 0x65;
    private static final int EXTENSION_LENGTH = 47;

    public DecodeResult decode(LocationReport position) {
        Objects.requireNonNull(position, "position");
        List<DecodedAlarm> alarms = new ArrayList<>();
        List<ExtensionRejection> rejections = new ArrayList<>();
        for (LocationReport.AdditionalItem item : position.additionalItems()) {
            if (item.id() != ADAS && item.id() != DSM_SOURCE) continue;
            byte[] value = item.value();
            if (value.length != EXTENSION_LENGTH) {
                rejections.add(new ExtensionRejection(item.id(), "ACTIVE_SAFETY_EXTENSION_REJECTED"));
                continue;
            }
            try {
                String module = item.id() == ADAS ? "ADAS" : "DMS";
                int stateCode = Byte.toUnsignedInt(value[4]);
                int typeCode = Byte.toUnsignedInt(value[5]);
                BigDecimal longitude = decimal(unsignedInt(value, 19));
                BigDecimal latitude = decimal(unsignedInt(value, 15));
                validateCoordinates(longitude, latitude);
                alarms.add(new DecodedAlarm(module, unsignedInt(value, 0), typeCode, type(module, typeCode), state(stateCode),
                        Byte.toUnsignedInt(value[6]), sha256(value, 31, 16), extensionTime(value),
                        longitude, latitude,
                        BigDecimal.valueOf(unsignedShort(value, 11)), unsignedShort(value, 29),
                        Byte.toUnsignedInt(value[44]), Byte.toUnsignedInt(value[45]), sha256(value)));
            } catch (RuntimeException malformedExtension) {
                rejections.add(new ExtensionRejection(item.id(), "ACTIVE_SAFETY_EXTENSION_REJECTED"));
            }
        }
        return new DecodeResult(alarms, rejections);
    }

    private static String state(int code) {
        if (code == 1) return "START";
        if (code == 2) return "END";
        throw new IllegalArgumentException("unsupported active safety lifecycle state");
    }

    private static void validateCoordinates(BigDecimal longitude, BigDecimal latitude) {
        if (longitude.signum() < 0 || longitude.compareTo(BigDecimal.valueOf(180)) > 0
                || latitude.signum() < 0 || latitude.compareTo(BigDecimal.valueOf(90)) > 0) {
            throw new IllegalArgumentException("active safety coordinates are out of range");
        }
    }
    private static String type(String module, int code) {
        if ("ADAS".equals(module)) return code == 1 ? "FORWARD_COLLISION" : code == 2 ? "LANE_DEPARTURE" : "UNKNOWN";
        return code == 1 ? "FATIGUE" : code == 2 ? "PHONE" : "UNKNOWN";
    }

    public record DecodeResult(List<DecodedAlarm> alarms, List<ExtensionRejection> rejections) {
        public DecodeResult { alarms = List.copyOf(alarms); rejections = List.copyOf(rejections); }
    }
    private static long unsignedInt(byte[] value, int offset) {
        return ((long) Byte.toUnsignedInt(value[offset]) << 24)
                | ((long) Byte.toUnsignedInt(value[offset + 1]) << 16)
                | ((long) Byte.toUnsignedInt(value[offset + 2]) << 8)
                | Byte.toUnsignedInt(value[offset + 3]);
    }

    private static int unsignedShort(byte[] value, int offset) {
        return (Byte.toUnsignedInt(value[offset]) << 8) | Byte.toUnsignedInt(value[offset + 1]);
    }

    private static BigDecimal decimal(long microDegrees) {
        return BigDecimal.valueOf(microDegrees, 6);
    }

    private static Instant extensionTime(byte[] value) {
        int year = 2000 + bcd(value[23]);
        return LocalDateTime.of(year, bcd(value[24]), bcd(value[25]), bcd(value[26]), bcd(value[27]), bcd(value[28]))
                .toInstant(ZoneOffset.ofHours(8));
    }

    private static int bcd(byte value) {
        int number = Byte.toUnsignedInt(value);
        int parsed = ((number >>> 4) * 10) + (number & 0x0F);
        if ((number >>> 4) > 9 || (number & 0x0F) > 9) {
            throw new IllegalArgumentException("invalid BCD active safety timestamp");
        }
        return parsed;
    }

    private static String sha256(byte[] value) {
        return sha256(value, 0, value.length);
    }

    private static String sha256(byte[] value, int offset, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(value, offset, length);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    public record DecodedAlarm(String module, long alarmId, int typeCode, String alarmType, String state, int level,
                               String terminalAlarmIdentifier, Instant occurredAt, BigDecimal longitude,
                               BigDecimal latitude, BigDecimal speedKph, int vehicleStatus,
                               int alarmSequenceNumber, int attachmentCount, String extensionPayloadDigest) { }
    public record ExtensionRejection(int itemId, String reasonCode) { }
}
