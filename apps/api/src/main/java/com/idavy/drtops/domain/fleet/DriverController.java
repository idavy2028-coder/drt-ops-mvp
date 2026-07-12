package com.idavy.drtops.domain.fleet;

import com.idavy.drtops.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.OffsetDateTime;
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
@RequestMapping("/api/drivers")
public class DriverController {

    private final DriverRepository repository;

    public DriverController(DriverRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    ApiResponse<List<Driver>> list() {
        return ApiResponse.ok(repository.findAll());
    }

    @PostMapping
    ResponseEntity<ApiResponse<Driver>> create(@Valid @RequestBody CreateDriverRequest request) {
        Driver driver = Driver.create(
                UUID.randomUUID(),
                request.name(),
                request.phone(),
                request.qualificationStatus(),
                request.shiftStart(),
                request.shiftEnd(),
                request.currentStatus(),
                request.fleetName());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(repository.save(driver)));
    }

    public record CreateDriverRequest(
            @NotBlank String name,
            @NotBlank String phone,
            @NotBlank String qualificationStatus,
            OffsetDateTime shiftStart,
            OffsetDateTime shiftEnd,
            @NotBlank String currentStatus,
            @NotBlank String fleetName) {
    }
}
