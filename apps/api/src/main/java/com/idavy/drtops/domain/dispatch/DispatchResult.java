package com.idavy.drtops.domain.dispatch;

import java.util.UUID;

public record DispatchResult(
        UUID orderId,
        DispatchDecisionType decision,
        UUID dispatchDecisionId,
        UUID vehicleTaskId) {
}
