package com.idavy.drtops.jt.protocol.core;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Public JT/T 808 0x0200 location body, before coordinate conversion or quality handling. */
public record LocationReport(
        int alarmBits,
        int statusBits,
        BigDecimal longitude,
        BigDecimal latitude,
        int altitudeMeters,
        BigDecimal speedKph,
        int directionDegrees,
        Instant locatedAt,
        Integer satelliteCount,
        List<AdditionalItem> additionalItems) {
    public LocationReport {
        Objects.requireNonNull(longitude, "longitude");
        Objects.requireNonNull(latitude, "latitude");
        Objects.requireNonNull(speedKph, "speedKph");
        Objects.requireNonNull(locatedAt, "locatedAt");
        Objects.requireNonNull(additionalItems, "additionalItems");
        additionalItems = List.copyOf(additionalItems);
    }

    /** Returns the final on-wire value of an item id without exposing internal byte arrays. */
    public byte[] additionalItemLastValue(int itemId) {
        byte[] value = null;
        for (AdditionalItem item : additionalItems) {
            if (item.id() == itemId) {
                value = item.value();
            }
        }
        return value;
    }

    /** One raw additional item; list order and duplicate ids are kept for protocol-equivalent encoding. */
    public record AdditionalItem(int id, byte[] value) {
        public AdditionalItem {
            if (id < 0 || id > 0xff) {
                throw new IllegalArgumentException("additional item id must be an unsigned byte");
            }
            value = Objects.requireNonNull(value, "value").clone();
            if (value.length > 0xff) {
                throw new IllegalArgumentException("additional item value must fit in one byte length");
            }
        }

        @Override
        public byte[] value() {
            return value.clone();
        }
    }
}
