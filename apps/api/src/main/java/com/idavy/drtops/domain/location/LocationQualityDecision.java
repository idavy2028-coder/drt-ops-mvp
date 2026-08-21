package com.idavy.drtops.domain.location;

import java.util.Set;

public record LocationQualityDecision(
        LocationQualityStatus status, Set<LocationQualityReason> reasons, boolean persistEvent, boolean applySnapshot) {
    public LocationQualityDecision { reasons = Set.copyOf(reasons); }
}
