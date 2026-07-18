package com.idavy.drtops.domain.area;

import com.idavy.drtops.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/virtual-stops")
public class VirtualStopController {

    private final VirtualStopRepository repository;
    private final VirtualStopCommandService commandService;
    private final VirtualStopImportService importService;

    public VirtualStopController(
            VirtualStopRepository repository,
            VirtualStopCommandService commandService,
            VirtualStopImportService importService) {
        this.repository = repository;
        this.commandService = commandService;
        this.importService = importService;
    }

    @GetMapping
    ApiResponse<List<VirtualStopView>> list(
            @RequestParam(required = false) UUID serviceAreaId,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(repository.findAll().stream()
                .filter(stop -> serviceAreaId == null || serviceAreaId.equals(stop.getServiceAreaId()))
                .filter(stop -> enabled == null || enabled.equals(stop.isEnabled()))
                .filter(stop -> keyword == null || keyword.isBlank()
                        || stop.getName().contains(keyword.trim())
                        || (stop.getAddress() != null && stop.getAddress().contains(keyword.trim())))
                .map(VirtualStopView::from)
                .toList());
    }

    @PostMapping
    ResponseEntity<ApiResponse<VirtualStopView>> create(
            Authentication authentication, @Valid @RequestBody VirtualStopRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(commandService.create(request.toCommand(), actorId(authentication))));
    }

    @PutMapping("/{stopId}")
    ApiResponse<VirtualStopView> update(
            Authentication authentication, @PathVariable UUID stopId, @Valid @RequestBody VirtualStopRequest request) {
        return ApiResponse.ok(commandService.update(stopId, request.toCommand(), actorId(authentication)));
    }

    @PostMapping(value = "/import", consumes = "multipart/form-data")
    ApiResponse<VirtualStopImportResult> importCsv(Authentication authentication, @RequestParam("file") MultipartFile file)
            throws java.io.IOException {
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (file.isEmpty() || !filename.endsWith(".csv")) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "请上传 UTF-8 CSV 模板文件");
        }
        return ApiResponse.ok(importService.importCsv(new String(file.getBytes(), StandardCharsets.UTF_8), actorId(authentication)));
    }

    private UUID actorId(Authentication authentication) {
        return (UUID) authentication.getPrincipal();
    }

    public record VirtualStopRequest(
            @NotNull UUID serviceAreaId,
            @NotBlank String name,
            String address,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal longitude,
            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal latitude,
            @NotNull @Positive Integer serviceRadiusMeters,
            boolean boardingEnabled,
            boolean alightingEnabled,
            String safetyNote,
            boolean enabled) {
        VirtualStopCommandService.Command toCommand() {
            return new VirtualStopCommandService.Command(
                    serviceAreaId, name, address, longitude, latitude, serviceRadiusMeters,
                    boardingEnabled, alightingEnabled, safetyNote, enabled);
        }
    }
}
