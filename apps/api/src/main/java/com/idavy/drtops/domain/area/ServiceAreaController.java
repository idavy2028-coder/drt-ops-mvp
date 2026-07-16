package com.idavy.drtops.domain.area;

import com.idavy.drtops.common.ApiResponse;
import com.idavy.drtops.domain.location.ServiceAreaLocationChecker;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;

@RestController
@RequestMapping("/api/service-areas")
public class ServiceAreaController {

    private final ServiceAreaRepository repository;
    private final ServiceAreaCommandService commandService;
    private final ServiceAreaBoundaryImportService boundaryImportService;
    private final ServiceAreaLocationChecker serviceAreaLocationChecker;

    public ServiceAreaController(
            ServiceAreaRepository repository,
            ServiceAreaCommandService commandService,
            ServiceAreaBoundaryImportService boundaryImportService,
            ServiceAreaLocationChecker serviceAreaLocationChecker) {
        this.repository = repository;
        this.commandService = commandService;
        this.boundaryImportService = boundaryImportService;
        this.serviceAreaLocationChecker = serviceAreaLocationChecker;
    }

    @GetMapping
    ApiResponse<List<ServiceArea>> list() {
        return ApiResponse.ok(repository.findAll());
    }

    @PostMapping
    ResponseEntity<ApiResponse<ServiceAreaView>> create(
            Authentication authentication, @Valid @RequestBody CreateServiceAreaRequest request) {
        ServiceAreaView area = commandService.create(new ServiceAreaCommandService.CreateServiceAreaCommand(
                UUID.randomUUID(),
                request.name(),
                request.boundaryWkt(),
                request.serviceStart(),
                request.serviceEnd(),
                request.ruleSetId()), actorId(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(area));
    }

    @PostMapping("/import-district-boundary")
    ApiResponse<ServiceAreaView> importDistrictBoundary(
            Authentication authentication, @RequestParam @NotBlank String keyword) {
        return ApiResponse.ok(boundaryImportService.importDistrictBoundary(keyword, actorId(authentication)));
    }

    @PutMapping("/{serviceAreaId}/boundary")
    ApiResponse<ServiceAreaView> saveBoundary(
            Authentication authentication,
            @org.springframework.web.bind.annotation.PathVariable UUID serviceAreaId,
            @RequestBody ServiceAreaBoundaryRequest request) {
        return ApiResponse.ok(commandService.saveBoundary(serviceAreaId, request, actorId(authentication)));
    }

    @PostMapping("/{serviceAreaId}/publish")
    ApiResponse<ServiceAreaView> publish(
            Authentication authentication,
            @org.springframework.web.bind.annotation.PathVariable UUID serviceAreaId) {
        return ApiResponse.ok(commandService.publish(serviceAreaId, actorId(authentication)));
    }

    @PostMapping("/{serviceAreaId}/contains")
    ApiResponse<ContainsResponse> contains(
            @org.springframework.web.bind.annotation.PathVariable UUID serviceAreaId,
            @Valid @RequestBody ContainsRequest request) {
        ServiceAreaLocationChecker.PublishedAreaCheck check = serviceAreaLocationChecker.checkPublishedArea(
                serviceAreaId, request.longitude(), request.latitude());
        boolean inside = check.inside();
        Double distance = check.distanceToBoundaryMeters();
        return ApiResponse.ok(new ContainsResponse(inside, check.serviceAreaId(), distance, "GCJ02"));
    }

    private UUID actorId(Authentication authentication) {
        if (authentication.getPrincipal() instanceof UUID actorId) {
            return actorId;
        }
        return UUID.fromString("00000000-0000-0000-0000-000000000002");
    }

    public record CreateServiceAreaRequest(
            @NotBlank String name,
            @NotBlank String boundaryWkt,
            @NotBlank String serviceStart,
            @NotBlank String serviceEnd,
            @NotNull UUID ruleSetId) {
    }

    public record ContainsRequest(
            @NotNull @jakarta.validation.constraints.DecimalMin("-180.0")
            @jakarta.validation.constraints.DecimalMax("180.0") java.math.BigDecimal longitude,
            @NotNull @jakarta.validation.constraints.DecimalMin("-90.0")
            @jakarta.validation.constraints.DecimalMax("90.0") java.math.BigDecimal latitude) {
    }

    public record ContainsResponse(
            boolean inside,
            UUID serviceAreaId,
            Double distanceToBoundaryMeters,
            String coordinateSystem) {
    }
}
