package com.idavy.drtops.domain.alarm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.domain.location.JtGatewayIngressReceiptRepository;
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
    private final ObjectMapper objectMapper;
    JpaAlarmStore(
            VehicleAlarmRepository alarms,
            VehicleAlarmOutboxRepository outbox,
            VehicleLocationEventRepository locations,
            JtGatewayIngressReceiptRepository receipts,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.alarms = alarms;
        this.outbox = outbox;
        this.locations = locations;
        this.receipts = receipts;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
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
    @Override public Optional<LocationReference> findLocation(UUID positionIdempotencyKey) {
        Optional<VehicleLocationEvent> event = locations.findByIdempotencyKey(positionIdempotencyKey);
        if (event.isPresent()) {
            return Optional.of(new LocationReference(
                    event.get().getId(), event.get().getQualityStatus().name(), event.get().getQualityReasons()));
        }
        return receipts.findById(positionIdempotencyKey)
                .filter(receipt -> "REJECTED".equals(receipt.getFinalStatus()))
                .map(receipt -> new LocationReference(null, "REJECTED", serializeReasons(receipt.getReasonCodes())));
    }
    private String serializeReasons(java.util.List<String> reasons) {
        try {
            return objectMapper.writeValueAsString(reasons);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize location quality reasons", exception);
        }
    }
    @Override public Optional<VehicleAlarm> findByDeduplicationKey(String key) { return alarms.findByDeduplicationKey(key); }
    @Override public Optional<VehicleAlarm> findOpenStart(VehicleAlarmIngressService.AlarmFact fact) {
        return alarms.findFirstByTerminalIdAndVehicleIdAndStandardAndModuleAndAlarmTypeCodeAndTerminalAlarmIdAndEndedAtIsNull(
                fact.terminalId(), fact.vehicleId(), fact.standard(), fact.module(), fact.typeCode(),
                fact.terminalAlarmId());
    }
    @Override public VehicleAlarm save(VehicleAlarm alarm) { return alarms.save(alarm); }
    @Override public void appendOutbox(UUID alarmId, String eventType) { outbox.save(VehicleAlarmOutboxEvent.pending(alarmId, eventType)); }
    @Override public void end(VehicleAlarm alarm, Instant endedAt) { alarm.endAt(endedAt); alarms.save(alarm); }
}
