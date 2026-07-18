package com.idavy.drtops.domain.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import com.idavy.drtops.domain.location.LocationSource;
import com.idavy.drtops.domain.map.Coordinate;
import com.idavy.drtops.domain.map.DistanceResult;
import com.idavy.drtops.domain.map.MapProviderException;
import com.idavy.drtops.domain.map.MapProviderStatus;
import com.idavy.drtops.domain.map.RoutePlanResult;
import com.idavy.drtops.domain.map.RoutePlanningProvider;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:travel_estimate;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import(TravelEstimateServiceTest.RouteTestConfiguration.class)
class TravelEstimateServiceTest {

    private static final UUID VEHICLE_ID = UUID.fromString("33333333-3333-3333-3333-333333333331");
    private static final Coordinate PICKUP = new Coordinate("105.242100", "35.212300");
    private static final Coordinate DESTINATION = new Coordinate("105.252100", "35.218300");

    @Autowired
    private TravelEstimateService travelEstimateService;

    @Autowired
    private VehicleRepository vehicleRepository;

    @Autowired
    private StubRoutePlanningProvider routePlanningProvider;

    @BeforeEach
    void setUp() {
        vehicleRepository.deleteAll();
        routePlanningProvider.reset();
    }

    @Test
    void usesAmapDrivingRouteAndCachesTheSameLeg() {
        vehicleRepository.save(vehicleWithSnapshot());
        routePlanningProvider.nextRoute = new RoutePlanResult(1_280, 210, List.of());

        TravelEstimate first = travelEstimateService.estimateVehicleToPickup(VEHICLE_ID, PICKUP);
        TravelEstimate second = travelEstimateService.estimateVehicleToPickup(VEHICLE_ID, PICKUP);

        assertThat(first).isEqualTo(new TravelEstimate(1_280, 210, "AMAP", false, null));
        assertThat(second).isEqualTo(first);
        assertThat(routePlanningProvider.drivingRouteCalls).isEqualTo(1);
    }

    @Test
    void fallsBackToStraightLineWhenAmapIsUnavailable() {
        routePlanningProvider.failureReason = "request-timeout";

        TravelEstimate estimate = travelEstimateService.estimatePickupToDestination(PICKUP, DESTINATION);

        assertThat(estimate.distanceMeters()).isPositive();
        assertThat(estimate.durationSeconds()).isPositive();
        assertThat(estimate.provider()).isEqualTo("STRAIGHT_LINE");
        assertThat(estimate.degraded()).isTrue();
        assertThat(estimate.degradedReason()).isEqualTo("request-timeout");
    }

    @Test
    void rejectsVehicleWithoutRecordedLocationSnapshot() {
        vehicleRepository.save(Vehicle.create(
                VEHICLE_ID, "DRT-201", "Microbus", 8, "IDLE", "POINT(105.230000 35.200000)", "Test", true));

        assertThatThrownBy(() -> travelEstimateService.estimateVehicleToPickup(VEHICLE_ID, PICKUP))
                .isInstanceOf(TravelEstimateService.MissingVehicleLocationSnapshotException.class)
                .hasMessage("车辆尚无人工位置快照，需人工复核");
    }

    private Vehicle vehicleWithSnapshot() {
        Vehicle vehicle = Vehicle.create(
                VEHICLE_ID, "DRT-201", "Microbus", 8, "IDLE", "POINT(105.230000 35.200000)", "Test", true);
        OffsetDateTime reportedAt = OffsetDateTime.parse("2026-07-18T08:00:00+08:00");
        vehicle.applyLocationSnapshot(
                "POINT(105.230000 35.200000)",
                "测试车辆位置",
                LocationSource.MANUAL_DISPATCHER,
                "GCJ-02",
                reportedAt,
                reportedAt,
                UUID.randomUUID(),
                null);
        return vehicle;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RouteTestConfiguration {

        @Bean
        @Primary
        StubRoutePlanningProvider routePlanningProvider() {
            return new StubRoutePlanningProvider();
        }
    }

    static class StubRoutePlanningProvider implements RoutePlanningProvider {

        private RoutePlanResult nextRoute = new RoutePlanResult(100, 60, List.of());
        private String failureReason;
        private int drivingRouteCalls;

        @Override
        public RoutePlanResult drivingRoute(Coordinate origin, Coordinate destination, List<Coordinate> waypoints) {
            drivingRouteCalls++;
            if (failureReason != null) {
                throw new MapProviderException(MapProviderStatus.degraded("AMAP", failureReason, "GCJ-02"));
            }
            return nextRoute;
        }

        @Override
        public DistanceResult distance(Coordinate origin, Coordinate destination) {
            return new DistanceResult(nextRoute.distanceMeters(), nextRoute.durationSeconds());
        }

        void reset() {
            nextRoute = new RoutePlanResult(100, 60, List.of());
            failureReason = null;
            drivingRouteCalls = 0;
        }
    }
}
