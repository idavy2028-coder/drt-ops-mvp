package com.idavy.drtops.domain.fleet;

import com.idavy.drtops.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {

    private final VehicleRepository repository;

    public VehicleController(VehicleRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    ApiResponse<List<VehicleView>> list() {
        return ApiResponse.ok(repository.findAll().stream().map(VehicleView::from).toList());
    }

    @PostMapping
    ResponseEntity<ApiResponse<VehicleView>> create(@Valid @RequestBody CreateVehicleRequest request) {
        Vehicle vehicle = Vehicle.create(
                UUID.randomUUID(),
                request.plateNumber(),
                request.vehicleType(),
                request.capacity(),
                request.currentStatus(),
                "POINT(" + request.lng().toPlainString() + " " + request.lat().toPlainString() + ")",
                request.fleetName(),
                request.dispatchable());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(VehicleView.from(repository.save(vehicle))));
    }

    public record CreateVehicleRequest(
            @NotBlank String plateNumber,
            @NotBlank String vehicleType,
            @NotNull @Positive Integer capacity,
            @NotBlank String currentStatus,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal lng,
            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal lat,
            @NotBlank String fleetName,
            boolean dispatchable) {
    }
}
