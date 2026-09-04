package com.idavy.drtops.domain.alarm;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for immutable alarm facts and their transactional outbox records. */
public interface AlarmStore {
    Optional<LocationReference> findLocation(
            UUID positionIdempotencyKey,
            UUID terminalId,
            UUID onboardSystemId,
            UUID vehicleId);
    ActiveSafetyAuthorization lockAndAuthorizeActiveSafety(
            VehicleAlarmIngressService.AlarmFact fact,
            LocationReference location);
    boolean hasLocationDependency(UUID positionIdempotencyKey);
    Optional<VehicleAlarm> findByDeduplicationKey(String key);
    Optional<VehicleAlarm> findOpenStart(VehicleAlarmIngressService.AlarmFact fact);
    boolean hasOpenStart(VehicleAlarmIngressService.AlarmFact fact);
    Optional<VehicleAlarm> findStart(VehicleAlarmIngressService.AlarmFact fact);
    VehicleAlarm save(VehicleAlarm alarm);
    void appendOutbox(VehicleAlarm alarm, String eventType);
    void end(VehicleAlarm alarm, Instant endedAt);

    record LocationReference(
            UUID eventId,
            UUID onboardSystemId,
            Instant recordedAt,
            String qualityStatus,
            String qualityReasons) { }

    record ActiveSafetyAuthorization(boolean authorized, String reasonCode) {
        static ActiveSafetyAuthorization allowed() {
            return new ActiveSafetyAuthorization(true, null);
        }

        static ActiveSafetyAuthorization rejected() {
            return new ActiveSafetyAuthorization(
                    false, "ACTIVE_SAFETY_AUTHORITY_MISMATCH");
        }
    }
}
