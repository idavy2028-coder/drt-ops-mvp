package com.idavy.drtops.domain.order;

import com.idavy.drtops.domain.task.TaskStatus;
import java.time.Duration;
import java.time.OffsetDateTime;

public final class NoShowEligibilityPolicy {

    static final Duration WAITING_PERIOD = Duration.ofMinutes(5);

    public NoShowEligibility evaluate(
            OrderStatus orderStatus,
            TaskStatus taskStatus,
            String pickupStopStatus,
            OffsetDateTime estimatedBoardingAt,
            OffsetDateTime actualArrivalAt,
            boolean pickupArrivalEventExists,
            OffsetDateTime now) {
        if (isTerminal(orderStatus)) {
            return blocked("NO_SHOW_ORDER_ALREADY_TERMINAL", "订单已结束，不能标记乘客未到");
        }
        if (orderStatus != OrderStatus.IN_PROGRESS) {
            return blocked("NO_SHOW_ORDER_NOT_IN_PROGRESS", "订单尚未开始执行");
        }
        if (taskStatus != TaskStatus.IN_PROGRESS) {
            return blocked("NO_SHOW_TASK_NOT_IN_PROGRESS", "车辆任务尚未开始执行");
        }
        if ("BOARDED".equals(pickupStopStatus)) {
            return blocked("NO_SHOW_PASSENGER_ALREADY_BOARDED", "乘客已上车，不能标记乘客未到");
        }
        if (!"ARRIVED".equals(pickupStopStatus) || actualArrivalAt == null) {
            return blocked("NO_SHOW_PICKUP_NOT_ARRIVED", "车辆尚未到达上车点");
        }
        if (!pickupArrivalEventExists) {
            return blocked("NO_SHOW_PICKUP_EVENT_MISSING", "缺少上车点到站事件");
        }

        OffsetDateTime waitingStartedAt =
                actualArrivalAt.isAfter(estimatedBoardingAt) ? actualArrivalAt : estimatedBoardingAt;
        OffsetDateTime eligibleAt = waitingStartedAt.plus(WAITING_PERIOD);
        long waitedSeconds = Math.max(0, Duration.between(waitingStartedAt, now).getSeconds());
        if (now.isBefore(eligibleAt)) {
            return NoShowEligibility.blocked(
                    eligibleAt,
                    waitedSeconds,
                    "NO_SHOW_WAITING_PERIOD_NOT_ELAPSED",
                    "乘客等候期尚未结束");
        }
        return NoShowEligibility.allowed(eligibleAt, waitedSeconds);
    }

    private NoShowEligibility blocked(String code, String message) {
        return NoShowEligibility.blocked(null, 0, code, message);
    }

    private boolean isTerminal(OrderStatus status) {
        return status == OrderStatus.COMPLETED
                || status == OrderStatus.CANCELLED
                || status == OrderStatus.UNSERVICEABLE
                || status == OrderStatus.EXCEPTION_CLOSED;
    }
}
