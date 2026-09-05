package com.idavy.drtops.domain.fleet;

import com.idavy.drtops.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {

    private final VehicleRepository repository;
    private final VehicleProvisioningService provisioningService;

    public VehicleController(VehicleRepository repository, VehicleProvisioningService provisioningService) {
        this.repository = repository;
        this.provisioningService = provisioningService;
    }

    @GetMapping
    ApiResponse<List<VehicleView>> list() {
        return ApiResponse.ok(repository.findAll().stream().map(VehicleView::from).toList());
    }

    @PostMapping
    ResponseEntity<ApiResponse<VehicleView>> create(
            Authentication authentication, @Valid @RequestBody CreateVehicleRequest request) {
        Vehicle vehicle = provisioningService.create(
                request.plateNumber(), request.vehicleType(), request.capacity(), request.currentStatus(),
                request.lng(), request.lat(), request.fleetName(), request.dispatchable(), request.reason(),
                actorId(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(VehicleView.from(vehicle)));
    }

    private static UUID actorId(Authentication authentication) {
        Object principal = authentication.getPrincipal();
        if (principal instanceof UUID actorId) {
            return actorId;
        }
        return UUID.fromString(authentication.getName());
    }

    public record CreateVehicleRequest(
            @NotBlank String plateNumber,
            @NotBlank String vehicleType,
            @NotNull @Positive Integer capacity,
            @NotBlank String currentStatus,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal lng,
            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal lat,
            @NotBlank String fleetName,
            boolean dispatchable,
            @Size(max = 300) String reason) {
    }
}
