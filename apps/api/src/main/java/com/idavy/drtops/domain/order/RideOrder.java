package com.idavy.drtops.domain.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "ride_orders")
public class RideOrder {

    @Id
    private UUID id;

    @Column(nullable = false, length = 80)
    private String passengerName;

    @Column(nullable = false, length = 30)
    private String passengerPhone;

    @Column(nullable = false)
    private int passengerCount;

    @Column(nullable = false, length = 40)
    private String requestType;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal originLng;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal originLat;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal destinationLng;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal destinationLat;

    private UUID boardingStopId;

    private UUID alightingStopId;

    @Column(nullable = false)
    private OffsetDateTime requestedDepartureAt;

    private OffsetDateTime estimatedBoardingAt;

    private OffsetDateTime estimatedArrivalAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private OrderStatus status;

    @Column(length = 300)
    private String failureReason;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    protected RideOrder() {
    }

    private RideOrder(CreateOrderCommand command) {
        OffsetDateTime now = OffsetDateTime.now();
        this.id = UUID.randomUUID();
        this.passengerName = command.passengerName();
        this.passengerPhone = command.passengerPhone();
        this.passengerCount = command.passengerCount();
        this.requestType = command.requestType();
        this.originLng = command.originLng();
        this.originLat = command.originLat();
        this.destinationLng = command.destinationLng();
        this.destinationLat = command.destinationLat();
        this.boardingStopId = command.boardingStopId();
        this.alightingStopId = command.alightingStopId();
        this.requestedDepartureAt = command.requestedDepartureAt();
        this.status = OrderStatus.PENDING_DISPATCH;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static RideOrder pendingDispatch(CreateOrderCommand command) {
        if (command.passengerCount() <= 0) {
            throw new IllegalArgumentException("passengerCount must be positive");
        }
        return new RideOrder(command);
    }

    public void confirm(OrderPromise promise) {
        requireStatus(OrderStatus.PENDING_DISPATCH, OrderStatus.PENDING_MANUAL_REVIEW);
        this.estimatedBoardingAt = promise.estimatedBoardingAt();
        this.estimatedArrivalAt = promise.estimatedArrivalAt();
        changeStatus(OrderStatus.CONFIRMED);
    }

    public void markUnserviceable(String reason) {
        requireStatus(OrderStatus.PENDING_DISPATCH, OrderStatus.PENDING_MANUAL_REVIEW);
        this.failureReason = reason;
        changeStatus(OrderStatus.UNSERVICEABLE);
    }

    public void markPendingManualReview(String reason) {
        requireStatus(OrderStatus.PENDING_DISPATCH);
        this.failureReason = reason;
        changeStatus(OrderStatus.PENDING_MANUAL_REVIEW);
    }

    public void cancel(String reason) {
        if (isTerminal()) {
            throw new IllegalStateException("Cannot cancel order in status " + status);
        }
        this.failureReason = reason;
        changeStatus(OrderStatus.CANCELLED);
    }

    public void startExecution() {
        requireStatus(OrderStatus.CONFIRMED);
        changeStatus(OrderStatus.IN_PROGRESS);
    }

    public void complete() {
        requireStatus(OrderStatus.IN_PROGRESS);
        changeStatus(OrderStatus.COMPLETED);
    }

    public void closeException(String reason) {
        if (status == OrderStatus.COMPLETED) {
            throw new IllegalStateException("Cannot close completed order as exception");
        }
        this.failureReason = reason;
        changeStatus(OrderStatus.EXCEPTION_CLOSED);
    }

    public UUID getId() {
        return id;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public OffsetDateTime getEstimatedBoardingAt() {
        return estimatedBoardingAt;
    }

    public OffsetDateTime getEstimatedArrivalAt() {
        return estimatedArrivalAt;
    }

    private void requireStatus(OrderStatus... allowedStatuses) {
        for (OrderStatus allowedStatus : allowedStatuses) {
            if (status == allowedStatus) {
                return;
            }
        }
        throw new IllegalStateException("Order status " + status + " cannot perform this transition");
    }

    private boolean isTerminal() {
        return status == OrderStatus.UNSERVICEABLE
                || status == OrderStatus.CANCELLED
                || status == OrderStatus.COMPLETED
                || status == OrderStatus.EXCEPTION_CLOSED;
    }

    private void changeStatus(OrderStatus nextStatus) {
        this.status = nextStatus;
        this.updatedAt = OffsetDateTime.now();
    }

    public record CreateOrderCommand(
            String passengerName,
            String passengerPhone,
            int passengerCount,
            String requestType,
            BigDecimal originLng,
            BigDecimal originLat,
            BigDecimal destinationLng,
            BigDecimal destinationLat,
            UUID boardingStopId,
            UUID alightingStopId,
            OffsetDateTime requestedDepartureAt) {
    }

    public record OrderPromise(
            OffsetDateTime estimatedBoardingAt,
            OffsetDateTime estimatedArrivalAt) {
    }
}
