package com.idavy.drtops.domain.alarm;

import com.idavy.drtops.domain.fleet.VehicleRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
public class VehicleAlarmQueryService {
    private final VehicleAlarmRepository alarms;
    private final VehicleAlarmAttachmentRepository attachments;
    private final VehicleRepository vehicles;
    private final VehicleAlarmAuthorization authorization;

    VehicleAlarmQueryService(
            VehicleAlarmRepository alarms,
            VehicleAlarmAttachmentRepository attachments,
            VehicleRepository vehicles,
            VehicleAlarmAuthorization authorization) {
        this.alarms = Objects.requireNonNull(alarms);
        this.attachments = Objects.requireNonNull(attachments);
        this.vehicles = Objects.requireNonNull(vehicles);
        this.authorization = Objects.requireNonNull(authorization);
    }

    public List<AlarmReadModel> list(UUID actorId, Filter filter) {
        requireRead(actorId);
        Filter normalized = filter == null ? Filter.empty() : filter.normalized();
        return alarms.findForRead(
                        normalized.level(), normalized.processingStatus(), normalized.vehicleId(), normalized.module(),
                        normalized.hasAttachment(), PageRequest.of(0, 100,
                                Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.asc("publicId"))))
                .stream()
                .map(this::readModel)
                .toList();
    }

    public AlarmReadModel get(UUID actorId, UUID publicId) {
        requireRead(actorId);
        return readAlarm(publicId).map(this::readModel)
                .orElseThrow(() -> new VehicleAlarmNotFoundException("vehicle alarm not found"));
    }

    UUID findInternalId(UUID actorId, UUID publicId) {
        requireRead(actorId);
        return readAlarm(publicId).map(VehicleAlarm::getId)
                .orElseThrow(() -> new VehicleAlarmNotFoundException("vehicle alarm not found"));
    }

    AlarmReadModel readModel(VehicleAlarm alarm) {
        return new AlarmReadModel(
                alarm.getPublicId(), alarm.getVehicleId(), alarm.getStandard(), alarm.getModule(),
                alarm.getAlarmTypeCode(), alarm.getAlarmTypeNameSnapshot(), alarm.getAlarmLevel(),
                alarm.getProcessingStatus().name(), alarm.getOccurredAt(), alarm.getEndedAt(),
                alarm.getLocationQualityStatus(), attachments.existsByVehicleAlarmId(alarm.getId()), alarm.getVersion(),
                vehicles.findById(alarm.getVehicleId()).map(vehicle -> vehicle.getPlateNumber()).orElse(null),
                alarm.getLongitude(), alarm.getLatitude(), alarm.getSpeedKph());
    }

    private void requireRead(UUID actorId) {
        if (!authorization.mayRead(actorId)) {
            throw new VehicleAlarmAuthorizationException("vehicle alarm read is forbidden");
        }
    }

    private java.util.Optional<VehicleAlarm> readAlarm(UUID publicId) {
        if (publicId == null) throw new IllegalArgumentException("publicId is required");
        return alarms.findByPublicId(publicId);
    }

    public record Filter(Integer level, String status, UUID vehicleId, String module, Boolean hasAttachment) {
        static Filter empty() { return new Filter(null, null, null, null, null); }

        Filter normalized() {
            if (level != null && (level < 0 || level > 255)) {
                throw new IllegalArgumentException("invalid alarm level");
            }
            VehicleAlarm.ProcessingStatus parsedStatus = status == null ? null : parseStatus(status);
            String parsedModule = module == null ? null : module.trim().toUpperCase(Locale.ROOT);
            if (parsedModule != null && !("ADAS".equals(parsedModule) || "DMS".equals(parsedModule))) {
                throw new IllegalArgumentException("invalid alarm module");
            }
            return new Filter(level, parsedStatus == null ? null : parsedStatus.name(), vehicleId, parsedModule, hasAttachment);
        }

        VehicleAlarm.ProcessingStatus processingStatus() {
            return status == null ? null : VehicleAlarm.ProcessingStatus.valueOf(status);
        }

        private static VehicleAlarm.ProcessingStatus parseStatus(String value) {
            try {
                return VehicleAlarm.ProcessingStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("invalid alarm status");
            }
        }
    }

    public record AlarmReadModel(
            UUID publicId,
            UUID vehicleId,
            String standard,
            String module,
            int alarmTypeCode,
            String alarmType,
            int level,
            String status,
            Instant occurredAt,
            Instant endedAt,
            String locationQualityStatus,
            boolean hasAttachment,
            long version,
            String plateNumber,
            BigDecimal longitude,
            BigDecimal latitude,
            BigDecimal speedKph) {
    }
}
