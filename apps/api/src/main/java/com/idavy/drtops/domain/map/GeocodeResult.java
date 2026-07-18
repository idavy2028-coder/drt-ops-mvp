package com.idavy.drtops.domain.map;

public record GeocodeResult(
        String formattedAddress,
        String province,
        String city,
        String district,
        Coordinate location) {
}
