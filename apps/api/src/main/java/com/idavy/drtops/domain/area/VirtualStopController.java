package com.idavy.drtops.domain.area;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/virtual-stops")
public class VirtualStopController {

    private final VirtualStopRepository repository;

    public VirtualStopController(VirtualStopRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    ApiResponse<List<VirtualStop>> list(@RequestParam(required = false) UUID serviceAreaId) {
        if (serviceAreaId == null) {
            return ApiResponse.ok(repository.findAll());
        }
        return ApiResponse.ok(repository.findByServiceAreaId(serviceAreaId));
    }

    @PostMapping
    ResponseEntity<ApiResponse<VirtualStop>> create(@Valid @RequestBody CreateVirtualStopRequest request) {
        VirtualStop stop = VirtualStop.create(
                UUID.randomUUID(),
                request.serviceAreaId(),
                request.name(),
                "POINT(" + request.lng().toPlainString() + " " + request.lat().toPlainString() + ")",
                request.serviceRadiusMeters(),
                request.boardingEnabled(),
                request.alightingEnabled(),
                request.safetyNote());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(repository.save(stop)));
    }

    public record CreateVirtualStopRequest(
            @NotNull UUID serviceAreaId,
            @NotBlank String name,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal lng,
            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal lat,
            @NotNull @Positive Integer serviceRadiusMeters,
            boolean boardingEnabled,
            boolean alightingEnabled,
            @NotBlank String safetyNote) {
    }
}
