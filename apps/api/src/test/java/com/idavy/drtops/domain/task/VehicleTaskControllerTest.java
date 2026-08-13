package com.idavy.drtops.domain.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.idavy.drtops.domain.fleet.VehicleRepository;
import java.lang.reflect.Proxy;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VehicleTaskControllerTest {

    @Test
    void keepsTaskAndReturnsNullPresentationFieldsWhenVehicleIsMissing() {
        UUID vehicleId = UUID.fromString("33333333-3333-3333-3333-333333333331");
        VehicleTask task = VehicleTask.pendingDeparture(
                vehicleId,
                UUID.fromString("44444444-4444-4444-4444-444444444441"),
                OffsetDateTime.parse("2026-08-13T09:00:00+08:00"),
                "ALGORITHM");
        VehicleTaskRepository taskRepository = repositoryProxy(
                VehicleTaskRepository.class,
                "findAllByOrderByPlannedStartAtAsc",
                List.of(task));
        VehicleRepository vehicleRepository = repositoryProxy(
                VehicleRepository.class,
                "findById",
                Optional.empty());

        VehicleTaskController controller = new VehicleTaskController(taskRepository, vehicleRepository, null);

        List<VehicleTaskView> views = controller.list().data();

        assertThat(views).singleElement().satisfies(view -> {
            assertThat(view.id()).isEqualTo(task.getId());
            assertThat(view.vehiclePlateNumber()).isNull();
            assertThat(view.vehicleStatus()).isNull();
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T repositoryProxy(Class<T> repositoryType, String supportedMethod, Object result) {
        return (T) Proxy.newProxyInstance(
                repositoryType.getClassLoader(),
                new Class<?>[] {repositoryType},
                (proxy, method, arguments) -> {
                    if (method.getName().equals(supportedMethod)) {
                        return result;
                    }
                    if (method.getDeclaringClass() == Object.class && method.getName().equals("toString")) {
                        return repositoryType.getSimpleName() + " test proxy";
                    }
                    throw new UnsupportedOperationException("Unexpected repository method: " + method.getName());
                });
    }
}
