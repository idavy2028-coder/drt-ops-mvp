package com.idavy.drtops.integration.amap;

import com.fasterxml.jackson.databind.JsonNode;
import com.idavy.drtops.domain.map.Coordinate;
import com.idavy.drtops.domain.map.DistanceResult;
import com.idavy.drtops.domain.map.RoutePlanResult;
import com.idavy.drtops.domain.map.RoutePlanningProvider;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AmapRoutePlanningProvider implements RoutePlanningProvider {

    private static final int MAX_WAYPOINTS = 16;

    private final AmapWebServiceClient client;

    public AmapRoutePlanningProvider(
            @Qualifier("amapWebClient") WebClient webClient,
            AmapProperties properties,
            AmapProviderMetrics metrics) {
        this.client = new AmapWebServiceClient(webClient, properties, metrics);
    }

    @Override
    public RoutePlanResult drivingRoute(Coordinate origin, Coordinate destination, List<Coordinate> waypoints) {
        List<Coordinate> routeWaypoints = waypoints == null ? List.of() : List.copyOf(waypoints);
        if (routeWaypoints.size() > MAX_WAYPOINTS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "途经点最多支持16个");
        }
        return client.get("driving-route", "/v3/direction/driving", query -> {
            query.queryParam("origin", origin.asAmapParameter())
                    .queryParam("destination", destination.asAmapParameter());
            if (!routeWaypoints.isEmpty()) {
                query.queryParam("waypoints", routeWaypoints.stream()
                        .map(Coordinate::asAmapParameter)
                        .collect(java.util.stream.Collectors.joining(";")));
            }
        }, this::routeResult);
    }

    @Override
    public DistanceResult distance(Coordinate origin, Coordinate destination) {
        return client.get("distance", "/v3/distance", query -> query
                        .queryParam("origins", origin.asAmapParameter())
                        .queryParam("destination", destination.asAmapParameter())
                        .queryParam("type", 1),
                this::distanceResult);
    }

    private RoutePlanResult routeResult(JsonNode root) {
        JsonNode path = root.path("route").path("paths").path(0);
        if (path.isMissingNode()) {
            throw invalidResponse();
        }
        List<Coordinate> coordinates = new ArrayList<>();
        for (JsonNode step : path.path("steps")) {
            String polyline = step.path("polyline").asText();
            for (String value : polyline.split(";")) {
                coordinates.add(parseCoordinate(value));
            }
        }
        return new RoutePlanResult(number(path, "distance"), number(path, "duration"), coordinates);
    }

    private DistanceResult distanceResult(JsonNode root) {
        JsonNode result = root.path("results").path(0);
        if (result.isMissingNode()) {
            throw invalidResponse();
        }
        return new DistanceResult(number(result, "distance"), number(result, "duration"));
    }

    private Coordinate parseCoordinate(String value) {
        String[] parts = value.split(",", -1);
        if (parts.length != 2) {
            throw invalidResponse();
        }
        try {
            return new Coordinate(parts[0].trim(), parts[1].trim());
        } catch (NumberFormatException exception) {
            throw invalidResponse();
        }
    }

    private int number(JsonNode node, String field) {
        try {
            return Integer.parseInt(node.path(field).asText());
        } catch (NumberFormatException exception) {
            throw invalidResponse();
        }
    }

    private IllegalArgumentException invalidResponse() {
        return new IllegalArgumentException();
    }
}
