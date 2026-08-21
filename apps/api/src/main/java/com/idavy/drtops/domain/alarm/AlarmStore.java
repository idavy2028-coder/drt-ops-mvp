package com.idavy.drtops.domain.alarm;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for immutable alarm facts and their transactional outbox records. */
public interface AlarmStore {
    void lockTerminal(UUID terminalId);
    boolean matchesBindingAt(UUID terminalId, UUID vehicleId, Instant gatewayReceivedAt);
    Optional<LocationReference> findLocation(
            UUID positionIdempotencyKey, UUID terminalId, UUID vehicleId);
    boolean hasLocationDependency(UUID positionIdempotencyKey);
    Optional<VehicleAlarm> findByDeduplicationKey(String key);
    Optional<VehicleAlarm> findOpenStart(VehicleAlarmIngressService.AlarmFact fact);
    Optional<VehicleAlarm> findStart(VehicleAlarmIngressService.AlarmFact fact);
    VehicleAlarm save(VehicleAlarm alarm);
    void appendOutbox(VehicleAlarm alarm, String eventType);
    void end(VehicleAlarm alarm, Instant endedAt);

    record LocationReference(UUID eventId, String qualityStatus, String qualityReasons) { }
}
