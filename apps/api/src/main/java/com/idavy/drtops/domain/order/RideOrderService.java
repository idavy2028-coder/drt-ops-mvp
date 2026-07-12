package com.idavy.drtops.domain.order;

import com.idavy.drtops.domain.area.VirtualStopMatcher;
import com.idavy.drtops.domain.area.VirtualStopMatcher.VirtualStopMatch;
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

    public RideOrderService(RideOrderRepository rideOrderRepository, VirtualStopMatcher virtualStopMatcher) {
        this.rideOrderRepository = rideOrderRepository;
        this.virtualStopMatcher = virtualStopMatcher;
    }

    public RideOrder create(CreateRideOrderRequest request) {
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
