package com.idavy.drtops.domain.location;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import com.idavy.drtops.domain.onboard.OnboardDeviceMembership;
import com.idavy.drtops.domain.onboard.OnboardDeviceMembershipRepository;
import com.idavy.drtops.domain.onboard.OnboardDeviceRoleAssignment;
import com.idavy.drtops.domain.onboard.OnboardDeviceRoleAssignmentRepository;
import com.idavy.drtops.domain.onboard.OnboardSystem;
import com.idavy.drtops.domain.onboard.OnboardSystemRepository;
import com.idavy.drtops.domain.terminal.JtGatewayAuditEvent;
import com.idavy.drtops.domain.terminal.JtGatewayAuditEventRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class GpsLocationIngressService {
    // PostgreSQL timestamp.h stores microseconds from 2000-01-01 and accepts MIN_TIMESTAMP <= t < END_TIMESTAMP.
    private static final Instant POSTGRES_TIMESTAMPTZ_MIN = Instant.ofEpochSecond(-210_866_803_200L);
    private static final Instant POSTGRES_TIMESTAMPTZ_MAX = Instant.ofEpochSecond(9_224_318_016_000L)
            .minusNanos(1_000);
    private final ObjectMapper mapper; private final CoordinateTransformer transformer; private final LocationQualityEvaluator evaluator;
    private final OnboardSystemRepository onboardSystemRepository;
    private final OnboardDeviceMembershipRepository membershipRepository;
    private final OnboardDeviceRoleAssignmentRepository roleRepository;
    private final EntityManager entityManager;
    private final VehicleRepository vehicleRepository;
    private final VehicleLocationEventRepository eventRepository; private final ServiceAreaLocationChecker areaChecker;
    private final JtGatewayAuditEventRepository auditRepository;
    private final JtGatewayIngressReceiptRepository receiptRepository;
    private final JtGatewayIngressReceiptClaimer receiptClaimer;
    private final TransactionTemplate itemTransaction;
    public GpsLocationIngressService(ObjectMapper mapper, CoordinateTransformer transformer, LocationQualityEvaluator evaluator,
            OnboardSystemRepository onboardSystemRepository,
            OnboardDeviceMembershipRepository membershipRepository,
            OnboardDeviceRoleAssignmentRepository roleRepository,
            EntityManager entityManager,
            VehicleRepository vehicleRepository,
            VehicleLocationEventRepository eventRepository, ServiceAreaLocationChecker areaChecker, JtGatewayAuditEventRepository auditRepository,
            JtGatewayIngressReceiptRepository receiptRepository, JtGatewayIngressReceiptClaimer receiptClaimer,
            PlatformTransactionManager transactionManager) {
        this.mapper=mapper; this.transformer=transformer; this.evaluator=evaluator;
        this.onboardSystemRepository = onboardSystemRepository;
        this.membershipRepository = membershipRepository;
        this.roleRepository = roleRepository;
        this.entityManager = entityManager;
        this.vehicleRepository=vehicleRepository; this.eventRepository=eventRepository; this.areaChecker=areaChecker; this.auditRepository=auditRepository;
        this.receiptRepository = receiptRepository;
        this.receiptClaimer = receiptClaimer;
        this.itemTransaction = new TransactionTemplate(transactionManager);
        this.itemTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public List<Result> ingest(List<GatewayIngressEnvelope> batch) {
        List<Result> results = new ArrayList<>();
        for (GatewayIngressEnvelope envelope : batch) {
            results.add(itemTransaction.execute(status -> ingestClaimed(envelope)));
        }
        return results;
    }
    public Result reject(GatewayIngressEnvelope envelope, String reason) {
        return rejectInTransaction(envelope, reason);
    }
    public Result rejectStable(GatewayIngressEnvelope envelope, String reason) {
        return rejectInTransaction(envelope, reason);
    }
    private Result rejectInTransaction(GatewayIngressEnvelope envelope, String reason) {
        if (envelope == null || envelope.idempotencyKey() == null || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("rejected ingress item must be correlatable");
        }
        return itemTransaction.execute(status -> rejectClaimed(envelope, reason));
    }
    private Result rejectClaimed(GatewayIngressEnvelope envelope, String reason) {
        if (receiptClaimer.claim(envelope.idempotencyKey()) == 0) {
            JtGatewayIngressReceipt receipt = receiptRepository.findById(envelope.idempotencyKey()).orElseThrow();
            return repeatedResult(envelope.idempotencyKey(), receipt);
        }
        Result result = rejectAudit(envelope, null, reason);
        JtGatewayIngressReceipt receipt = receiptRepository.findById(envelope.idempotencyKey()).orElseThrow();
        receipt.complete("REJECTED", result.reasonCodes(), OffsetDateTime.now(ZoneOffset.UTC));
        return result;
    }
    private Result ingestClaimed(GatewayIngressEnvelope envelope) {
        if (envelope == null) {
            return rejectAudit(null, null, "INVALID_PAYLOAD");
        }
        if (envelope.idempotencyKey() == null) {
            return ingestOne(envelope);
        }
        if (receiptClaimer.claim(envelope.idempotencyKey()) == 0) {
            JtGatewayIngressReceipt receipt = receiptRepository.findById(envelope.idempotencyKey()).orElseThrow();
            return repeatedResult(envelope.idempotencyKey(), receipt);
        }
        Result result = ingestOne(envelope);
        JtGatewayIngressReceipt receipt = receiptRepository.findById(envelope.idempotencyKey()).orElseThrow();
        receipt.complete(result.status(), result.reasonCodes(), OffsetDateTime.now(ZoneOffset.UTC));
        return result;
    }
    private static Result repeatedResult(java.util.UUID idempotencyKey, JtGatewayIngressReceipt receipt) {
        return switch (receipt.getFinalStatus()) {
            case "ACCEPTED" -> new Result(idempotencyKey, "REPLAYED", receipt.getReasonCodes());
            case "REJECTED" -> new Result(idempotencyKey, "REJECTED", receipt.getReasonCodes());
            default -> throw new IllegalStateException("ingress receipt is not finalized");
        };
    }
    private Result ingestOne(GatewayIngressEnvelope envelope) {
        if (envelope.schemaVersion()!=1
                || !("POSITION".equals(envelope.kind()) || "LOCATION".equals(envelope.kind()))
                || envelope.idempotencyKey()==null) return rejectAudit(envelope, null, "UNSUPPORTED_ENVELOPE");
        if (envelope.payloadJson() == null) return rejectAudit(envelope, null, "INVALID_PAYLOAD");
        final CanonicalPositionIngress ingress;
        try { ingress = mapper.readValue(envelope.payloadJson(), CanonicalPositionIngress.class); }
        catch (JsonProcessingException malformed) { return rejectAudit(envelope, null, "INVALID_PAYLOAD"); }
        if (ingress == null) return rejectAudit(envelope, null, "INVALID_PAYLOAD");
        receiptRepository.findById(envelope.idempotencyKey())
                .ifPresent(receipt -> receipt.identify(
                        envelope.kind(), ingress.terminalId(), ingress.vehicleId()));
        if (!valid(ingress, envelope)) return rejectAudit(envelope, ingress, "INVALID_PAYLOAD");
        CompositeAuthority authority = lockAndValidateAuthority(ingress);
        if (authority == null) {
            return rejectAudit(envelope, ingress, "ONBOARD_PROVENANCE_MISMATCH");
        }
        Vehicle vehicle = vehicleRepository.findByIdForLocationUpdate(authority.vehicleId()).orElse(null);
        if (vehicle == null) return rejectAudit(envelope, ingress, "ONBOARD_PROVENANCE_MISMATCH");
        LocationQualityDecision rawDecision = evaluate(
                ingress, envelope, vehicle, ingress.rawLongitude(), ingress.rawLatitude(), true, null, 0);
        if (!rawDecision.persistEvent()) return rejectAudit(envelope, ingress, rawDecision.reasons());
        CoordinateTransformer.StandardizedCoordinate coordinate;
        try { coordinate = transformer.transform(ingress.rawLongitude(), ingress.rawLatitude(), ingress.rawCoordinateSystem()); }
        catch (IllegalArgumentException invalidCoordinate) {
            Set<LocationQualityReason> reasons = java.util.EnumSet.noneOf(LocationQualityReason.class);
            reasons.addAll(rawDecision.reasons());
            reasons.add(LocationQualityReason.INVALID_COORDINATE);
            return rejectAudit(envelope, ingress, reasons);
        }
        boolean inside = areaChecker.isInsideEnabledArea(coordinate.longitude(), coordinate.latitude());
        Double impliedSpeed = impliedSpeedKph(vehicle, coordinate, ingress);
        LocationQualityDecision decision = evaluate(ingress, envelope, vehicle,
                coordinate.longitude(), coordinate.latitude(), inside, impliedSpeed, 0);
        if (decision.status() == LocationQualityStatus.QUARANTINED) {
            int consecutive = consecutiveQuarantines(authority.vehicleId()) + 1;
            decision = evaluate(ingress, envelope, vehicle,
                    coordinate.longitude(), coordinate.latitude(), inside, impliedSpeed, consecutive);
        }
        if (!decision.persistEvent()) return rejectAudit(envelope, ingress, decision.reasons());
        VehicleLocationEvent event = VehicleLocationEvent.recordGps(authority.vehicleId(), ingress.terminalId(), ingress, coordinate, decision,
                envelope.idempotencyKey(), ingress.payloadDigest(), OffsetDateTime.now(ZoneOffset.UTC), envelope.gatewayReceivedAt(), !inside);
        eventRepository.save(event); if (decision.applySnapshot()) vehicle.applyGpsLocationSnapshot(event);
        return new Result(envelope.idempotencyKey(), "ACCEPTED", decision.reasons().stream().map(Enum::name).sorted().toList());
    }
    private Result rejectAudit(GatewayIngressEnvelope envelope, CanonicalPositionIngress ingress, String reason) {
        auditRepository.save(JtGatewayAuditEvent.record(gatewayAuditKey(envelope), null, null,
                JtGatewayAuditEvent.EventType.PROTOCOL_REJECTED,
                JtGatewayAuditEvent.Result.REJECTED, reason, validProtocolVersion(ingress == null ? null : ingress.protocolVersion()), null, validDigest(ingress == null ? null : ingress.payloadDigest()), null,
                OffsetDateTime.now(ZoneOffset.UTC), "JT_GATEWAY_SERVICE"));
        return Result.rejected(envelope == null ? null : envelope.idempotencyKey(), reason);
    }
    private Result rejectAudit(GatewayIngressEnvelope envelope, CanonicalPositionIngress ingress, Set<LocationQualityReason> reasons) {
        List<String> reasonCodes = reasons.stream().map(Enum::name).sorted().toList();
        auditRepository.save(JtGatewayAuditEvent.record(gatewayAuditKey(envelope), null, null,
                JtGatewayAuditEvent.EventType.PROTOCOL_REJECTED,
                JtGatewayAuditEvent.Result.REJECTED, reasonCodes.getFirst(), validProtocolVersion(ingress.protocolVersion()), null, validDigest(ingress.payloadDigest()), null,
                OffsetDateTime.now(ZoneOffset.UTC), "JT_GATEWAY_SERVICE"));
        return new Result(envelope.idempotencyKey(), "REJECTED", reasonCodes);
    }
    private static java.util.UUID gatewayAuditKey(GatewayIngressEnvelope envelope) {
        return envelope == null || envelope.idempotencyKey() == null
                ? java.util.UUID.randomUUID() : envelope.idempotencyKey();
    }
    private static boolean valid(CanonicalPositionIngress ingress, GatewayIngressEnvelope envelope) {
        return validPostgresTimestamp(envelope.gatewayReceivedAt())
                && ingress.terminalId() != null
                && ingress.onboardSystemId() != null
                && ingress.vehicleId() != null
                && ("LOCATION_PRIMARY".equals(ingress.sourceRole())
                        || "LOCATION_BACKUP".equals(ingress.sourceRole()))
                && ingress.messageSerialNo() >= 0 && ingress.messageSerialNo() <= 0xffff
                && validProtocolVersion(ingress.protocolVersion()) != null
                && fitsNumeric(ingress.rawLongitude(), 10, 7)
                && fitsNumeric(ingress.rawLatitude(), 10, 7)
                && ingress.rawCoordinateSystem() != null
                && ("WGS84".equals(ingress.rawCoordinateSystem()) || "GCJ02".equals(ingress.rawCoordinateSystem()))
                && validPostgresTimestamp(ingress.terminalLocatedAt())
                && validPostgresTimestamp(ingress.gatewayReceivedAt())
                && (ingress.alarmBits() == null || ingress.alarmBits() >= 0)
                && (ingress.statusBits() == null || ingress.statusBits() >= 0)
                && fitsNumeric(ingress.speedKph(), 6, 2) && ingress.speedKph().signum() >= 0
                && ingress.directionDegrees() != null && ingress.directionDegrees() >= 0 && ingress.directionDegrees() <= 359
                && ingress.altitudeMeters() != null
                && fitsNumeric(BigDecimal.valueOf(ingress.altitudeMeters()), 8, 2)
                && (ingress.satelliteCount() == null || ingress.satelliteCount() >= 0)
                && validDigest(ingress.payloadDigest()) != null;
    }

    private CompositeAuthority lockAndValidateAuthority(CanonicalPositionIngress ingress) {
        OnboardDeviceMembership discovered = membershipRepository
                .findActiveByTerminalId(ingress.terminalId())
                .orElse(null);
        if (discovered == null) {
            return null;
        }
        UUID discoveredSystemId = discovered.getOnboardSystemId();
        List<?> lockedSystemRows = entityManager.createNativeQuery("""
                        select id from onboard_systems
                        where id = :onboardSystemId
                        for update
                        """)
                .setParameter("onboardSystemId", discoveredSystemId)
                .getResultList();
        if (lockedSystemRows.size() != 1) {
            return null;
        }
        OnboardSystem system = onboardSystemRepository.findById(discoveredSystemId).orElse(null);
        if (system == null) {
            return null;
        }
        entityManager.refresh(system);

        List<?> lockedMembershipRows = entityManager.createNativeQuery("""
                        select id from onboard_device_memberships
                        where id = :membershipId
                          and onboard_system_id = :onboardSystemId
                          and terminal_id = :terminalId
                          and status = 'ACTIVE' and valid_to is null
                        for update
                        """)
                .setParameter("membershipId", discovered.getId())
                .setParameter("onboardSystemId", discoveredSystemId)
                .setParameter("terminalId", ingress.terminalId())
                .getResultList();
        if (lockedMembershipRows.size() != 1) {
            return null;
        }
        entityManager.refresh(discovered);

        entityManager.createNativeQuery("""
                        select id from onboard_device_role_assignments
                        where onboard_system_id = :onboardSystemId
                          and terminal_id = :terminalId
                          and status = 'ACTIVE' and valid_to is null
                        order by role, id
                        for update
                        """)
                .setParameter("onboardSystemId", discoveredSystemId)
                .setParameter("terminalId", ingress.terminalId())
                .getResultList();
        OnboardDeviceRoleAssignment.Role claimedRole =
                OnboardDeviceRoleAssignment.Role.valueOf(ingress.sourceRole());
        boolean exactRoleIsActive = roleRepository
                .findActiveByTerminalIdOrderByValidFromAsc(ingress.terminalId()).stream()
                .anyMatch(assignment -> assignment.getOnboardSystemId().equals(discoveredSystemId)
                        && assignment.getTerminalId().equals(ingress.terminalId())
                        && assignment.getStatus() == OnboardDeviceRoleAssignment.Status.ACTIVE
                        && assignment.getValidTo() == null
                        && assignment.getRole() == claimedRole);

        if (system.getStatus() != OnboardSystem.Status.ACTIVE
                || !system.getId().equals(ingress.onboardSystemId())
                || !system.getVehicleId().equals(ingress.vehicleId())
                || discovered.getStatus() != OnboardDeviceMembership.Status.ACTIVE
                || discovered.getValidTo() != null
                || !discovered.getOnboardSystemId().equals(system.getId())
                || !discovered.getTerminalId().equals(ingress.terminalId())
                || !exactRoleIsActive) {
            return null;
        }
        return new CompositeAuthority(system.getId(), system.getVehicleId());
    }
    private static boolean validPostgresTimestamp(Instant timestamp) {
        return timestamp != null
                && !timestamp.isBefore(POSTGRES_TIMESTAMPTZ_MIN)
                && !timestamp.isAfter(POSTGRES_TIMESTAMPTZ_MAX);
    }
    private static String validProtocolVersion(String protocolVersion) {
        return protocolVersion != null && !protocolVersion.isBlank() && protocolVersion.length() <= 40
                ? protocolVersion : null;
    }
    private static String validDigest(String payloadDigest) {
        return payloadDigest != null && payloadDigest.matches("[0-9a-f]{64}") ? payloadDigest : null;
    }
    private static boolean fitsNumeric(BigDecimal value, int precision, int scale) {
        if (value == null) return false;
        BigDecimal normalized = value.stripTrailingZeros();
        long fractionalDigits = Math.max((long) normalized.scale(), 0L);
        long integerDigits = Math.max((long) normalized.precision() - normalized.scale(), 0L);
        return fractionalDigits <= scale && integerDigits <= precision - scale;
    }
    private LocationQualityDecision evaluate(CanonicalPositionIngress ingress, GatewayIngressEnvelope envelope,
            Vehicle vehicle, java.math.BigDecimal longitude, java.math.BigDecimal latitude,
            boolean inside, Double impliedSpeed, int consecutive) {
        return evaluator.evaluate(new LocationQualityEvaluator.Input(longitude, latitude,
                ingress.terminalLocatedAt(), envelope.gatewayReceivedAt(), envelope.gatewayReceivedAt(),
                vehicle.getCurrentLocationReportedAt() == null ? null : vehicle.getCurrentLocationReportedAt().toInstant(),
                ingress.statusBits() == null ? 0 : ingress.statusBits(), ingress.speedKph(), ingress.satelliteCount(),
                inside, impliedSpeed, consecutive));
    }
    private int consecutiveQuarantines(java.util.UUID vehicleId) {
        int count = 0;
        for (VehicleLocationEvent event : eventRepository
                .findTop3ByVehicleIdAndGatewayReceivedAtIsNotNullOrderByGatewayReceivedAtDescIdDesc(vehicleId)) {
            if (event.getQualityStatus() != LocationQualityStatus.QUARANTINED) break;
            count++;
        }
        return count;
    }
    private static Double impliedSpeedKph(Vehicle vehicle, CoordinateTransformer.StandardizedCoordinate coordinate,
            CanonicalPositionIngress ingress) {
        if (vehicle.getCurrentLocation() == null || vehicle.getCurrentLocationReportedAt() == null) return null;
        long seconds = java.time.Duration.between(vehicle.getCurrentLocationReportedAt().toInstant(), ingress.terminalLocatedAt()).getSeconds();
        if (seconds <= 0) return null;
        org.locationtech.jts.geom.Point point = GeographyPoint.fromWkt(vehicle.getCurrentLocation());
        double lat1 = Math.toRadians(point.getY()), lat2 = Math.toRadians(coordinate.latitude().doubleValue());
        double dLat = lat2 - lat1, dLon = Math.toRadians(coordinate.longitude().doubleValue() - point.getX());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371d * 2d * Math.atan2(Math.sqrt(a), Math.sqrt(1d - a)) / seconds * 3600d;
    }
    public record Result(java.util.UUID idempotencyKey, String status, List<String> reasonCodes) {
        static Result rejected(java.util.UUID key, String reason) { return new Result(key, "REJECTED", List.of(reason)); }
    }

    private record CompositeAuthority(UUID onboardSystemId, UUID vehicleId) { }
}
