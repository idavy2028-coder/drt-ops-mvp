package com.idavy.drtops.domain.location;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import com.idavy.drtops.domain.terminal.JtGatewayAuditEvent;
import com.idavy.drtops.domain.terminal.JtGatewayAuditEventRepository;
import com.idavy.drtops.domain.terminal.JtTerminalVehicleBinding;
import com.idavy.drtops.domain.terminal.JtTerminalVehicleBindingRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class GpsLocationIngressService {
    private final ObjectMapper mapper; private final CoordinateTransformer transformer; private final LocationQualityEvaluator evaluator;
    private final JtTerminalVehicleBindingRepository bindingRepository; private final VehicleRepository vehicleRepository;
    private final VehicleLocationEventRepository eventRepository; private final ServiceAreaLocationChecker areaChecker;
    private final JtGatewayAuditEventRepository auditRepository;
    private final JtGatewayIngressReceiptRepository receiptRepository;
    private final JtGatewayIngressReceiptClaimer receiptClaimer;
    private final TransactionTemplate itemTransaction;
    public GpsLocationIngressService(ObjectMapper mapper, CoordinateTransformer transformer, LocationQualityEvaluator evaluator,
            JtTerminalVehicleBindingRepository bindingRepository, VehicleRepository vehicleRepository,
            VehicleLocationEventRepository eventRepository, ServiceAreaLocationChecker areaChecker, JtGatewayAuditEventRepository auditRepository,
            JtGatewayIngressReceiptRepository receiptRepository, JtGatewayIngressReceiptClaimer receiptClaimer,
            PlatformTransactionManager transactionManager) {
        this.mapper=mapper; this.transformer=transformer; this.evaluator=evaluator; this.bindingRepository=bindingRepository;
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
    private Result ingestClaimed(GatewayIngressEnvelope envelope) {
        if (envelope == null) {
            return rejectAudit(null, null, "INVALID_PAYLOAD");
        }
        if (envelope.idempotencyKey() == null) {
            return ingestOne(envelope);
        }
        if (receiptClaimer.claim(envelope.idempotencyKey()) == 0) {
            JtGatewayIngressReceipt receipt = receiptRepository.findById(envelope.idempotencyKey()).orElseThrow();
            return new Result(envelope.idempotencyKey(), "REPLAYED", receipt.getReasonCodes());
        }
        Result result = ingestOne(envelope);
        JtGatewayIngressReceipt receipt = receiptRepository.findById(envelope.idempotencyKey()).orElseThrow();
        receipt.complete(result.status(), result.reasonCodes(), OffsetDateTime.now(ZoneOffset.UTC));
        return result;
    }
    private Result ingestOne(GatewayIngressEnvelope envelope) {
        if (envelope.schemaVersion()!=1 || !"POSITION".equals(envelope.kind()) || envelope.idempotencyKey()==null) return rejectAudit(envelope, null, "UNSUPPORTED_ENVELOPE");
        final CanonicalPositionIngress ingress;
        try { ingress = mapper.readValue(envelope.payloadJson(), CanonicalPositionIngress.class); }
        catch (Exception malformed) { return rejectAudit(envelope, null, "INVALID_PAYLOAD"); }
        try {
            if (!valid(ingress, envelope)) return rejectAudit(envelope, ingress, "INVALID_PAYLOAD");
            JtTerminalVehicleBinding binding = bindingRepository.findByTerminalIdAndStatus(ingress.terminalId(), JtTerminalVehicleBinding.Status.ACTIVE).orElse(null);
            if (binding == null || !binding.getVehicleId().equals(ingress.vehicleId())) return rejectAudit(envelope, ingress, "TERMINAL_BINDING_MISMATCH");
            Vehicle vehicle = vehicleRepository.findByIdForLocationUpdate(binding.getVehicleId()).orElse(null);
            if (vehicle == null) return rejectAudit(envelope, ingress, "TERMINAL_BINDING_MISMATCH");
            LocationQualityDecision rawDecision = evaluate(ingress, envelope, vehicle, ingress.rawLongitude(), ingress.rawLatitude(), true, null, 0);
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
                int consecutive = consecutiveQuarantines(binding.getVehicleId()) + 1;
                decision = evaluate(ingress, envelope, vehicle,
                        coordinate.longitude(), coordinate.latitude(), inside, impliedSpeed, consecutive);
            }
            if (!decision.persistEvent()) return rejectAudit(envelope, ingress, decision.reasons());
            VehicleLocationEvent event = VehicleLocationEvent.recordGps(binding.getVehicleId(), ingress.terminalId(), ingress, coordinate, decision,
                    envelope.idempotencyKey(), ingress.payloadDigest(), OffsetDateTime.now(ZoneOffset.UTC), envelope.gatewayReceivedAt(), !inside);
            eventRepository.save(event); if (decision.applySnapshot()) vehicle.applyGpsLocationSnapshot(event);
            return new Result(envelope.idempotencyKey(), "ACCEPTED", decision.reasons().stream().map(Enum::name).sorted().toList());
        } catch (Exception exception) { throw exception; }
    }
    private Result rejectAudit(GatewayIngressEnvelope envelope, CanonicalPositionIngress ingress, String reason) {
        auditRepository.save(JtGatewayAuditEvent.record(ingress == null ? null : ingress.terminalId(), ingress == null ? null : ingress.vehicleId(), JtGatewayAuditEvent.EventType.PROTOCOL_REJECTED,
                JtGatewayAuditEvent.Result.REJECTED, reason, ingress == null ? null : ingress.protocolVersion(), null, validDigest(ingress == null ? null : ingress.payloadDigest()), null,
                OffsetDateTime.now(ZoneOffset.UTC), "JT_GATEWAY_SERVICE"));
        return Result.rejected(envelope == null ? null : envelope.idempotencyKey(), reason);
    }
    private Result rejectAudit(GatewayIngressEnvelope envelope, CanonicalPositionIngress ingress, Set<LocationQualityReason> reasons) {
        List<String> reasonCodes = reasons.stream().map(Enum::name).sorted().toList();
        auditRepository.save(JtGatewayAuditEvent.record(ingress.terminalId(), ingress.vehicleId(), JtGatewayAuditEvent.EventType.PROTOCOL_REJECTED,
                JtGatewayAuditEvent.Result.REJECTED, reasonCodes.getFirst(), ingress.protocolVersion(), null, validDigest(ingress.payloadDigest()), null,
                OffsetDateTime.now(ZoneOffset.UTC), "JT_GATEWAY_SERVICE"));
        return new Result(envelope.idempotencyKey(), "REJECTED", reasonCodes);
    }
    private static boolean valid(CanonicalPositionIngress ingress, GatewayIngressEnvelope envelope) {
        return envelope.gatewayReceivedAt() != null
                && ingress.protocolVersion() != null && !ingress.protocolVersion().isBlank()
                && ingress.rawCoordinateSystem() != null
                && ("WGS84".equals(ingress.rawCoordinateSystem()) || "GCJ02".equals(ingress.rawCoordinateSystem()))
                && ingress.terminalLocatedAt() != null && ingress.gatewayReceivedAt() != null
                && ingress.alarmBits() != null && ingress.alarmBits() >= 0
                && ingress.statusBits() != null && ingress.statusBits() >= 0
                && ingress.speedKph() != null && ingress.speedKph().signum() >= 0
                && ingress.directionDegrees() != null && ingress.directionDegrees() >= 0 && ingress.directionDegrees() <= 359
                && ingress.altitudeMeters() != null
                && (ingress.satelliteCount() == null || ingress.satelliteCount() >= 0)
                && validDigest(ingress.payloadDigest()) != null;
    }
    private static String validDigest(String payloadDigest) {
        return payloadDigest != null && payloadDigest.matches("[0-9a-f]{64}") ? payloadDigest : null;
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
}
