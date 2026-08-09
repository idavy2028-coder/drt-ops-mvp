package com.idavy.drtops.domain.map;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record Coordinate(
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal longitude,
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal latitude) {

    public static final String COORDINATE_SYSTEM = "GCJ-02";

    public Coordinate {
        if (longitude == null) {
            throw new IllegalArgumentException("经度不能为空");
        }
        if (latitude == null) {
            throw new IllegalArgumentException("纬度不能为空");
        }
        if (longitude.compareTo(new BigDecimal("-180")) < 0
                || longitude.compareTo(new BigDecimal("180")) > 0) {
            throw new IllegalArgumentException("经度必须在 -180 到 180 之间");
        }
        if (latitude.compareTo(new BigDecimal("-90")) < 0
                || latitude.compareTo(new BigDecimal("90")) > 0) {
            throw new IllegalArgumentException("纬度必须在 -90 到 90 之间");
        }
    }

    public Coordinate(String longitude, String latitude) {
        this(new BigDecimal(longitude), new BigDecimal(latitude));
    }

    @JsonProperty("coordinateSystem")
    public String coordinateSystem() {
        return COORDINATE_SYSTEM;
    }

    public String asAmapParameter() {
        return longitude.toPlainString() + "," + latitude.toPlainString();
    }
}
