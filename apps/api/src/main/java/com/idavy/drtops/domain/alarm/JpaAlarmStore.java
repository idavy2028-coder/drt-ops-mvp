package com.idavy.drtops.domain.alarm;

import com.idavy.drtops.domain.location.JtGatewayIngressReceiptRepository;
import com.idavy.drtops.domain.location.LocationSource;
import com.idavy.drtops.domain.location.VehicleLocationEvent;
import com.idavy.drtops.domain.location.VehicleLocationEventRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JpaAlarmStore implements AlarmStore {
    private final VehicleAlarmRepository alarms;
    private final VehicleAlarmOutboxRepository outbox;
    private final VehicleLocationEventRepository locations;
    private final JtGatewayIngressReceiptRepository receipts;
    private final JdbcTemplate jdbc;
    JpaAlarmStore(
            VehicleAlarmRepository alarms,
            VehicleAlarmOutboxRepository outbox,
            VehicleLocationEventRepository locations,
            JtGatewayIngressReceiptRepository receipts,
            JdbcTemplate jdbc) {
        this.alarms = alarms;
        this.outbox = outbox;
        this.locations = locations;
        this.receipts = receipts;
        this.jdbc = jdbc;
    }
    @Override public void lockTerminal(UUID terminalId) {
        jdbc.queryForObject("select id from jt_terminals where id = ? for update", UUID.class, terminalId);
    }
    @Override public boolean matchesBindingAt(UUID terminalId, UUID vehicleId, Instant gatewayReceivedAt) {
        Integer matches = jdbc.queryForObject("""
                select count(*) from jt_terminal_vehicle_bindings
                where terminal_id = ? and vehicle_id = ? and valid_from <= ?
                  and (valid_to is null or valid_to > ?)
                """, Integer.class, terminalId, vehicleId,
                gatewayReceivedAt.atOffset(java.time.ZoneOffset.UTC),
                gatewayReceivedAt.atOffset(java.time.ZoneOffset.UTC));
        return matches != null && matches > 0;
    }
    @Override public Optional<LocationReference> findLocation(
            UUID positionIdempotencyKey, UUID terminalId, UUID vehicleId) {
        if (receipts.findById(positionIdempotencyKey)
                .filter(receipt -> receipt.isAcceptedLocationFor(terminalId, vehicleId))
                .isEmpty()) {
            return Optional.empty();
        }
        return locations.findByIdempotencyKey(positionIdempotencyKey)
                .filter(location -> location.getSource() == LocationSource.GPS_DEVICE
                        && terminalId.equals(location.getTerminalId())
                        && vehicleId.equals(location.getVehicleId()))
                .map(event -> new LocationReference(
                        event.getId(), event.getQualityStatus().name(), event.getQualityReasons()));
    }
    @Override public boolean hasLocationDependency(UUID positionIdempotencyKey) {
        return locations.findByIdempotencyKey(positionIdempotencyKey).isPresent()
                || receipts.findById(positionIdempotencyKey)
                        .filter(receipt -> "REJECTED".equals(receipt.getFinalStatus()))
                        .isPresent();
    }
    @Override public Optional<VehicleAlarm> findByDeduplicationKey(String key) { return alarms.findByDeduplicationKey(key); }
    @Override public Optional<VehicleAlarm> findOpenStart(VehicleAlarmIngressService.AlarmFact fact) {
        return alarms.findFirstByTerminalIdAndVehicleIdAndStandardAndModuleAndAlarmTypeCodeAndTerminalAlarmIdAndEndedAtIsNull(
                fact.terminalId(), fact.vehicleId(), fact.standard(), fact.module(), fact.typeCode(),
                fact.terminalAlarmId());
    }
    @Override public Optional<VehicleAlarm> findStart(VehicleAlarmIngressService.AlarmFact fact) {
        return alarms.findFirstByTerminalIdAndVehicleIdAndStandardAndModuleAndAlarmTypeCodeAndTerminalAlarmIdOrderByOccurredAtDesc(
                fact.terminalId(), fact.vehicleId(), fact.standard(), fact.module(), fact.typeCode(),
                fact.terminalAlarmId());
    }
    @Override public VehicleAlarm save(VehicleAlarm alarm) { return alarms.save(alarm); }
    @Override public void appendOutbox(VehicleAlarm alarm, String eventType) { outbox.save(VehicleAlarmOutboxEvent.pending(alarm, eventType)); }
    @Override public void end(VehicleAlarm alarm, Instant endedAt) { alarm.endAt(endedAt); alarms.save(alarm); }
}
