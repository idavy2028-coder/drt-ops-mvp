package com.idavy.drtops.domain.task;

import com.idavy.drtops.domain.fleet.Driver;
import com.idavy.drtops.domain.fleet.DriverRepository;
import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TaskResourceCoordinator {

    public static final List<TaskStatus> ACTIVE_STATUSES = List.of(
            TaskStatus.PENDING_DEPARTURE,
            TaskStatus.DISPATCHED,
            TaskStatus.IN_PROGRESS,
            TaskStatus.PAUSED);

    private final VehicleRepository vehicleRepository;
    private final DriverRepository driverRepository;
    private final VehicleTaskRepository taskRepository;

    public TaskResourceCoordinator(
            VehicleRepository vehicleRepository,
            DriverRepository driverRepository,
            VehicleTaskRepository taskRepository) {
        this.vehicleRepository = vehicleRepository;
        this.driverRepository = driverRepository;
        this.taskRepository = taskRepository;
    }

    public void reserve(UUID vehicleId, UUID driverId) {
        Vehicle vehicle = vehicle(vehicleId);
        Driver driver = driver(driverId);
        vehicle.reserveForDispatch();
        driver.reserve();
    }

    public void start(VehicleTask task) {
        vehicle(task.getVehicleId()).startService();
        driver(task.getDriverId()).startService();
    }

    public void releaseIfUnused(VehicleTask task) {
        Vehicle vehicle = vehicle(task.getVehicleId());
        Driver driver = driver(task.getDriverId());
        if (!taskRepository.existsByVehicleIdAndStatusInAndIdNot(
                task.getVehicleId(), ACTIVE_STATUSES, task.getId())) {
            vehicle.releaseToIdle();
        }
        if (!taskRepository.existsByDriverIdAndStatusInAndIdNot(
                task.getDriverId(), ACTIVE_STATUSES, task.getId())) {
            driver.release();
        }
    }

    private Vehicle vehicle(UUID vehicleId) {
        return vehicleRepository.findByIdForAssignment(vehicleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "车辆不存在"));
    }

    private Driver driver(UUID driverId) {
        return driverRepository.findByIdForAssignment(driverId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "驾驶员不存在"));
    }
}
