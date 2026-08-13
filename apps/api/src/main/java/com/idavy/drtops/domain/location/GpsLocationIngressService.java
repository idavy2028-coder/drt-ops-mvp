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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GpsLocationIngressService {
    private final ObjectMapper mapper; private final CoordinateTransformer transformer; private final LocationQualityEvaluator evaluator;
    private final JtTerminalVehicleBindingRepository bindingRepository; private final VehicleRepository vehicleRepository;
    private final VehicleLocationEventRepository eventRepository; private final ServiceAreaLocationChecker areaChecker;
    private final JtGatewayAuditEventRepository auditRepository;
    public GpsLocationIngressService(ObjectMapper mapper, CoordinateTransformer transformer, LocationQualityEvaluator evaluator,
            JtTerminalVehicleBindingRepository bindingRepository, VehicleRepository vehicleRepository,
            VehicleLocationEventRepository eventRepository, ServiceAreaLocationChecker areaChecker, JtGatewayAuditEventRepository auditRepository) {
        this.mapper=mapper; this.transformer=transformer; this.evaluator=evaluator; this.bindingRepository=bindingRepository;
        this.vehicleRepository=vehicleRepository; this.eventRepository=eventRepository; this.areaChecker=areaChecker; this.auditRepository=auditRepository;
    }
    @Transactional
    public List<Result> ingest(List<GatewayIngressEnvelope> batch) {
        List<Result> results = new ArrayList<>();
        Set<java.util.UUID> processed = new HashSet<>();
        for (GatewayIngressEnvelope envelope : batch) {
            if (envelope.idempotencyKey() != null && !processed.add(envelope.idempotencyKey())) {
                results.add(new Result(envelope.idempotencyKey(), "REPLAYED", List.of()));
            } else {
                results.add(ingestOne(envelope));
            }
        }
        return results;
    }
    private Result ingestOne(GatewayIngressEnvelope envelope) {
        if (envelope.schemaVersion()!=1 || !"POSITION".equals(envelope.kind()) || envelope.idempotencyKey()==null) return Result.rejected(envelope.idempotencyKey(), "UNSUPPORTED_ENVELOPE");
        if (eventRepository.findByIdempotencyKey(envelope.idempotencyKey()).isPresent()) return new Result(envelope.idempotencyKey(), "REPLAYED", List.of());
        final CanonicalPositionIngress ingress;
        try { ingress = mapper.readValue(envelope.payloadJson(), CanonicalPositionIngress.class); }
        catch (Exception malformed) { return Result.rejected(envelope.idempotencyKey(), "INVALID_PAYLOAD"); }
        try {
            JtTerminalVehicleBinding binding = bindingRepository.findByTerminalIdAndStatus(ingress.terminalId(), JtTerminalVehicleBinding.Status.ACTIVE).orElse(null);
            if (binding == null || !binding.getVehicleId().equals(ingress.vehicleId())) return rejectAudit(envelope, ingress, "TERMINAL_BINDING_MISMATCH");
            CoordinateTransformer.StandardizedCoordinate coordinate;
            try { coordinate = transformer.transform(ingress.rawLongitude(), ingress.rawLatitude(), ingress.rawCoordinateSystem()); }
            catch (IllegalArgumentException invalidCoordinate) { return rejectAudit(envelope, ingress, "INVALID_COORDINATE"); }
            Vehicle vehicle = vehicleRepository.findByIdForLocationUpdate(binding.getVehicleId()).orElse(null);
            if (vehicle == null) return rejectAudit(envelope, ingress, "TERMINAL_BINDING_MISMATCH");
            boolean inside = areaChecker.isInsideEnabledArea(coordinate.longitude(), coordinate.latitude());
            Double impliedSpeed = impliedSpeedKph(vehicle, coordinate, ingress);
            LocationQualityDecision decision = evaluator.evaluate(new LocationQualityEvaluator.Input(coordinate.longitude(), coordinate.latitude(),
                    ingress.terminalLocatedAt(), envelope.gatewayReceivedAt(), envelope.gatewayReceivedAt(),
                    vehicle.getCurrentLocationReportedAt()==null?null:vehicle.getCurrentLocationReportedAt().toInstant(),
                    ingress.statusBits()==null?0:ingress.statusBits(), ingress.speedKph(), ingress.satelliteCount(), inside, impliedSpeed, 0));
            if (decision.status() == LocationQualityStatus.QUARANTINED) {
                int consecutive = consecutiveQuarantines(binding.getVehicleId()) + 1;
                decision = evaluator.evaluate(new LocationQualityEvaluator.Input(coordinate.longitude(), coordinate.latitude(),
                        ingress.terminalLocatedAt(), envelope.gatewayReceivedAt(), envelope.gatewayReceivedAt(),
                        vehicle.getCurrentLocationReportedAt()==null?null:vehicle.getCurrentLocationReportedAt().toInstant(),
                        ingress.statusBits()==null?0:ingress.statusBits(), ingress.speedKph(), ingress.satelliteCount(), inside, impliedSpeed, consecutive));
            }
            if (!decision.persistEvent()) return rejectAudit(envelope, ingress, decision.reasons());
            VehicleLocationEvent event = VehicleLocationEvent.recordGps(binding.getVehicleId(), ingress.terminalId(), ingress, coordinate, decision,
                    envelope.idempotencyKey(), ingress.payloadDigest(), OffsetDateTime.now(ZoneOffset.UTC), envelope.gatewayReceivedAt(), !inside);
            eventRepository.save(event); if (decision.applySnapshot()) vehicle.applyGpsLocationSnapshot(event);
            return new Result(envelope.idempotencyKey(), "ACCEPTED", decision.reasons().stream().map(Enum::name).sorted().toList());
        } catch (Exception exception) { throw exception; }
    }
    private Result rejectAudit(GatewayIngressEnvelope envelope, CanonicalPositionIngress ingress, String reason) {
        auditRepository.save(JtGatewayAuditEvent.record(ingress.terminalId(), ingress.vehicleId(), JtGatewayAuditEvent.EventType.PROTOCOL_REJECTED,
                JtGatewayAuditEvent.Result.REJECTED, reason, ingress.protocolVersion(), null, ingress.payloadDigest(), null,
                OffsetDateTime.now(ZoneOffset.UTC), "JT_GATEWAY_SERVICE")); return Result.rejected(envelope.idempotencyKey(), reason);
    }
    private Result rejectAudit(GatewayIngressEnvelope envelope, CanonicalPositionIngress ingress, Set<LocationQualityReason> reasons) {
        List<String> reasonCodes = reasons.stream().map(Enum::name).sorted().toList();
        auditRepository.save(JtGatewayAuditEvent.record(ingress.terminalId(), ingress.vehicleId(), JtGatewayAuditEvent.EventType.PROTOCOL_REJECTED,
                JtGatewayAuditEvent.Result.REJECTED, reasonCodes.getFirst(), ingress.protocolVersion(), null, ingress.payloadDigest(), null,
                OffsetDateTime.now(ZoneOffset.UTC), "JT_GATEWAY_SERVICE"));
        return new Result(envelope.idempotencyKey(), "REJECTED", reasonCodes);
    }
    private int consecutiveQuarantines(java.util.UUID vehicleId) {
        int count = 0;
        for (VehicleLocationEvent event : eventRepository.findTop3ByVehicleIdOrderByDriverReportedAtDesc(vehicleId)) {
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
