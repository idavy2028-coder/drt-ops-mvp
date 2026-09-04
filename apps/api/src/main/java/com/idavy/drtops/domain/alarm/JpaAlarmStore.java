package com.idavy.drtops.domain.alarm;

import com.idavy.drtops.domain.location.JtGatewayIngressReceiptRepository;
import com.idavy.drtops.domain.location.LocationSource;
import com.idavy.drtops.domain.location.VehicleLocationEvent;
import com.idavy.drtops.domain.location.VehicleLocationEventRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
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
    @Override public Optional<LocationReference> findLocation(
            UUID positionIdempotencyKey,
            UUID terminalId,
            UUID onboardSystemId,
            UUID vehicleId) {
        if (receipts.findById(positionIdempotencyKey)
                .filter(receipt -> receipt.isAcceptedLocationFor(terminalId, vehicleId))
                .isEmpty()) {
            return Optional.empty();
        }
        return locations.findByIdempotencyKey(positionIdempotencyKey)
                .filter(location -> location.getSource() == LocationSource.GPS_DEVICE
                        && terminalId.equals(location.getTerminalId())
                        && onboardSystemId.equals(location.getOnboardSystemId())
                        && vehicleId.equals(location.getVehicleId()))
                .map(event -> new LocationReference(
                        event.getId(), event.getOnboardSystemId(),
                        event.getRecordedAt().toInstant(),
                        event.getQualityStatus().name(), event.getQualityReasons()));
    }
    @Override public ActiveSafetyAuthorization lockAndAuthorizeActiveSafety(
            VehicleAlarmIngressService.AlarmFact fact,
            LocationReference location) {
        if (!Objects.equals(fact.onboardSystemId(), location.onboardSystemId())) {
            return ActiveSafetyAuthorization.rejected();
        }
        java.time.OffsetDateTime authorizedAt = location.recordedAt().atOffset(ZoneOffset.UTC);
        if (!locksExactlyOne("""
                select id from onboard_systems
                where id = ? and vehicle_id = ? and status = 'ACTIVE'
                for update
                """, fact.onboardSystemId(), fact.vehicleId())) {
            return ActiveSafetyAuthorization.rejected();
        }
        if (!locksExactlyOne("""
                select id from jt_terminals where id = ? for update
                """, fact.terminalId())) {
            return ActiveSafetyAuthorization.rejected();
        }
        if (!locksExactlyOne("""
                select id from onboard_device_memberships
                where onboard_system_id = ? and terminal_id = ?
                  and valid_from <= ? and (valid_to is null or ? < valid_to)
                order by id
                for update
                """, fact.onboardSystemId(), fact.terminalId(),
                authorizedAt, authorizedAt)) {
            return ActiveSafetyAuthorization.rejected();
        }
        if (!locksExactlyOne("""
                select id from onboard_device_role_assignments
                where onboard_system_id = ? and terminal_id = ?
                  and role = 'ACTIVE_SAFETY'
                  and valid_from <= ? and (valid_to is null or ? < valid_to)
                order by id
                for update
                """, fact.onboardSystemId(), fact.terminalId(),
                authorizedAt, authorizedAt)) {
            return ActiveSafetyAuthorization.rejected();
        }
        List<String> profiles = jdbc.query("""
                select safety_profile from onboard_device_protocol_profiles
                where terminal_id = ?
                  and valid_from <= ? and (valid_to is null or ? < valid_to)
                order by id
                for update
                """, (row, index) -> row.getString(1),
                fact.terminalId(), authorizedAt, authorizedAt);
        if (profiles.size() != 1
                || !matchesSafetyProfile(profiles.getFirst(), fact.standard())) {
            return ActiveSafetyAuthorization.rejected();
        }
        String requiredCapability = switch (fact.module()) {
            case "ADAS" -> "ADAS";
            case "DMS" -> "DMS";
            default -> null;
        };
        if (requiredCapability == null || !locksExactlyOne("""
                select id from onboard_device_capabilities
                where terminal_id = ? and capability = ? and verified_at <= ?
                  and (status = 'VERIFIED'
                       or (status = 'DISABLED' and updated_at > ?))
                order by id
                for update
                """, fact.terminalId(), requiredCapability,
                authorizedAt, authorizedAt)) {
            return ActiveSafetyAuthorization.rejected();
        }
        return ActiveSafetyAuthorization.allowed();
    }
    @Override public boolean hasLocationDependency(UUID positionIdempotencyKey) {
        return locations.findByIdempotencyKey(positionIdempotencyKey).isPresent()
                || receipts.findById(positionIdempotencyKey)
                        .filter(receipt -> "REJECTED".equals(receipt.getFinalStatus()))
                        .isPresent();
    }
    @Override public Optional<VehicleAlarm> findByDeduplicationKey(String key) { return alarms.findByDeduplicationKey(key); }
    @Override public Optional<VehicleAlarm> findOpenStart(VehicleAlarmIngressService.AlarmFact fact) {
        return alarms.findFirstByOnboardSystemIdAndTerminalIdAndVehicleIdAndStandardAndModuleAndAlarmTypeCodeAndTerminalAlarmIdAndEndedAtIsNull(
                fact.onboardSystemId(), fact.terminalId(), fact.vehicleId(), fact.standard(),
                fact.module(), fact.typeCode(), fact.terminalAlarmId());
    }
    @Override public boolean hasOpenStart(VehicleAlarmIngressService.AlarmFact fact) {
        return alarms.existsByTerminalIdAndVehicleIdAndStandardAndModuleAndAlarmTypeCodeAndTerminalAlarmIdAndEndedAtIsNull(
                fact.terminalId(), fact.vehicleId(), fact.standard(), fact.module(),
                fact.typeCode(), fact.terminalAlarmId());
    }
    @Override public Optional<VehicleAlarm> findStart(VehicleAlarmIngressService.AlarmFact fact) {
        return alarms.findFirstByOnboardSystemIdAndTerminalIdAndVehicleIdAndStandardAndModuleAndAlarmTypeCodeAndTerminalAlarmIdOrderByOccurredAtDesc(
                fact.onboardSystemId(), fact.terminalId(), fact.vehicleId(), fact.standard(),
                fact.module(), fact.typeCode(), fact.terminalAlarmId());
    }
    @Override public VehicleAlarm save(VehicleAlarm alarm) { return alarms.save(alarm); }
    @Override public void appendOutbox(VehicleAlarm alarm, String eventType) { outbox.save(VehicleAlarmOutboxEvent.pending(alarm, eventType)); }
    @Override public void end(VehicleAlarm alarm, Instant endedAt) { alarm.endAt(endedAt); alarms.save(alarm); }

    private boolean locksExactlyOne(String sql, Object... arguments) {
        return jdbc.query(sql, (row, index) -> row.getObject(1, UUID.class), arguments)
                .size() == 1;
    }

    private static boolean matchesSafetyProfile(String safetyProfile, String standard) {
        return switch (safetyProfile) {
            case "JSATL12_2017" -> "T/JSATL12-2017".equals(standard);
            case "GBT28787_2023" -> "GB/T 28787-2023".equals(standard);
            default -> false;
        };
    }
}
