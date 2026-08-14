package com.idavy.drtops.domain.alarm;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface VehicleAlarmRepository extends JpaRepository<VehicleAlarm, UUID> {
    Optional<VehicleAlarm> findByDeduplicationKey(String deduplicationKey);
    Optional<VehicleAlarm> findFirstByTerminalIdAndVehicleIdAndStandardAndModuleAndAlarmTypeCodeAndTerminalAlarmIdAndEndedAtIsNull(
            UUID terminalId, UUID vehicleId, String standard, String module, int alarmTypeCode,
            long terminalAlarmId);
}
