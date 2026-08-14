package com.idavy.drtops.domain.alarm;

import java.util.UUID;

/** Domain authorization boundary; Task 12 supplies the application RBAC adapter. */
public interface VehicleAlarmAuthorization {
    boolean mayHandle(UUID actorId);
    boolean mayReopen(UUID actorId);
}
