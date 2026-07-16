package com.idavy.drtops.domain.order;

import com.idavy.drtops.domain.area.VirtualStopMatcher;
import com.idavy.drtops.domain.area.VirtualStopMatcher.VirtualStopMatch;
import com.idavy.drtops.domain.audit.AuditLog;
import com.idavy.drtops.domain.audit.AuditLogRepository;
import com.idavy.drtops.domain.location.ServiceAreaLocationChecker;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RideOrderService {

    private final RideOrderRepository rideOrderRepository;
    private final VirtualStopMatcher virtualStopMatcher;
    private final ServiceAreaLocationChecker serviceAreaLocationChecker;
    private final AuditLogRepository auditLogRepository;

    public RideOrderService(
            RideOrderRepository rideOrderRepository,
            VirtualStopMatcher virtualStopMatcher,
            ServiceAreaLocationChecker serviceAreaLocationChecker,
            AuditLogRepository auditLogRepository) {
        this.rideOrderRepository = rideOrderRepository;
        this.virtualStopMatcher = virtualStopMatcher;
        this.serviceAreaLocationChecker = serviceAreaLocationChecker;
        this.auditLogRepository = auditLogRepository;
    }

    public RideOrder create(java.util.UUID actorId, CreateRideOrderRequest request) {
        validatePublishedServiceArea("起点", request.originLng(), request.originLat(), actorId);
        validatePublishedServiceArea("终点", request.destinationLng(), request.destinationLat(), actorId);
        VirtualStopMatch match;
        try {
            match = virtualStopMatcher.matchStops(
                    request.originLng(),
                    request.originLat(),
                    request.destinationLng(),
                    request.destinationLat(),
                    request.requestedDepartureAt().toInstant());
        } catch (VirtualStopMatcher.NoMatchedStopException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }

        RideOrder order = RideOrder.pendingDispatch(new RideOrder.CreateOrderCommand(
                request.passengerName(),
                request.passengerPhone(),
                request.passengerCount(),
                request.requestType(),
                request.originLng(),
                request.originLat(),
                request.destinationLng(),
                request.destinationLat(),
                match.boardingStopId(),
                match.alightingStopId(),
                request.requestedDepartureAt()));
        return rideOrderRepository.save(order);
    }

    private void validatePublishedServiceArea(
            String endpoint, BigDecimal longitude, BigDecimal latitude, java.util.UUID actorId) {
        ServiceAreaLocationChecker.PublishedAreaCheck check = serviceAreaLocationChecker.checkPublishedArea(longitude, latitude);
        if (check.serviceAreaId() == null || check.inside()) {
            return;
        }
        String message = "订单" + endpoint + "不在已发布服务区内";
        auditLogRepository.save(AuditLog.record(
                "SERVICE_AREA",
                check.serviceAreaId(),
                "RIDE_ORDER_AREA_REJECTED",
                "USER",
                actorId.toString(),
                message,
                "{\"longitude\":\"" + longitude.toPlainString() + "\",\"latitude\":\""
                        + latitude.toPlainString() + "\",\"distanceToBoundaryMeters\":"
                        + check.distanceToBoundaryMeters() + "}"));
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public List<RideOrder> list() {
        return rideOrderRepository.findAll();
    }

    public record CreateRideOrderRequest(
            @NotBlank String passengerName,
            @NotBlank String passengerPhone,
            @NotNull @Positive Integer passengerCount,
            @NotBlank String requestType,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal originLng,
            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal originLat,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal destinationLng,
            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal destinationLat,
            @NotNull OffsetDateTime requestedDepartureAt) {
    }
}
