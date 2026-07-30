package com.idavy.drtops.domain.order;

import java.time.OffsetDateTime;

public class NoShowConflictException extends RuntimeException {

    private final String code;
    private final OffsetDateTime eligibleAt;

    public NoShowConflictException(String code, String message, OffsetDateTime eligibleAt) {
        super(message);
        this.code = code;
        this.eligibleAt = eligibleAt;
    }

    public String getCode() {
        return code;
    }

    public OffsetDateTime getEligibleAt() {
        return eligibleAt;
    }
}
