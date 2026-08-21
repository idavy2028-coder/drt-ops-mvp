package com.idavy.drtops.domain.alarm;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Test-only store; production wiring always uses JpaAlarmStore. */
final class InMemoryAlarmStore implements AlarmStore {
    private final List<VehicleAlarm> facts = new ArrayList<>();
    private final List<OutboxRecord> outbox = new ArrayList<>();
    private final java.util.Map<UUID, AlarmStore.LocationReference> positions = new java.util.HashMap<>();
    private boolean bindingsAccepted = true;
    private Instant historicalBindingValidUntil;

    @Override public void lockTerminal(UUID terminalId) { }
    @Override public boolean matchesBindingAt(UUID terminalId, UUID vehicleId, Instant gatewayReceivedAt) {
        return bindingsAccepted || historicalBindingValidUntil != null
                && !gatewayReceivedAt.isAfter(historicalBindingValidUntil);
    }
    @Override public Optional<AlarmStore.LocationReference> findLocation(UUID positionIdempotencyKey) {
        return Optional.ofNullable(positions.get(positionIdempotencyKey));
    }
    @Override public Optional<VehicleAlarm> findByDeduplicationKey(String key) {
        return facts.stream().filter(fact -> fact.getDeduplicationKey().equals(key)).findFirst();
    }
    @Override public Optional<VehicleAlarm> findOpenStart(VehicleAlarmIngressService.AlarmFact fact) {
        return facts.stream().filter(value -> value.getTerminalId().equals(fact.terminalId())
                && value.getVehicleId().equals(fact.vehicleId())
                && value.getStandard().equals(fact.standard()) && value.getModule().equals(fact.module())
                && value.getAlarmTypeCode() == fact.typeCode()
                && value.getTerminalAlarmId() == fact.terminalAlarmId()
                && value.getEndedAt() == null).findFirst();
    }
    @Override public VehicleAlarm save(VehicleAlarm alarm) { if (!facts.contains(alarm)) facts.add(alarm); return alarm; }
    @Override public void appendOutbox(VehicleAlarm alarm, String eventType) { outbox.add(new OutboxRecord(alarm.getId(), eventType)); }
    @Override public void end(VehicleAlarm alarm, Instant endedAt) { alarm.endAt(endedAt); }
    List<VehicleAlarm> facts() { return List.copyOf(facts); }
    List<OutboxRecord> outbox() { return List.copyOf(outbox); }
    void position(UUID key, UUID eventId, String qualityStatus) {
        position(key, eventId, qualityStatus, "[]");
    }
    void position(UUID key, UUID eventId, String qualityStatus, String qualityReasons) {
        positions.put(key, new AlarmStore.LocationReference(eventId, qualityStatus, qualityReasons));
    }
    void rejectedPosition(UUID key) {
        positions.put(key, new AlarmStore.LocationReference(null, "REJECTED", "[\"INVALID_COORDINATE\"]"));
    }
    void rejectBindings() { bindingsAccepted = false; }
    void acceptHistoricalBindingUntil(Instant validUntil) {
        bindingsAccepted = false;
        historicalBindingValidUntil = validUntil;
    }
    record OutboxRecord(UUID alarmId, String eventType) { }
}
