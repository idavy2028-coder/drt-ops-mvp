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
