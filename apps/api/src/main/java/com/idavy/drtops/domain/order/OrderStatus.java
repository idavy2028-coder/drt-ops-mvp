package com.idavy.drtops.domain.order;

public enum OrderStatus {
    PENDING_DISPATCH,
    UNSERVICEABLE,
    PENDING_MANUAL_REVIEW,
    CONFIRMED,
    CANCELLED,
    IN_PROGRESS,
    COMPLETED,
    EXCEPTION_CLOSED
}
