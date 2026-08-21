package com.idavy.drtops.domain.alarm;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface VehicleAlarmRepository extends JpaRepository<VehicleAlarm, UUID> {
    Optional<VehicleAlarm> findByDeduplicationKey(String deduplicationKey);
    Optional<VehicleAlarm> findByPublicId(UUID publicId);
    @Query("""
            select alarm from VehicleAlarm alarm
            where (:level is null or alarm.alarmLevel = :level)
              and (:status is null or alarm.processingStatus = :status)
              and (:vehicleId is null or alarm.vehicleId = :vehicleId)
              and (:module is null or alarm.module = :module)
              and (:hasAttachment is null
                   or (:hasAttachment = true and exists (
                       select 1 from VehicleAlarmAttachment attachment
                       where attachment.vehicleAlarmId = alarm.id))
                   or (:hasAttachment = false and not exists (
                       select 1 from VehicleAlarmAttachment attachment
                       where attachment.vehicleAlarmId = alarm.id)))
            """)
    List<VehicleAlarm> findForRead(
            @Param("level") Integer level,
            @Param("status") VehicleAlarm.ProcessingStatus status,
            @Param("vehicleId") UUID vehicleId,
            @Param("module") String module,
            @Param("hasAttachment") Boolean hasAttachment,
            Pageable pageable);
    Optional<VehicleAlarm> findFirstByTerminalIdAndVehicleIdAndStandardAndModuleAndAlarmTypeCodeAndTerminalAlarmIdAndEndedAtIsNull(
            UUID terminalId, UUID vehicleId, String standard, String module, int alarmTypeCode,
            long terminalAlarmId);
    Optional<VehicleAlarm> findFirstByTerminalIdAndVehicleIdAndStandardAndModuleAndAlarmTypeCodeAndTerminalAlarmId(
            UUID terminalId, UUID vehicleId, String standard, String module, int alarmTypeCode,
            long terminalAlarmId);
}
