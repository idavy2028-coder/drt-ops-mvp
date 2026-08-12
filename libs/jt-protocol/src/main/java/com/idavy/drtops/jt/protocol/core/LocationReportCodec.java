package com.idavy.drtops.jt.protocol.core;

import com.idavy.drtops.jt.protocol.codec.Jt808CodecException;
import com.idavy.drtops.jt.protocol.codec.Jt808DecodeError;
import com.idavy.drtops.jt.protocol.codec.Jt808MessageCodec;
import com.idavy.drtops.jt.protocol.codec.Jt808MessageHeader;
import io.netty.buffer.ByteBuf;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** Codec for the JT/T 808 public location-report body (0x0200). */
public final class LocationReportCodec implements Jt808MessageCodec<LocationReport> {
    private static final int MESSAGE_ID = 0x0200;
    private static final int BASE_BODY_LENGTH = 28;
    private static final int SOUTH_LATITUDE_BIT = 1 << 2;
    private static final int WEST_LONGITUDE_BIT = 1 << 3;
    private static final int SATELLITE_COUNT_ITEM_ID = 0x31;
    private static final ZoneOffset TERMINAL_TIME_OFFSET = ZoneOffset.ofHours(8);

    @Override
    public int messageId() {
        return MESSAGE_ID;
    }

    @Override
    public Class<LocationReport> payloadType() {
        return LocationReport.class;
    }

    @Override
    public LocationReport decode(Jt808MessageHeader header, ByteBuf body) {
        if (header.messageId() != MESSAGE_ID || header.bodyLength() != body.readableBytes()
                || body.readableBytes() < BASE_BODY_LENGTH) {
            throw new Jt808CodecException(Jt808DecodeError.LENGTH_MISMATCH);
        }
        ByteBuf input = body.duplicate();
        int alarmBits = input.readInt();
        int statusBits = input.readInt();
        BigDecimal latitude = coordinate(input.readUnsignedInt(), (statusBits & SOUTH_LATITUDE_BIT) != 0);
        BigDecimal longitude = coordinate(input.readUnsignedInt(), (statusBits & WEST_LONGITUDE_BIT) != 0);
        int altitudeMeters = input.readUnsignedShort();
        BigDecimal speedKph = BigDecimal.valueOf(input.readUnsignedShort(), 1);
        int directionDegrees = input.readUnsignedShort();
        Instant locatedAt = readTerminalTime(input);
        List<LocationReport.AdditionalItem> additionalItems = new ArrayList<>();
        Integer satelliteCount = null;
        while (input.isReadable()) {
            if (input.readableBytes() < 2) {
                throw new Jt808CodecException(Jt808DecodeError.LENGTH_MISMATCH);
            }
            int itemId = input.readUnsignedByte();
            int itemLength = input.readUnsignedByte();
            if (itemLength > input.readableBytes()) {
                throw new Jt808CodecException(Jt808DecodeError.LENGTH_MISMATCH);
            }
            byte[] value = new byte[itemLength];
            input.readBytes(value);
            if (itemId == SATELLITE_COUNT_ITEM_ID) {
                if (itemLength != 1) {
                    throw new Jt808CodecException(Jt808DecodeError.LENGTH_MISMATCH);
                }
                satelliteCount = Byte.toUnsignedInt(value[0]);
            }
            additionalItems.add(new LocationReport.AdditionalItem(itemId, value));
        }
        return new LocationReport(
                alarmBits, statusBits, longitude, latitude, altitudeMeters, speedKph,
                directionDegrees, locatedAt, satelliteCount, additionalItems);
    }

    @Override
    public void encode(LocationReport value, ByteBuf target) {
        EncodedLocation encoded = validateForWire(value);
        target.writeInt(value.alarmBits());
        int statusBits = coordinateStatus(value.statusBits(), value.latitude(), value.longitude());
        target.writeInt(statusBits);
        target.writeInt((int) encoded.latitudeRaw());
        target.writeInt((int) encoded.longitudeRaw());
        target.writeShort(encoded.altitudeMeters());
        target.writeShort(encoded.speedTenthsKph());
        target.writeShort(value.directionDegrees());
        writeTerminalTime(value.locatedAt(), target);
        value.additionalItems().forEach(item -> {
            byte[] rawValue = item.value();
            target.writeByte(item.id());
            target.writeByte(rawValue.length);
            target.writeBytes(rawValue);
        });
    }

    private static BigDecimal coordinate(long raw, boolean negative) {
        BigDecimal value = BigDecimal.valueOf(raw, 6);
        return negative ? value.negate() : value;
    }

    private static Instant readTerminalTime(ByteBuf input) {
        int year = 2000 + readBcd(input.readUnsignedByte());
        int month = readBcd(input.readUnsignedByte());
        int day = readBcd(input.readUnsignedByte());
        int hour = readBcd(input.readUnsignedByte());
        int minute = readBcd(input.readUnsignedByte());
        int second = readBcd(input.readUnsignedByte());
        try {
            return LocalDateTime.of(year, month, day, hour, minute, second).toInstant(TERMINAL_TIME_OFFSET);
        } catch (DateTimeException invalidDate) {
            throw new Jt808CodecException(Jt808DecodeError.INVALID_BCD);
        }
    }

    private static int readBcd(int value) {
        int high = (value >>> 4) & 0xf;
        int low = value & 0xf;
        if (high > 9 || low > 9) {
            throw new Jt808CodecException(Jt808DecodeError.INVALID_BCD);
        }
        return high * 10 + low;
    }

    private static int coordinateStatus(int original, BigDecimal latitude, BigDecimal longitude) {
        int status = original & ~(SOUTH_LATITUDE_BIT | WEST_LONGITUDE_BIT);
        if (latitude.signum() < 0) {
            status |= SOUTH_LATITUDE_BIT;
        }
        if (longitude.signum() < 0) {
            status |= WEST_LONGITUDE_BIT;
        }
        return status;
    }

    private static EncodedLocation validateForWire(LocationReport value) {
        try {
            long latitude = coordinateMagnitude(value.latitude());
            long longitude = coordinateMagnitude(value.longitude());
            int altitude = unsignedShort(value.altitudeMeters(), "altitudeMeters");
            int speedTenths = unsignedShort(
                    value.speedKph().movePointRight(1).setScale(0, RoundingMode.UNNECESSARY).intValueExact(),
                    "speedKph");
            if (value.directionDegrees() < 0 || value.directionDegrees() > 359) {
                throw new IllegalArgumentException("directionDegrees must be between 0 and 359");
            }
            if (value.locatedAt().getNano() != 0) {
                throw new IllegalArgumentException("locatedAt must have second precision");
            }
            LocalDateTime local = LocalDateTime.ofInstant(value.locatedAt(), TERMINAL_TIME_OFFSET);
            if (local.getYear() < 2000 || local.getYear() > 2099) {
                throw new IllegalArgumentException("locatedAt must be within 2000 through 2099 at UTC+08:00");
            }
            return new EncodedLocation(latitude, longitude, altitude, speedTenths);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("location report is not representable on the wire", overflow);
        }
    }

    private static long coordinateMagnitude(BigDecimal coordinate) {
        long raw = coordinate.abs().movePointRight(6).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        if (raw < 0 || raw > 0xffff_ffffL) {
            throw new IllegalArgumentException("coordinate magnitude must fit an unsigned int");
        }
        return raw;
    }

    private static int unsignedShort(int value, String name) {
        if (value < 0 || value > 0xffff) {
            throw new IllegalArgumentException(name + " must fit an unsigned short");
        }
        return value;
    }

    private static void writeTerminalTime(Instant locatedAt, ByteBuf target) {
        LocalDateTime local = LocalDateTime.ofInstant(locatedAt, TERMINAL_TIME_OFFSET);
        writeBcd(local.getYear() % 100, target);
        writeBcd(local.getMonthValue(), target);
        writeBcd(local.getDayOfMonth(), target);
        writeBcd(local.getHour(), target);
        writeBcd(local.getMinute(), target);
        writeBcd(local.getSecond(), target);
    }

    private static void writeBcd(int value, ByteBuf target) {
        if (value < 0 || value > 99) {
            throw new IllegalArgumentException("BCD value must be between 0 and 99");
        }
        target.writeByte(((value / 10) << 4) | (value % 10));
    }

    private record EncodedLocation(long latitudeRaw, long longitudeRaw, int altitudeMeters, int speedTenthsKph) {
    }
}
