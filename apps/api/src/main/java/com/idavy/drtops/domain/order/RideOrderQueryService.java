package com.idavy.drtops.domain.order;

import com.idavy.drtops.domain.location.LocationEventType;
import com.idavy.drtops.domain.location.VehicleLocationEventRepository;
import com.idavy.drtops.domain.task.TaskResourceCoordinator;
import com.idavy.drtops.domain.task.TaskStop;
import com.idavy.drtops.domain.task.TaskStatus;
import com.idavy.drtops.domain.task.VehicleTask;
import com.idavy.drtops.domain.task.VehicleTaskRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RideOrderQueryService {

    private final RideOrderRepository rideOrderRepository;
    private final VehicleTaskRepository vehicleTaskRepository;
    private final VehicleLocationEventRepository locationEventRepository;
    private final NoShowEligibilityPolicy noShowEligibilityPolicy = new NoShowEligibilityPolicy();
    private final Clock clock = Clock.systemUTC();

    public RideOrderQueryService(
            RideOrderRepository rideOrderRepository,
            VehicleTaskRepository vehicleTaskRepository,
            VehicleLocationEventRepository locationEventRepository) {
        this.rideOrderRepository = rideOrderRepository;
        this.vehicleTaskRepository = vehicleTaskRepository;
        this.locationEventRepository = locationEventRepository;
    }

    @Transactional(readOnly = true)
    public List<RideOrderView> list() {
        return rideOrderRepository.findAll().stream()
                .map(order -> RideOrderView.from(order, eligibility(order)))
                .toList();
    }

    private NoShowEligibility eligibility(RideOrder order) {
        if (order.getStatus() != OrderStatus.IN_PROGRESS) {
            return noShowEligibilityPolicy.evaluate(
                    order.getStatus(),
                    TaskStatus.DISPATCHED,
                    "PLANNED",
                    order.getEstimatedBoardingAt(),
                    null,
                    false,
                    OffsetDateTime.now(clock));
        }
        List<VehicleTask> tasks = vehicleTaskRepository.findActiveByRideOrderId(
                order.getId(), TaskResourceCoordinator.ACTIVE_STATUSES);
        if (tasks.isEmpty()) {
            return NoShowEligibility.blocked(
                    null, 0, "NO_SHOW_TASK_NOT_IN_PROGRESS", "车辆任务尚未开始执行");
        }
        VehicleTask task = tasks.getFirst();
        TaskStop pickupStop = task.getStops().stream()
                .filter(stop -> order.getId().equals(stop.getRideOrderId()))
                .filter(stop -> "BOARDING".equals(stop.getStopType()))
                .findFirst()
                .orElse(null);
        if (pickupStop == null) {
            return NoShowEligibility.blocked(
                    null, 0, "NO_SHOW_PICKUP_NOT_ARRIVED", "车辆尚未到达上车点");
        }
        return noShowEligibilityPolicy.evaluate(
                order.getStatus(),
                task.getStatus(),
                pickupStop.getStatus(),
                order.getEstimatedBoardingAt(),
                pickupStop.getActualArrivalAt(),
                locationEventRepository.existsByVehicleTaskIdAndTaskStopIdAndEventType(
                        task.getId(), pickupStop.getId(), LocationEventType.PICKUP_ARRIVED),
                OffsetDateTime.now(clock));
    }
}
