package com.idavy.drtops.integration.amap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.idavy.drtops.domain.dispatch.TravelEstimate;
import com.idavy.drtops.domain.dispatch.TravelEstimateService;
import com.idavy.drtops.domain.map.Coordinate;
import com.idavy.drtops.domain.map.MapProviderException;
import com.idavy.drtops.domain.map.RoutePlanResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.ChannelOption;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

class P61AmapRouteEvaluationIT {

    private static final long MINIMUM_CALL_INTERVAL_MS = 1_000L;

    @Test
    void runsOnlyWhenExplicitlyEnabled() throws Exception {
        assumeTrue(Boolean.getBoolean("drt.integration.amap-evaluation"),
                "P6-1 真实高德评估默认关闭");

        String phase = requiredProperty("drt.integration.amap-phase").toLowerCase(Locale.ROOT);
        Path input = resolveRepositoryPath(requiredProperty("drt.integration.amap-input"));
        Path output = resolveRepositoryPath(requiredProperty("drt.integration.amap-output"));

        switch (phase) {
            case "select" -> runOfflineSelection(input, output);
            case "summarize" -> runOfflineSummary(
                    resolveRepositoryPath(requiredProperty("drt.integration.amap-samples")), input, output);
            case "shapes" -> runRouteShapeCollection(input, output, evaluationRuntime());
            case "preclassify" -> runPreclassification(input, output, evaluationRuntime());
            case "formal" -> runFormalEvaluation(input, output, evaluationRuntime());
            default -> throw new IllegalArgumentException("不支持的 P6-1 评估阶段: " + phase);
        }
    }

    private void runOfflineSelection(Path input, Path output) throws IOException {
        List<P61RouteEvaluationSupport.PreclassificationResult> results = readPreclassification(input);
        List<P61RouteEvaluationSupport.FixedRouteSample> samples =
                P61RouteEvaluationSupport.selectFixedSamples(results);
        P61RouteEvaluationSupport.writeFixedSamples(output, samples);
        assertEquals(20, samples.size());
    }

    private void runOfflineSummary(Path samplesInput, Path callsInput, Path output) throws IOException {
        List<P61RouteEvaluationSupport.FixedRouteSample> samples = readFixedSamples(samplesInput);
        List<P61RouteEvaluationSupport.RouteCallResult> calls = readRouteCalls(callsInput);
        P61RouteEvaluationSupport.EvaluationSummary summary =
                P61RouteEvaluationSupport.summarize(samples, calls);
        P61RouteEvaluationSupport.writeEvidence(output, summary);
        assertEquals(20, samples.size());
        assertEquals(200, calls.size());
        assertTrue(summary.meetsSuccessThreshold());
    }

    private void runRouteShapeCollection(Path input, Path output, EvaluationRuntime runtime) throws Exception {
        List<P61RouteEvaluationSupport.FixedRouteSample> samples = readFixedSamples(input);
        assertEquals(20, samples.size());
        List<P61RouteEvaluationSupport.RouteShapeSummary> shapes = new ArrayList<>(20);
        for (P61RouteEvaluationSupport.FixedRouteSample sample : samples) {
            long startedAt = System.nanoTime();
            RoutePlanResult route;
            try {
                route = runtime.provider().drivingRoute(
                        coordinate(sample.pair().origin()), coordinate(sample.pair().destination()), List.of());
            }
            catch (MapProviderException exception) {
                throw P61RouteEvaluationSupport.sanitizedEvaluationFailure(
                        "shapes", sample.sampleId(), exception.getStatus().degradedReason());
            }
            catch (RuntimeException exception) {
                throw P61RouteEvaluationSupport.sanitizedEvaluationFailure(
                        "shapes", sample.sampleId(), "route-estimate-failed");
            }
            assertTrue(route.distanceMeters() > 0);
            assertTrue(route.durationSeconds() > 0);
            assertFalse(route.pathCoordinates().isEmpty());
            P61RouteEvaluationSupport.RouteShapeSummary shape =
                    new P61RouteEvaluationSupport.RouteShapeSummary(
                            sample.sampleId(),
                            route.pathCoordinates().size(),
                            P61RouteEvaluationSupport.routeFingerprint(route.pathCoordinates()),
                            route.distanceMeters(),
                            route.durationSeconds(),
                            elapsedMillis(startedAt));
            shapes.add(shape);
            logResult("shapes", sample.sampleId(), 1, true, route.distanceMeters(),
                    route.durationSeconds(), shape.latencyMs(), null);
            waitForMinimumInterval(startedAt);
        }
        P61RouteEvaluationSupport.writeRouteShapes(output, shapes);
        assertEquals(20D, providerCallCount(runtime.registry()));
    }

    private void runPreclassification(Path input, Path output, EvaluationRuntime runtime) throws Exception {
        List<P61RouteEvaluationSupport.StopSample> stops = readStops(input);
        assertTrue(stops.size() >= 20, "至少需要 20 个启用虚拟站点");
        List<P61RouteEvaluationSupport.RoutePair> candidates = P61RouteEvaluationSupport.candidatePairs(stops);
        assertTrue(candidates.size() >= 20, "至少需要 20 组候选路线");

        List<P61RouteEvaluationSupport.PreclassificationResult> results = new ArrayList<>();
        for (P61RouteEvaluationSupport.RoutePair pair : candidates) {
            long startedAt = System.nanoTime();
            P61RouteEvaluationSupport.PreclassificationResult result;
            try {
                RoutePlanResult route = runtime.provider().drivingRoute(
                        coordinate(pair.origin()), coordinate(pair.destination()), List.of());
                long latencyMs = elapsedMillis(startedAt);
                boolean valid = route.distanceMeters() > 0 && route.durationSeconds() > 0;
                result = new P61RouteEvaluationSupport.PreclassificationResult(
                        pair,
                        valid,
                        valid ? route.distanceMeters() : null,
                        valid ? route.durationSeconds() : null,
                        latencyMs,
                        valid ? null : "upstream-response-invalid");
            }
            catch (MapProviderException exception) {
                result = new P61RouteEvaluationSupport.PreclassificationResult(
                        pair, false, null, null, elapsedMillis(startedAt),
                        exception.getStatus().degradedReason());
            }
            catch (RuntimeException exception) {
                result = new P61RouteEvaluationSupport.PreclassificationResult(
                        pair, false, null, null, elapsedMillis(startedAt), "route-estimate-failed");
            }
            results.add(result);
            logResult("preclassify", pair.id(), 1, result.success(), result.distanceMeters(),
                    result.durationSeconds(), result.latencyMs(), result.failureReason());
            waitForMinimumInterval(startedAt);
        }

        List<P61RouteEvaluationSupport.FixedRouteSample> samples =
                P61RouteEvaluationSupport.selectFixedSamples(results);
        P61RouteEvaluationSupport.writePreclassification(output, results);
        P61RouteEvaluationSupport.writeFixedSamples(output, samples);
        assertEquals(20, samples.size());
        assertEquals(candidates.size(), providerCallCount(runtime.registry()));
    }

    private void runFormalEvaluation(Path input, Path output, EvaluationRuntime runtime) throws Exception {
        List<P61RouteEvaluationSupport.FixedRouteSample> samples = readFixedSamples(input);
        assertEquals(20, samples.size(), "正式评估必须恰好使用 20 组固定路线");
        List<P61RouteEvaluationSupport.RouteCallResult> calls = new ArrayList<>(200);

        for (int iteration = 1; iteration <= 10; iteration++) {
            for (P61RouteEvaluationSupport.FixedRouteSample sample : samples) {
                long startedAt = System.nanoTime();
                Instant requestedAt = Instant.now();
                P61RouteEvaluationSupport.RouteCallResult call;
                try {
                    TravelEstimateService service = new TravelEstimateService(null, runtime.provider());
                    TravelEstimate estimate = service.estimateBetween(
                            coordinate(sample.pair().origin()), coordinate(sample.pair().destination()));
                    boolean success = !estimate.degraded()
                            && "AMAP".equals(estimate.provider())
                            && estimate.distanceMeters() > 0
                            && estimate.durationSeconds() > 0;
                    call = new P61RouteEvaluationSupport.RouteCallResult(
                            sample.sampleId(), iteration, requestedAt, elapsedMillis(startedAt), success,
                            success ? estimate.distanceMeters() : null,
                            success ? estimate.durationSeconds() : null,
                            estimate.provider(), estimate.degraded(),
                            success ? null : estimate.degradedReason());
                }
                catch (RuntimeException exception) {
                    call = new P61RouteEvaluationSupport.RouteCallResult(
                            sample.sampleId(), iteration, requestedAt, elapsedMillis(startedAt), false,
                            null, null, null, false, "route-estimate-failed");
                }
                calls.add(call);
                logResult("formal", sample.sampleId(), iteration, call.success(), call.distanceMeters(),
                        call.durationSeconds(), call.latencyMs(), call.failureReason());
                waitForMinimumInterval(startedAt);
            }
        }

        P61RouteEvaluationSupport.EvaluationSummary summary =
                P61RouteEvaluationSupport.summarize(samples, calls);
        P61RouteEvaluationSupport.writeEvidence(output, summary);
        assertEquals(200, calls.size());
        assertEquals(200D, providerCallCount(runtime.registry()));
        assertTrue(summary.meetsSuccessThreshold(), "正式路线调用未达到 99% 成功率或存在全失败样本");
        assertFalse(summary.samplesWithoutSuccess().stream().findAny().isPresent());
    }

    private EvaluationRuntime evaluationRuntime() {
        String key = requiredSecretEnvironment("DRT_AMAP_WEB_SERVICE_KEY");
        AmapProperties properties = new AmapProperties();
        properties.setEnabled(true);
        properties.setWebServiceKey(key);
        properties.setBaseUrl("https://restapi.amap.com");
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.getConnectTimeoutMs())
                .responseTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        WebClient webClient = WebClient.builder()
                .baseUrl(properties.getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AmapRoutePlanningProvider provider = new AmapRoutePlanningProvider(
                webClient, properties, new AmapProviderMetrics(registry));
        return new EvaluationRuntime(provider, registry);
    }

    private List<P61RouteEvaluationSupport.StopSample> readStops(Path input) throws IOException {
        CsvTable table = readCsv(input);
        List<P61RouteEvaluationSupport.StopSample> stops = new ArrayList<>();
        for (List<String> row : table.rows()) {
            String coordinateSystem = table.value(row, "coordinate_system");
            if (!"GCJ-02".equalsIgnoreCase(coordinateSystem) && !"GCJ02".equalsIgnoreCase(coordinateSystem)) {
                throw new IllegalArgumentException("虚拟站点坐标系必须为 GCJ-02");
            }
            stops.add(new P61RouteEvaluationSupport.StopSample(
                    table.value(row, "id"),
                    table.value(row, "name"),
                    new BigDecimal(table.value(row, "longitude")),
                    new BigDecimal(table.value(row, "latitude"))));
        }
        return List.copyOf(stops);
    }

    private List<P61RouteEvaluationSupport.FixedRouteSample> readFixedSamples(Path input) throws IOException {
        CsvTable table = readCsv(input);
        List<P61RouteEvaluationSupport.FixedRouteSample> samples = new ArrayList<>();
        for (List<String> row : table.rows()) {
            P61RouteEvaluationSupport.StopSample origin = new P61RouteEvaluationSupport.StopSample(
                    table.value(row, "origin_id"),
                    table.value(row, "origin_name"),
                    new BigDecimal(table.value(row, "origin_longitude")),
                    new BigDecimal(table.value(row, "origin_latitude")));
            P61RouteEvaluationSupport.StopSample destination = new P61RouteEvaluationSupport.StopSample(
                    table.value(row, "destination_id"),
                    table.value(row, "destination_name"),
                    new BigDecimal(table.value(row, "destination_longitude")),
                    new BigDecimal(table.value(row, "destination_latitude")));
            P61RouteEvaluationSupport.RoutePair pair = new P61RouteEvaluationSupport.RoutePair(
                    table.value(row, "pair_id"), origin, destination, table.value(row, "bearing_sector"));
            samples.add(new P61RouteEvaluationSupport.FixedRouteSample(
                    table.value(row, "sample_id"),
                    P61RouteEvaluationSupport.DistanceBand.valueOf(table.value(row, "distance_band")),
                    pair,
                    Integer.parseInt(table.value(row, "baseline_distance_meters")),
                    Integer.parseInt(table.value(row, "baseline_duration_seconds"))));
        }
        return List.copyOf(samples);
    }

    private List<P61RouteEvaluationSupport.PreclassificationResult> readPreclassification(Path input)
            throws IOException {
        CsvTable table = readCsv(input);
        List<P61RouteEvaluationSupport.PreclassificationResult> results = new ArrayList<>();
        for (List<String> row : table.rows()) {
            P61RouteEvaluationSupport.StopSample origin = new P61RouteEvaluationSupport.StopSample(
                    table.value(row, "origin_id"),
                    table.value(row, "origin_name"),
                    new BigDecimal(table.value(row, "origin_longitude")),
                    new BigDecimal(table.value(row, "origin_latitude")));
            P61RouteEvaluationSupport.StopSample destination = new P61RouteEvaluationSupport.StopSample(
                    table.value(row, "destination_id"),
                    table.value(row, "destination_name"),
                    new BigDecimal(table.value(row, "destination_longitude")),
                    new BigDecimal(table.value(row, "destination_latitude")));
            P61RouteEvaluationSupport.RoutePair pair = new P61RouteEvaluationSupport.RoutePair(
                    table.value(row, "pair_id"), origin, destination, table.value(row, "bearing_sector"));
            boolean success = Boolean.parseBoolean(table.value(row, "success"));
            results.add(new P61RouteEvaluationSupport.PreclassificationResult(
                    pair,
                    success,
                    nullableInteger(table.value(row, "distance_meters")),
                    nullableInteger(table.value(row, "duration_seconds")),
                    Long.parseLong(table.value(row, "latency_ms")),
                    blankToNull(table.value(row, "failure_reason"))));
        }
        return List.copyOf(results);
    }

    private List<P61RouteEvaluationSupport.RouteCallResult> readRouteCalls(Path input) throws IOException {
        CsvTable table = readCsv(input);
        List<P61RouteEvaluationSupport.RouteCallResult> calls = new ArrayList<>();
        for (List<String> row : table.rows()) {
            calls.add(new P61RouteEvaluationSupport.RouteCallResult(
                    table.value(row, "sample_id"),
                    Integer.parseInt(table.value(row, "iteration")),
                    Instant.parse(table.value(row, "requested_at")),
                    Long.parseLong(table.value(row, "latency_ms")),
                    Boolean.parseBoolean(table.value(row, "success")),
                    nullableInteger(table.value(row, "distance_meters")),
                    nullableInteger(table.value(row, "duration_seconds")),
                    blankToNull(table.value(row, "provider")),
                    Boolean.parseBoolean(table.value(row, "degraded")),
                    blankToNull(table.value(row, "failure_reason"))));
        }
        return List.copyOf(calls);
    }

    private CsvTable readCsv(Path input) throws IOException {
        List<String> lines = Files.readAllLines(input, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank())
                .toList();
        if (lines.size() < 2) {
            throw new IllegalArgumentException("CSV 缺少数据: " + input.getFileName());
        }
        List<String> header = parseCsvLine(lines.get(0));
        List<List<String>> rows = lines.subList(1, lines.size()).stream().map(this::parseCsvLine).toList();
        return new CsvTable(header, rows);
    }

    private List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    current.append('"');
                    index++;
                }
                else {
                    quoted = !quoted;
                }
            }
            else if (character == ',' && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            }
            else {
                current.append(character);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("CSV 引号未闭合");
        }
        values.add(current.toString());
        return List.copyOf(values);
    }

    private Coordinate coordinate(P61RouteEvaluationSupport.StopSample stop) {
        return new Coordinate(stop.longitude(), stop.latitude());
    }

    private Integer nullableInteger(String value) {
        return value == null || value.isBlank() ? null : Integer.valueOf(value);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少系统属性: " + name);
        }
        return value.trim();
    }

    private String requiredSecretEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank() || "local-simulator".equals(value)) {
            throw new IllegalStateException("真实高德 Key 未配置");
        }
        return value;
    }

    private Path resolveRepositoryPath(String value) {
        Path path = Path.of(value);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("apps")) && Files.isDirectory(current.resolve("docs"))) {
                return current.resolve(path).normalize();
            }
            current = current.getParent();
        }
        throw new IllegalStateException("无法定位仓库根目录");
    }

    private long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private void waitForMinimumInterval(long startedAt) throws InterruptedException {
        long remainingMs = MINIMUM_CALL_INTERVAL_MS - elapsedMillis(startedAt);
        if (remainingMs > 0) {
            Thread.sleep(remainingMs);
        }
    }

    private double providerCallCount(SimpleMeterRegistry registry) {
        return registry.get("drt.map.provider.call.total")
                .tag("operation", "driving-route")
                .counter()
                .count();
    }

    private void logResult(
            String phase,
            String sampleId,
            int iteration,
            boolean success,
            Integer distanceMeters,
            Integer durationSeconds,
            long latencyMs,
            String failureReason) {
        String safeFailure = failureReason == null || failureReason.isBlank() ? "none" : safeFailureReason(failureReason);
        System.out.printf(
                Locale.ROOT,
                "P6_ROUTE phase=%s sample=%s iteration=%d success=%s distance=%s duration=%s latencyMs=%d failure=%s%n",
                phase,
                sampleId,
                iteration,
                success,
                distanceMeters == null ? "none" : distanceMeters,
                durationSeconds == null ? "none" : durationSeconds,
                latencyMs,
                safeFailure);
    }

    private String safeFailureReason(String reason) {
        return switch (reason) {
            case "request-timeout", "upstream-network-unavailable", "upstream-response-invalid",
                    "upstream-rejected", "missing-web-service-key", "disabled", "route-estimate-failed" -> reason;
            default -> "unknown";
        };
    }

    private record EvaluationRuntime(
            AmapRoutePlanningProvider provider,
            SimpleMeterRegistry registry) {
    }

    private record CsvTable(List<String> header, List<List<String>> rows) {

        CsvTable {
            header = List.copyOf(header);
            rows = List.copyOf(rows);
        }

        String value(List<String> row, String column) {
            int index = header.indexOf(column);
            if (index < 0 || index >= row.size()) {
                throw new IllegalArgumentException("CSV 缺少字段: " + column);
            }
            return row.get(index);
        }
    }
}
