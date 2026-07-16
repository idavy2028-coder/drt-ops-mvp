package com.idavy.drtops.domain.map;

import java.util.List;

public interface RoutePlanningProvider {

    RoutePlanResult drivingRoute(Coordinate origin, Coordinate destination, List<Coordinate> waypoints);

    DistanceResult distance(Coordinate origin, Coordinate destination);
}
