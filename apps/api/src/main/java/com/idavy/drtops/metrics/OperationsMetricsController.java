package com.idavy.drtops.metrics;

import com.idavy.drtops.common.ApiResponse;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
        LocalDate operatingDate = date == null ? LocalDate.now() : date;
        return ApiResponse.ok(metricsService.calculateSummary(operatingDate));
    }
}
