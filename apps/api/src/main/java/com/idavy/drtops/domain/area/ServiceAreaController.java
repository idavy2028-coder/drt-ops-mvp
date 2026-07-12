package com.idavy.drtops.domain.area;

import com.idavy.drtops.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
@RequestMapping("/api/service-areas")
public class ServiceAreaController {

    private final ServiceAreaRepository repository;

    public ServiceAreaController(ServiceAreaRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    ApiResponse<List<ServiceArea>> list() {
        return ApiResponse.ok(repository.findAll());
    }

    @PostMapping
    ResponseEntity<ApiResponse<ServiceArea>> create(@Valid @RequestBody CreateServiceAreaRequest request) {
        ServiceArea area = ServiceArea.create(
                UUID.randomUUID(),
                request.name(),
                request.boundaryWkt(),
                request.serviceStart(),
                request.serviceEnd(),
                request.ruleSetId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(repository.save(area)));
    }

    public record CreateServiceAreaRequest(
            @NotBlank String name,
            @NotBlank String boundaryWkt,
            @NotBlank String serviceStart,
            @NotBlank String serviceEnd,
            @NotNull UUID ruleSetId) {
    }
}
