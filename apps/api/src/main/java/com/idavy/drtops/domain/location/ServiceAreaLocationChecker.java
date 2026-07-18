package com.idavy.drtops.domain.location;

import java.math.BigDecimal;
import java.util.UUID;

public interface ServiceAreaLocationChecker {

    boolean isInsideEnabledArea(BigDecimal longitude, BigDecimal latitude);

    default PublishedAreaCheck checkPublishedArea(BigDecimal longitude, BigDecimal latitude) {
        return new PublishedAreaCheck(isInsideEnabledArea(longitude, latitude), null, null);
    }

    default PublishedAreaCheck checkPublishedArea(
            UUID serviceAreaId, BigDecimal longitude, BigDecimal latitude) {
        PublishedAreaCheck check = checkPublishedArea(longitude, latitude);
        return serviceAreaId.equals(check.serviceAreaId()) ? check : new PublishedAreaCheck(false, null, null);
    }

    record PublishedAreaCheck(boolean inside, UUID serviceAreaId, Double distanceToBoundaryMeters) {
    }
}
