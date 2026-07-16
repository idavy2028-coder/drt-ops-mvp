package com.idavy.drtops.domain.location;

import java.math.BigDecimal;
import java.util.UUID;

public interface ServiceAreaLocationChecker {

    boolean isInsideEnabledArea(BigDecimal longitude, BigDecimal latitude);

    default PublishedAreaCheck checkPublishedArea(BigDecimal longitude, BigDecimal latitude) {
        return new PublishedAreaCheck(isInsideEnabledArea(longitude, latitude), null, null);
    }

    record PublishedAreaCheck(boolean inside, UUID serviceAreaId, Double distanceToBoundaryMeters) {
    }
}
