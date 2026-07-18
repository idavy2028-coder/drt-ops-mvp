package com.idavy.drtops.domain.map;

import java.util.List;

public record RoutePlanResult(
        int distanceMeters,
        int durationSeconds,
        List<Coordinate> pathCoordinates) {

    public RoutePlanResult {
        pathCoordinates = List.copyOf(pathCoordinates);
    }
}
