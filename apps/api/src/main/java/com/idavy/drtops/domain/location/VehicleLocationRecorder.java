package com.idavy.drtops.domain.location;

import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class VehicleLocationRecorder {

    private static final String COORDINATE_SYSTEM = "GCJ02";

    private final VehicleLocationEventRepository eventRepository;
    private final VehicleRepository vehicleRepository;
    private final IdempotencyKeyLock idempotencyKeyLock;
    private final ServiceAreaLocationChecker serviceAreaLocationChecker;
    private final Clock clock;

    @Autowired
    public VehicleLocationRecorder(
            VehicleLocationEventRepository eventRepository,
            VehicleRepository vehicleRepository,
            IdempotencyKeyLock idempotencyKeyLock,
            ServiceAreaLocationChecker serviceAreaLocationChecker) {
        this(eventRepository, vehicleRepository, idempotencyKeyLock, serviceAreaLocationChecker, Clock.systemUTC());
    }

    VehicleLocationRecorder(
            VehicleLocationEventRepository eventRepository,
            VehicleRepository vehicleRepository,
            IdempotencyKeyLock idempotencyKeyLock,
            ServiceAreaLocationChecker serviceAreaLocationChecker,
            Clock clock) {
        this.eventRepository = eventRepository;
        this.vehicleRepository = vehicleRepository;
        this.idempotencyKeyLock = idempotencyKeyLock;
        this.serviceAreaLocationChecker = serviceAreaLocationChecker;
        this.clock = clock;
    }

    public Optional<LocationReportResult> findReplay(LocationReportCommand command) {
        String requestFingerprint = requestFingerprint(command);
        return eventRepository.findByIdempotencyKey(command.idempotencyKey())
                .map(event -> {
                    if (!event.getRequestFingerprint().equals(requestFingerprint)) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "幂等编号已用于不同的位置请求");
                    }
                    return new LocationReportResult(event, warnings(event), true);
                });
    }

    public LocationReportResult append(LocationReportCommand command) {
        idempotencyKeyLock.acquire(command.idempotencyKey());
        Optional<LocationReportResult> replay = findReplay(command);
        if (replay.isPresent()) {
            return replay.get();
        }

        validateReportedAt(command.driverReportedAt());
        validateCorrection(command);

        Vehicle vehicle = vehicleRepository.findByIdForLocationUpdate(command.vehicleId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "车辆不存在"));
        boolean snapshotApplied = vehicle.getCurrentLocationReportedAt() == null
                || !command.driverReportedAt().isBefore(vehicle.getCurrentLocationReportedAt());
        boolean outsideServiceArea = !serviceAreaLocationChecker.isInsideEnabledArea(
                command.longitude(), command.latitude());
        OffsetDateTime recordedAt = OffsetDateTime.now(clock);

        VehicleLocationEvent event = VehicleLocationEvent.record(
                command.vehicleId(),
                command.vehicleTaskId(),
                command.taskStopId(),
                command.virtualStopId(),
                command.eventType(),
                LocationSource.MANUAL_DISPATCHER,
                pointWkt(command),
                command.longitude(),
                command.latitude(),
                COORDINATE_SYSTEM,
                command.standardizedAddress(),
                command.driverReportedAt(),
                recordedAt,
                command.recordedBy(),
                command.note(),
                command.correctionReason(),
                command.correctsEventId(),
                command.idempotencyKey(),
                requestFingerprint(command),
                snapshotApplied,
                outsideServiceArea);
        eventRepository.save(event);
        return new LocationReportResult(event, warnings(event), false);
    }

    private void validateReportedAt(OffsetDateTime driverReportedAt) {
        if (driverReportedAt.isAfter(OffsetDateTime.now(clock))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "反馈时间不能晚于当前时间");
        }
    }

    private void validateCorrection(LocationReportCommand command) {
        if (command.eventType() != LocationEventType.MANUAL_CORRECTION) {
            if (command.correctsEventId() != null || command.correctionReason() != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "普通位置事件不能包含修正关联或原因");
            }
            return;
        }
        if (command.correctsEventId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "被修正的位置事件不能为空");
        }
        if (command.correctionReason() == null || command.correctionReason().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "修正原因不能为空");
        }
        VehicleLocationEvent correctedEvent = eventRepository.findById(command.correctsEventId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "被修正的位置事件不存在"));
        if (!correctedEvent.getVehicleId().equals(command.vehicleId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能修正其他车辆的位置事件");
        }
    }

    private static List<LocationWarning> warnings(VehicleLocationEvent event) {
        List<LocationWarning> warnings = new ArrayList<>(2);
        if (event.isOutsideServiceArea()) {
            warnings.add(LocationWarning.OUTSIDE_SERVICE_AREA);
        }
        if (!event.isSnapshotApplied()) {
            warnings.add(LocationWarning.HISTORICAL_EVENT_NOT_APPLIED_TO_SNAPSHOT);
        }
        return warnings;
    }

    private static String pointWkt(LocationReportCommand command) {
        return "POINT(" + command.longitude().toPlainString() + " " + command.latitude().toPlainString() + ")";
    }

    private static String requestFingerprint(LocationReportCommand command) {
        StringBuilder canonical = new StringBuilder();
        appendFingerprintPart(canonical, "vehicleId", command.vehicleId());
        appendFingerprintPart(canonical, "vehicleTaskId", command.vehicleTaskId());
        appendFingerprintPart(canonical, "taskStopId", command.taskStopId());
        appendFingerprintPart(canonical, "virtualStopId", command.virtualStopId());
        appendFingerprintPart(canonical, "eventType", command.eventType());
        appendFingerprintPart(canonical, "longitude", command.longitude());
        appendFingerprintPart(canonical, "latitude", command.latitude());
        appendFingerprintPart(canonical, "standardizedAddress", command.standardizedAddress());
        appendFingerprintPart(canonical, "driverReportedAt", command.driverReportedAt());
        appendFingerprintPart(canonical, "recordedBy", command.recordedBy());
        appendFingerprintPart(canonical, "note", command.note());
        appendFingerprintPart(canonical, "correctionReason", command.correctionReason());
        appendFingerprintPart(canonical, "correctsEventId", command.correctsEventId());

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void appendFingerprintPart(StringBuilder canonical, String fieldName, Object value) {
        canonical.append(fieldName).append('=');
        if (value == null) {
            canonical.append("null;");
            return;
        }
        String text = canonicalValue(value);
        canonical.append(text.length()).append(':').append(text).append(';');
    }

    private static String canonicalValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (value instanceof OffsetDateTime dateTime) {
            return dateTime.toInstant().toString();
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        return value.toString();
    }
}
