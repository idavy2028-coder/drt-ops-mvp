package com.idavy.drtops.domain.map;

import com.idavy.drtops.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/map")
@PreAuthorize("hasAnyAuthority('RESOURCE_MANAGE', 'ORDER_CREATE')")
public class MapProviderController {

    private final MapSearchProvider mapSearchProvider;
    private final RoutePlanningProvider routePlanningProvider;

    public MapProviderController(MapSearchProvider mapSearchProvider, RoutePlanningProvider routePlanningProvider) {
        this.mapSearchProvider = mapSearchProvider;
        this.routePlanningProvider = routePlanningProvider;
    }

    @GetMapping("/address-suggestions")
    ApiResponse<List<AddressSuggestion>> suggest(
            @RequestParam @NotBlank String keyword,
            @RequestParam @NotBlank String city) {
        return ApiResponse.ok(mapSearchProvider.suggest(keyword, city));
    }

    @GetMapping("/geocode")
    ApiResponse<GeocodeResult> geocode(
            @RequestParam @NotBlank String address,
            @RequestParam @NotBlank String city) {
        return ApiResponse.ok(mapSearchProvider.geocode(address, city));
    }

    @PostMapping("/driving-route")
    ApiResponse<RoutePlanResult> drivingRoute(@Valid @RequestBody DrivingRouteRequest request) {
        return ApiResponse.ok(routePlanningProvider.drivingRoute(
                request.origin(), request.destination(), request.waypoints() == null ? List.of() : request.waypoints()));
    }

    @PostMapping("/distance")
    ApiResponse<DistanceResult> distance(@Valid @RequestBody DistanceRequest request) {
        return ApiResponse.ok(routePlanningProvider.distance(request.origin(), request.destination()));
    }

    public record DrivingRouteRequest(
            @NotNull @Valid Coordinate origin,
            @NotNull @Valid Coordinate destination,
            List<@Valid Coordinate> waypoints) {
    }

    public record DistanceRequest(
            @NotNull @Valid Coordinate origin,
            @NotNull @Valid Coordinate destination) {
    }
}
