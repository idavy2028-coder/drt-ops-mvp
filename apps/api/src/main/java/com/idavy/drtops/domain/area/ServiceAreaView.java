package com.idavy.drtops.domain.area;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ServiceAreaView(
        UUID id,
        String name,
        String boundaryWkt,
        String boundarySource,
        int boundaryVersion,
        OffsetDateTime publishedAt,
        OffsetDateTime updatedAt,
        String coordinateSystem) {

    static ServiceAreaView from(ServiceArea serviceArea) {
        return new ServiceAreaView(
                serviceArea.getId(),
                serviceArea.getName(),
                serviceArea.getBoundary(),
                serviceArea.getBoundarySource(),
                serviceArea.getBoundaryVersion(),
                serviceArea.getPublishedAt(),
                serviceArea.getUpdatedAt(),
                serviceArea.getCoordinateSystem());
    }
}
