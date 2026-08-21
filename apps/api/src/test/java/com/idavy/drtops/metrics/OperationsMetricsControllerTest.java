package com.idavy.drtops.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

class OperationsMetricsControllerTest {

    private StubMetricsService metricsService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        metricsService = new StubMetricsService();
        ObjectMapper objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc = MockMvcBuilders.standaloneSetup(new OperationsMetricsController(metricsService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void returnsDashboardEnvelopeForSevenDayRange() throws Exception {
        LocalDate endDate = LocalDate.parse("2026-08-13");
        metricsService.dashboard = dashboardFixture(endDate);

        mockMvc.perform(get("/api/metrics/operations-dashboard")
                        .param("endDate", "2026-08-13")
                        .param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operatingDate").value("2026-08-13"))
                .andExpect(jsonPath("$.data.rangeStart").value("2026-08-07"))
                .andExpect(jsonPath("$.data.rangeEnd").value("2026-08-13"))
                .andExpect(jsonPath("$.data.trend.length()").value(7));
    }

    @Test
    void defaultsDashboardRangeToSevenDays() throws Exception {
        LocalDate endDate = LocalDate.parse("2026-08-13");
        metricsService.dashboard = dashboardFixture(endDate);

        mockMvc.perform(get("/api/metrics/operations-dashboard")
                        .param("endDate", "2026-08-13"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trend.length()").value(7));
    }

    @Test
    void rejectsUnsupportedDashboardRange() throws Exception {
        mockMvc.perform(get("/api/metrics/operations-dashboard")
                        .param("endDate", "2026-08-13")
                        .param("days", "30"))
                .andExpect(status().isBadRequest());
    }

    private OperationsDashboard dashboardFixture(LocalDate endDate) {
        List<OperationsDashboard.TrendPoint> trend = endDate.minusDays(6)
                .datesUntil(endDate.plusDays(1))
                .map(date -> new OperationsDashboard.TrendPoint(
                        date,
                        0,
                        0,
                        0,
                        null,
                        null,
                        0,
                        0,
                        0,
                        null))
                .toList();
        OperationsDashboard.CoreMetrics core = new OperationsDashboard.CoreMetrics(
                new OperationsDashboard.OrderVolume(
                        0,
                        null,
                        null,
                        OperationsDashboard.MetricStatus.NO_BASELINE),
                new OperationsDashboard.TaskCompletion(
                        0,
                        0,
                        null,
                        null,
                        OperationsDashboard.MetricStatus.NO_BASELINE),
                new OperationsDashboard.AverageWait(
                        null,
                        0,
                        null,
                        null,
                        OperationsDashboard.MetricStatus.NO_BASELINE),
                new OperationsDashboard.VehicleUtilization(
                        0,
                        0,
                        null,
                        null,
                        OperationsDashboard.MetricStatus.NO_BASELINE));
        OperationsDashboard.Distributions distributions = new OperationsDashboard.Distributions(
                List.of(),
                List.of(),
                List.of());
        return new OperationsDashboard(
                endDate,
                endDate.minusDays(6),
                endDate,
                core,
                trend,
                distributions,
                OffsetDateTime.parse("2026-08-13T09:32:00+08:00"));
    }

    private static final class StubMetricsService extends OperationsMetricsService {

        private OperationsDashboard dashboard;

        private StubMetricsService() {
            super(null, null, null, null);
        }

        @Override
        public OperationsDashboard calculateDashboard(LocalDate endDate, int days) {
            return dashboard;
        }
    }
}
