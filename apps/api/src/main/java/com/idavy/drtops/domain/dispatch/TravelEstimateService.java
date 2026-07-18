package com.idavy.drtops.domain.dispatch;

import com.idavy.drtops.domain.fleet.Vehicle;
import com.idavy.drtops.domain.fleet.VehicleRepository;
import com.idavy.drtops.domain.location.VehicleLocationSnapshotView;
import com.idavy.drtops.domain.map.Coordinate;
import com.idavy.drtops.domain.map.MapProviderException;
import com.idavy.drtops.domain.map.RoutePlanResult;
import com.idavy.drtops.domain.map.RoutePlanningProvider;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TravelEstimateService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(2);
    private static final int FALLBACK_SPEED_METERS_PER_SECOND = 7;
    private static final double EARTH_RADIUS_METERS = 6_371_000D;

    private final VehicleRepository vehicleRepository;
    private final RoutePlanningProvider routePlanningProvider;
    private final Map<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();

    public TravelEstimateService(VehicleRepository vehicleRepository, RoutePlanningProvider routePlanningProvider) {
        this.vehicleRepository = vehicleRepository;
        this.routePlanningProvider = routePlanningProvider;
    }

    public TravelEstimate estimateVehicleToPickup(UUID vehicleId, Coordinate pickupCoordinate) {
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "车辆不存在"));
        VehicleLocationSnapshotView snapshot = VehicleLocationSnapshotView.from(vehicle);
        if (snapshot == null) {
            throw new MissingVehicleLocationSnapshotException();
        }
        return estimate("VEHICLE_TO_PICKUP", new Coordinate(snapshot.longitude(), snapshot.latitude()), pickupCoordinate);
    }

    public TravelEstimate estimatePickupToDestination(Coordinate pickupCoordinate, Coordinate destinationCoordinate) {
        return estimate("PICKUP_TO_DESTINATION", pickupCoordinate, destinationCoordinate);
    }

    private TravelEstimate estimate(String requestType, Coordinate origin, Coordinate destination) {
        CacheKey key = CacheKey.of(requestType, origin, destination);
        CacheEntry cached = cache.get(key);
        Instant now = Instant.now();
        if (cached != null && cached.createdAt().plus(CACHE_TTL).isAfter(now)) {
            return cached.estimate();
        }

        TravelEstimate estimate;
        try {
            RoutePlanResult route = routePlanningProvider.drivingRoute(origin, destination, List.of());
            estimate = new TravelEstimate(route.distanceMeters(), route.durationSeconds(), "AMAP", false, null);
        } catch (MapProviderException exception) {
            estimate = straightLineFallback(origin, destination, exception.getStatus().degradedReason());
        } catch (RuntimeException exception) {
            estimate = straightLineFallback(origin, destination, "route-estimate-failed");
        }
        cache.put(key, new CacheEntry(estimate, now));
        return estimate;
    }

    private TravelEstimate straightLineFallback(Coordinate origin, Coordinate destination, String reason) {
        int distanceMeters = (int) Math.ceil(haversineMeters(origin, destination));
        int durationSeconds = Math.max(60, (int) Math.ceil((double) distanceMeters / FALLBACK_SPEED_METERS_PER_SECOND));
        return new TravelEstimate(distanceMeters, durationSeconds, "STRAIGHT_LINE", true, reason);
    }

    private double haversineMeters(Coordinate origin, Coordinate destination) {
        double latitudeDelta = Math.toRadians(destination.latitude().doubleValue() - origin.latitude().doubleValue());
        double longitudeDelta = Math.toRadians(destination.longitude().doubleValue() - origin.longitude().doubleValue());
        double originLatitude = Math.toRadians(origin.latitude().doubleValue());
        double destinationLatitude = Math.toRadians(destination.latitude().doubleValue());
        double a = Math.sin(latitudeDelta / 2) * Math.sin(latitudeDelta / 2)
                + Math.cos(originLatitude) * Math.cos(destinationLatitude)
                * Math.sin(longitudeDelta / 2) * Math.sin(longitudeDelta / 2);
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public static class MissingVehicleLocationSnapshotException extends RuntimeException {

        MissingVehicleLocationSnapshotException() {
            super("车辆尚无人工位置快照，需人工复核");
        }
    }

    private record CacheEntry(TravelEstimate estimate, Instant createdAt) {
    }

    private record CacheKey(String requestType, String originLongitude, String originLatitude,
                            String destinationLongitude, String destinationLatitude) {

        static CacheKey of(String requestType, Coordinate origin, Coordinate destination) {
            return new CacheKey(
                    requestType,
                    rounded(origin.longitude()),
                    rounded(origin.latitude()),
                    rounded(destination.longitude()),
                    rounded(destination.latitude()));
        }

        private static String rounded(BigDecimal coordinate) {
            return coordinate.setScale(5, RoundingMode.HALF_UP).toPlainString();
        }
    }
}
