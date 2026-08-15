package com.idavy.drtops.domain.alarm;

import java.util.UUID;

/** Domain authorization boundary; Task 12 supplies the application RBAC adapter. */
public interface VehicleAlarmAuthorization {
    default boolean mayRead(UUID actorId) { return false; }
    default boolean mayContinueRead(UUID actorId) { return mayRead(actorId); }
    /** Catch-up replay must not trust an authorization snapshot from the opening request. */
    default boolean mayContinueReadPersisted(UUID actorId) { return mayContinueRead(actorId); }
    boolean mayHandle(UUID actorId);
    boolean mayReopen(UUID actorId);
}
