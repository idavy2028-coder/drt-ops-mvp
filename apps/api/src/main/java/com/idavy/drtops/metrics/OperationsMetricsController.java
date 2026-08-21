package com.idavy.drtops.metrics;

import com.idavy.drtops.common.ApiResponse;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/metrics")
public class OperationsMetricsController {

    private final OperationsMetricsService metricsService;

    public OperationsMetricsController(OperationsMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @GetMapping("/operations-summary")
    ApiResponse<OperationsSummary> operationsSummary(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date) {
        LocalDate operatingDate = date == null ? LocalDate.now(OperationsMetricsService.OPERATING_ZONE) : date;
        return ApiResponse.ok(metricsService.calculateSummary(operatingDate));
    }

    @GetMapping("/operations-dashboard")
    ApiResponse<OperationsDashboard> operationsDashboard(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate endDate,
            @RequestParam(defaultValue = "7") int days) {
        if (days != 7) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "days must be 7");
        }
        LocalDate resolvedEndDate = endDate == null
                ? LocalDate.now(OperationsMetricsService.OPERATING_ZONE)
                : endDate;
        return ApiResponse.ok(metricsService.calculateDashboard(resolvedEndDate, days));
    }
}
