package com.idavy.drtops.domain.order;

import java.time.OffsetDateTime;

public record NoShowEligibility(
        boolean eligible,
        OffsetDateTime eligibleAt,
        long waitedSeconds,
        String reasonCode,
        String reasonMessage) {

    static NoShowEligibility blocked(
            OffsetDateTime eligibleAt,
            long waitedSeconds,
            String reasonCode,
            String reasonMessage) {
        return new NoShowEligibility(false, eligibleAt, waitedSeconds, reasonCode, reasonMessage);
    }

    static NoShowEligibility allowed(OffsetDateTime eligibleAt, long waitedSeconds) {
        return new NoShowEligibility(true, eligibleAt, waitedSeconds, null, null);
    }
}
