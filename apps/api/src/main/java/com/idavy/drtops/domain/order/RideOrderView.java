package com.idavy.drtops.domain.order;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record RideOrderView(
        UUID id,
        String passengerName,
        String passengerPhone,
        int passengerCount,
        String requestType,
        BigDecimal originLng,
        BigDecimal originLat,
        BigDecimal destinationLng,
        BigDecimal destinationLat,
        String originAddress,
        String destinationAddress,
        String coordinateSystem,
        String originAddressSource,
        String destinationAddressSource,
        UUID boardingStopId,
        UUID alightingStopId,
        OffsetDateTime requestedDepartureAt,
        OffsetDateTime estimatedBoardingAt,
        OffsetDateTime estimatedArrivalAt,
        OffsetDateTime createdAt,
        OrderStatus status,
        boolean canMarkNoShow,
        OffsetDateTime noShowEligibleAt,
        long noShowWaitedSeconds,
        String noShowBlockReason,
        DispatchFailureView dispatchFailure) {

    static RideOrderView from(RideOrder order, NoShowEligibility eligibility) {
        return from(order, eligibility, null);
    }

    static RideOrderView from(
            RideOrder order,
            NoShowEligibility eligibility,
            DispatchFailureView dispatchFailure) {
        return new RideOrderView(
                order.getId(),
                order.getPassengerName(),
                order.getPassengerPhone(),
                order.getPassengerCount(),
                order.getRequestType(),
                order.getOriginLng(),
                order.getOriginLat(),
                order.getDestinationLng(),
                order.getDestinationLat(),
                order.getOriginAddress(),
                order.getDestinationAddress(),
                order.getCoordinateSystem(),
                order.getOriginAddressSource(),
                order.getDestinationAddressSource(),
                order.getBoardingStopId(),
                order.getAlightingStopId(),
                order.getRequestedDepartureAt(),
                order.getEstimatedBoardingAt(),
                order.getEstimatedArrivalAt(),
                order.getCreatedAt(),
                order.getStatus(),
                eligibility.eligible(),
                eligibility.eligibleAt(),
                eligibility.waitedSeconds(),
                eligibility.reasonMessage(),
                dispatchFailure);
    }
}
