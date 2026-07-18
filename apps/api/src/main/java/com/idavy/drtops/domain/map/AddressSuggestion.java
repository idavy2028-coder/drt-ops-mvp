package com.idavy.drtops.domain.map;

public record AddressSuggestion(
        String id,
        String name,
        String address,
        String district,
        Coordinate location) {
}
