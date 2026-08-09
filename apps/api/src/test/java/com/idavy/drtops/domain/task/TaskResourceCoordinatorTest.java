package com.idavy.drtops.domain.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idavy.drtops.domain.fleet.Driver;
import com.idavy.drtops.domain.fleet.DriverRepository;
import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import java.lang.reflect.Proxy;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskResourceCoordinatorTest {

    private static final UUID VEHICLE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DRIVER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private VehicleRepository vehicleRepository;
    private DriverRepository driverRepository;
    private VehicleTaskRepository taskRepository;

    private Vehicle vehicle;
    private Driver driver;
    private TaskResourceCoordinator coordinator;
    private boolean anotherVehicleTask;
    private boolean anotherDriverTask;

    @BeforeEach
    void setUp() {
        vehicle = Vehicle.create(
                VEHICLE_ID, "甘JTEST01", "MINIBUS", 8, "IDLE",
                "POINT(105.240000 35.210000)", "测试车队", true);
        driver = Driver.create(
                DRIVER_ID, "测试司机", "13900009999", "QUALIFIED",
                OffsetDateTime.parse("2026-07-30T06:30:00+08:00"),
                OffsetDateTime.parse("2026-07-30T19:00:00+08:00"),
                "AVAILABLE", "测试车队");
        vehicleRepository = repository(VehicleRepository.class, (method, arguments) ->
                "findByIdForAssignment".equals(method) ? Optional.of(vehicle) : null);
        driverRepository = repository(DriverRepository.class, (method, arguments) ->
                "findByIdForAssignment".equals(method) ? Optional.of(driver) : null);
        taskRepository = repository(VehicleTaskRepository.class, (method, arguments) -> switch (method) {
            case "existsByVehicleIdAndStatusInAndIdNot" -> anotherVehicleTask;
            case "existsByDriverIdAndStatusInAndIdNot" -> anotherDriverTask;
            default -> null;
        });
        coordinator = new TaskResourceCoordinator(vehicleRepository, driverRepository, taskRepository);
    }

    @Test
    void reserveMarksVehicleAndDriverBusy() {
        coordinator.reserve(VEHICLE_ID, DRIVER_ID);

        assertThat(vehicle.getCurrentStatus()).isEqualTo("DISPATCHED");
        assertThat(driver.getCurrentStatus()).isEqualTo("BUSY");
    }

    @Test
    void startMarksVehicleInServiceAndKeepsDriverBusy() {
        VehicleTask task = activeTask();
        coordinator.reserve(VEHICLE_ID, DRIVER_ID);

        coordinator.start(task);

        assertThat(vehicle.getCurrentStatus()).isEqualTo("IN_SERVICE");
        assertThat(driver.getCurrentStatus()).isEqualTo("BUSY");
    }

    @Test
    void releaseRestoresResourcesWhenNoOtherTaskIsActive() {
        VehicleTask task = activeTask();
        coordinator.reserve(VEHICLE_ID, DRIVER_ID);

        coordinator.releaseIfUnused(task);

        assertThat(vehicle.getCurrentStatus()).isEqualTo("IDLE");
        assertThat(driver.getCurrentStatus()).isEqualTo("AVAILABLE");
    }

    @Test
    void releaseKeepsResourcesBusyWhenAnotherTaskIsActive() {
        VehicleTask task = activeTask();
        coordinator.reserve(VEHICLE_ID, DRIVER_ID);
        anotherVehicleTask = true;
        anotherDriverTask = true;

        coordinator.releaseIfUnused(task);

        assertThat(vehicle.getCurrentStatus()).isEqualTo("DISPATCHED");
        assertThat(driver.getCurrentStatus()).isEqualTo("BUSY");
    }

    @Test
    void reserveRejectsNonIdleVehicle() {
        vehicle.reserveForDispatch();

        assertThatThrownBy(() -> coordinator.reserve(VEHICLE_ID, DRIVER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DISPATCHED");
    }

    private VehicleTask activeTask() {
        VehicleTask task = VehicleTask.pendingDeparture(
                VEHICLE_ID, DRIVER_ID, OffsetDateTime.now().plusMinutes(5), "TEST");
        task.dispatch();
        return task;
    }

    @SuppressWarnings("unchecked")
    private static <T> T repository(Class<T> type, RepositoryCall call) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return method.invoke(proxy, arguments);
                    }
                    return call.invoke(method.getName(), arguments);
                });
    }

    @FunctionalInterface
    private interface RepositoryCall {
        Object invoke(String method, Object[] arguments);
    }
}
