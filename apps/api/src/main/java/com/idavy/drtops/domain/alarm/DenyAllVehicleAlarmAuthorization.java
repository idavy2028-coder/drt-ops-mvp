package com.idavy.drtops.domain.alarm;

import java.util.UUID;

/** Safe default before the HTTP permission matrix is connected in Task 12. */
class DenyAllVehicleAlarmAuthorization implements VehicleAlarmAuthorization {
    @Override public boolean mayHandle(UUID actorId) { return false; }
    @Override public boolean mayReopen(UUID actorId) { return false; }
}
