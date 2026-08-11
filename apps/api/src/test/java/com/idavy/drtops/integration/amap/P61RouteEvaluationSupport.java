package com.idavy.drtops.integration.amap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.domain.map.Coordinate;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

final class P61RouteEvaluationSupport {

    private static final List<String> BEARING_SECTORS = List.of("N", "NE", "E", "SE", "S", "SW", "W", "NW");
    private static final List<String> CARDINAL_SECTORS = List.of("N", "E", "S", "W");
    private static final Set<String> EXPECTED_SAMPLE_IDS = Set.of(
            "S01", "S02", "S03", "S04", "S05",
            "M01", "M02", "M03", "M04", "M05", "M06", "M07", "M08", "M09", "M10",
            "L01", "L02", "L03", "L04", "L05");
    private static final Set<String> SAFE_FAILURE_REASONS = Set.of(
            "request-timeout",
            "upstream-network-unavailable",
            "upstream-response-invalid",
            "upstream-rejected",
            "missing-web-service-key",
            "disabled",
            "route-estimate-failed");

    private P61RouteEvaluationSupport() {
    }

    static List<RoutePair> candidatePairs(List<StopSample> stops) {
        List<CandidateWithDistance> directedPairs = new ArrayList<>();
        for (StopSample origin : stops) {
            for (StopSample destination : stops) {
                if (origin.id().equals(destination.id())) {
                    continue;
                }
                double distance = haversineMeters(origin, destination);
                String sector = bearingSector(origin, destination);
                directedPairs.add(new CandidateWithDistance(
                        new RoutePair(origin.id() + "->" + destination.id(), origin, destination, sector),
                        distance));
            }
        }

        Map<String, List<CandidateWithDistance>> bySector = directedPairs.stream()
                .collect(Collectors.groupingBy(
                        candidate -> candidate.pair().bearingSector(),
                        LinkedHashMap::new,
                        Collectors.toCollection(ArrayList::new)));
        LinkedHashMap<String, RoutePair> selected = new LinkedHashMap<>();
        for (String sector : BEARING_SECTORS) {
            List<CandidateWithDistance> sectorPairs = bySector.getOrDefault(sector, List.of()).stream()
                    .sorted(Comparator.comparingDouble(CandidateWithDistance::distanceMeters))
                    .toList();
            for (double quantile : List.of(0.10D, 0.25D, 0.40D, 0.60D, 0.75D, 0.90D)) {
                if (sectorPairs.isEmpty()) {
                    continue;
                }
                int index = (int) Math.round((sectorPairs.size() - 1) * quantile);
                RoutePair pair = sectorPairs.get(index).pair();
                selected.putIfAbsent(pair.id(), pair);
            }
        }
        return selected.values().stream().limit(48).toList();
    }

    static List<FixedRouteSample> selectFixedSamples(List<PreclassificationResult> preclassified) {
        List<PreclassificationResult> valid = preclassified.stream()
                .filter(PreclassificationResult::success)
                .filter(result -> result.distanceMeters() != null && result.distanceMeters() > 0)
                .filter(result -> result.durationSeconds() != null && result.durationSeconds() > 0)
                .sorted(Comparator.comparingInt(PreclassificationResult::distanceMeters))
                .toList();
        if (valid.size() < 20) {
            throw new IllegalArgumentException("至少需要 20 组有效预分类路线");
        }

        List<PreclassificationResult> shortRoutes = selectRoundRobinBySector(
                valid,
                5,
                Comparator.comparingInt(PreclassificationResult::distanceMeters),
                Set.of());
        int maximumShortDistance = shortRoutes.stream()
                .mapToInt(PreclassificationResult::distanceMeters)
                .max()
                .orElseThrow();
        List<PreclassificationResult> afterShort = valid.stream()
                .filter(result -> result.distanceMeters() > maximumShortDistance)
                .toList();
        if (afterShort.size() < 15) {
            throw new IllegalArgumentException("at least 15 routes must remain after the short band");
        }
        int tenthMiddleCandidateDistance = afterShort.get(9).distanceMeters();
        Set<String> shortPairIds = shortRoutes.stream()
                .map(result -> result.pair().id())
                .collect(Collectors.toSet());
        List<PreclassificationResult> longRoutes = selectRoundRobinBySector(
                valid.stream()
                        .filter(result -> result.distanceMeters() > tenthMiddleCandidateDistance)
                        .toList(),
                5,
                Comparator.comparingInt(PreclassificationResult::distanceMeters).reversed(),
                shortPairIds);
        int minimumLongDistance = longRoutes.stream()
                .mapToInt(PreclassificationResult::distanceMeters)
                .min()
                .orElseThrow();
        int medianDistance = valid.get(valid.size() / 2).distanceMeters();
        List<PreclassificationResult> eligibleMedium = valid.stream()
                .filter(result -> result.distanceMeters() > maximumShortDistance)
                .filter(result -> result.distanceMeters() < minimumLongDistance)
                .toList();
        if (eligibleMedium.size() < 10) {
            throw new IllegalArgumentException("有效路线不足，无法形成严格有序的短、中、长距离样本");
        }

        Set<String> outerPairIds = new HashSet<>(shortPairIds);
        longRoutes.stream().map(result -> result.pair().id()).forEach(outerPairIds::add);
        List<PreclassificationResult> mediumRoutes = selectRoundRobinBySector(
                eligibleMedium,
                10,
                Comparator
                        .comparingInt((PreclassificationResult result) ->
                                Math.abs(result.distanceMeters() - medianDistance))
                        .thenComparingInt(PreclassificationResult::distanceMeters),
                outerPairIds);

        List<FixedRouteSample> samples = new ArrayList<>(20);
        appendSamples(samples, shortRoutes, DistanceBand.SHORT, "S");
        appendSamples(samples, mediumRoutes, DistanceBand.MEDIUM, "M");
        appendSamples(samples, longRoutes, DistanceBand.LONG, "L");
        ensureCardinalCoverage(samples);
        ensureSectorDiversityByBand(samples);
        return List.copyOf(samples);
    }

    static EvaluationSummary summarize(List<FixedRouteSample> samples, List<RouteCallResult> calls) {
        validateFormalEvidence(samples, calls);
        int successCount = (int) calls.stream().filter(RouteCallResult::success).count();
        int failureCount = calls.size() - successCount;
        BigDecimal successRate = calls.isEmpty()
                ? BigDecimal.ZERO.setScale(4)
                : BigDecimal.valueOf(successCount)
                        .divide(BigDecimal.valueOf(calls.size()), 4, RoundingMode.HALF_UP);
        Map<String, Integer> failuresByReason = calls.stream()
                .filter(call -> !call.success())
                .map(call -> safeFailureReason(call.failureReason()))
                .collect(Collectors.toMap(Function.identity(), ignored -> 1, Integer::sum, LinkedHashMap::new));
        Map<String, Long> successfulBySample = calls.stream()
                .filter(RouteCallResult::success)
                .collect(Collectors.groupingBy(RouteCallResult::sampleId, Collectors.counting()));
        List<String> samplesWithoutSuccess = samples.stream()
                .map(FixedRouteSample::sampleId)
                .filter(sampleId -> successfulBySample.getOrDefault(sampleId, 0L) == 0L)
                .toList();

        Set<String> knownSampleIds = samples.stream()
                .map(FixedRouteSample::sampleId)
                .collect(Collectors.toSet());
        Map<String, RouteCallResult> firstSuccessfulBySample = new LinkedHashMap<>();
        calls.stream()
                .filter(RouteCallResult::success)
                .filter(call -> knownSampleIds.contains(call.sampleId()))
                .filter(call -> call.distanceMeters() != null && call.durationSeconds() != null)
                .forEach(call -> firstSuccessfulBySample.putIfAbsent(call.sampleId(), call));
        List<RouteAnomaly> anomalies = new ArrayList<>();
        for (RouteCallResult call : calls) {
            if (!call.success()) {
                continue;
            }
            RouteCallResult baseline = firstSuccessfulBySample.get(call.sampleId());
            if (baseline == null || call.distanceMeters() == null || call.durationSeconds() == null) {
                continue;
            }
            BigDecimal distanceChange = changeRate(baseline.distanceMeters(), call.distanceMeters());
            if (distanceChange.compareTo(new BigDecimal("0.05")) > 0) {
                anomalies.add(new RouteAnomaly(call.sampleId(), "DISTANCE_CHANGE_OVER_5_PERCENT",
                        distanceChange, call.iteration()));
            }
            BigDecimal etaChange = changeRate(baseline.durationSeconds(), call.durationSeconds());
            if (etaChange.compareTo(new BigDecimal("0.20")) > 0) {
                anomalies.add(new RouteAnomaly(call.sampleId(), "ETA_CHANGE_OVER_20_PERCENT",
                        etaChange, call.iteration()));
            }
        }

        LatencySummary latency = latencySummary(calls);
        boolean meetsThreshold = successRate.compareTo(new BigDecimal("0.9900")) >= 0
                && samplesWithoutSuccess.isEmpty();
        return new EvaluationSummary(
                List.copyOf(samples), List.copyOf(calls), successCount, failureCount, successRate,
                meetsThreshold, Map.copyOf(failuresByReason), List.copyOf(anomalies),
                latency, List.copyOf(samplesWithoutSuccess));
    }

    static void writeEvidence(Path outputDirectory, EvaluationSummary summary) throws IOException {
        validateFormalEvidence(summary.samples(), summary.calls());
        Files.createDirectories(outputDirectory);
        Files.writeString(
                outputDirectory.resolve("p6-1-route-calls-2026-08-09.csv"),
                callsCsv(summary.calls()),
                StandardCharsets.UTF_8);
        Files.writeString(
                outputDirectory.resolve("p6-1-route-summary-2026-08-09.json"),
                summaryJson(summary),
                StandardCharsets.UTF_8);
        Files.writeString(
                outputDirectory.resolve("p6-1-real-route-eta-evaluation-2026-08-09.md"),
                summaryMarkdown(summary),
                StandardCharsets.UTF_8);
    }

    static void writePreclassification(
            Path outputDirectory,
            List<PreclassificationResult> preclassified) throws IOException {
        Files.createDirectories(outputDirectory);
        StringBuilder csv = new StringBuilder(
                "pair_id,origin_id,origin_name,origin_longitude,origin_latitude,destination_id,destination_name,"
                        + "destination_longitude,destination_latitude,bearing_sector,success,distance_meters,"
                        + "duration_seconds,latency_ms,failure_reason\n");
        for (PreclassificationResult result : preclassified) {
            RoutePair pair = result.pair();
            csv.append(csvCell(pair.id())).append(',')
                    .append(csvCell(pair.origin().id())).append(',')
                    .append(csvCell(pair.origin().name())).append(',')
                    .append(pair.origin().longitude().toPlainString()).append(',')
                    .append(pair.origin().latitude().toPlainString()).append(',')
                    .append(csvCell(pair.destination().id())).append(',')
                    .append(csvCell(pair.destination().name())).append(',')
                    .append(pair.destination().longitude().toPlainString()).append(',')
                    .append(pair.destination().latitude().toPlainString()).append(',')
                    .append(pair.bearingSector()).append(',')
                    .append(result.success()).append(',')
                    .append(nullableNumber(result.distanceMeters())).append(',')
                    .append(nullableNumber(result.durationSeconds())).append(',')
                    .append(result.latencyMs()).append(',')
                    .append(csvCell(result.success() ? "" : safeFailureReason(result.failureReason())))
                    .append('\n');
        }
        Files.writeString(
                outputDirectory.resolve("p6-1-route-preclassification-2026-08-09.csv"),
                csv.toString(),
                StandardCharsets.UTF_8);
    }

    static void writeFixedSamples(
            Path outputDirectory,
            List<FixedRouteSample> samples) throws IOException {
        Files.createDirectories(outputDirectory);
        StringBuilder csv = new StringBuilder(
                "sample_id,distance_band,pair_id,origin_id,origin_name,origin_longitude,origin_latitude,"
                        + "destination_id,destination_name,destination_longitude,destination_latitude,"
                        + "bearing_sector,baseline_distance_meters,baseline_duration_seconds\n");
        for (FixedRouteSample sample : samples) {
            RoutePair pair = sample.pair();
            csv.append(sample.sampleId()).append(',')
                    .append(sample.band()).append(',')
                    .append(csvCell(pair.id())).append(',')
                    .append(csvCell(pair.origin().id())).append(',')
                    .append(csvCell(pair.origin().name())).append(',')
                    .append(pair.origin().longitude().toPlainString()).append(',')
                    .append(pair.origin().latitude().toPlainString()).append(',')
                    .append(csvCell(pair.destination().id())).append(',')
                    .append(csvCell(pair.destination().name())).append(',')
                    .append(pair.destination().longitude().toPlainString()).append(',')
                    .append(pair.destination().latitude().toPlainString()).append(',')
                    .append(pair.bearingSector()).append(',')
                    .append(sample.baselineDistanceMeters()).append(',')
                    .append(sample.baselineDurationSeconds()).append('\n');
        }
        Files.writeString(
                outputDirectory.resolve("p6-1-fixed-route-samples-2026-08-09.csv"),
                csv.toString(),
                StandardCharsets.UTF_8);
    }

    static String routeFingerprint(List<Coordinate> pathCoordinates) {
        String canonical = pathCoordinates.stream()
                .map(Coordinate::asAmapParameter)
                .collect(Collectors.joining(";"));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 运行时不支持 SHA-256", exception);
        }
    }

    static void writeRouteShapes(Path outputDirectory, List<RouteShapeSummary> shapes) throws IOException {
        Files.createDirectories(outputDirectory);
        StringBuilder csv = new StringBuilder(
                "sample_id,path_point_count,route_fingerprint,distance_meters,duration_seconds,latency_ms\n");
        for (RouteShapeSummary shape : shapes) {
            csv.append(shape.sampleId()).append(',')
                    .append(shape.pathPointCount()).append(',')
                    .append(shape.routeFingerprint()).append(',')
                    .append(shape.distanceMeters()).append(',')
                    .append(shape.durationSeconds()).append(',')
                    .append(shape.latencyMs()).append('\n');
        }
        Files.writeString(
                outputDirectory.resolve("p6-1-route-shapes-2026-08-09.csv"),
                csv.toString(),
                StandardCharsets.UTF_8);
    }

    private static void appendSamples(
            List<FixedRouteSample> target,
            List<PreclassificationResult> source,
            DistanceBand band,
            String prefix) {
        for (int index = 0; index < source.size(); index++) {
            PreclassificationResult result = source.get(index);
            target.add(new FixedRouteSample(
                    prefix + "%02d".formatted(index + 1),
                    band,
                    result.pair(),
                    result.distanceMeters(),
                    result.durationSeconds()));
        }
    }

    private static List<PreclassificationResult> selectRoundRobinBySector(
            List<PreclassificationResult> candidates,
            int count,
            Comparator<PreclassificationResult> comparator,
            Set<String> excludedPairIds) {
        Map<String, List<PreclassificationResult>> bySector = candidates.stream()
                .filter(result -> !excludedPairIds.contains(result.pair().id()))
                .collect(Collectors.groupingBy(
                        result -> result.pair().bearingSector(),
                        LinkedHashMap::new,
                        Collectors.collectingAndThen(Collectors.toList(), values -> values.stream()
                                .sorted(comparator)
                                .toList())));
        List<PreclassificationResult> selected = new ArrayList<>(count);
        for (int round = 0; selected.size() < count; round++) {
            boolean selectedInRound = false;
            for (String sector : BEARING_SECTORS) {
                List<PreclassificationResult> sectorCandidates = bySector.getOrDefault(sector, List.of());
                if (round < sectorCandidates.size()) {
                    selected.add(sectorCandidates.get(round));
                    selectedInRound = true;
                    if (selected.size() == count) {
                        break;
                    }
                }
            }
            if (!selectedInRound) {
                throw new IllegalArgumentException("not enough sector-balanced route candidates");
            }
        }
        return List.copyOf(selected);
    }

    private static void ensureCardinalCoverage(List<FixedRouteSample> samples) {
        Set<String> sectors = samples.stream().map(FixedRouteSample::bearingSector).collect(java.util.stream.Collectors.toSet());
        if (!sectors.containsAll(List.of("N", "E", "S", "W"))) {
            throw new IllegalArgumentException("固定路线未覆盖北、东、南、西四个主方向");
        }
    }

    private static void ensureSectorDiversityByBand(List<FixedRouteSample> samples) {
        for (DistanceBand band : DistanceBand.values()) {
            long distinctSectors = samples.stream()
                    .filter(sample -> sample.band() == band)
                    .map(FixedRouteSample::bearingSector)
                    .distinct()
                    .count();
            if (distinctSectors < 4) {
                throw new IllegalArgumentException("distance band " + band + " must cover at least 4 sectors");
            }
        }
    }

    private static void validateFormalEvidence(
            List<FixedRouteSample> samples,
            List<RouteCallResult> calls) {
        if (samples.size() != 20) {
            throw new IllegalArgumentException("formal evidence requires exactly 20 samples");
        }
        Set<String> sampleIds = samples.stream().map(FixedRouteSample::sampleId).collect(Collectors.toSet());
        if (!sampleIds.equals(EXPECTED_SAMPLE_IDS)) {
            throw new IllegalArgumentException("formal evidence sample IDs must be S01-S05, M01-M10 and L01-L05");
        }
        if (samples.stream().map(sample -> sample.pair().id()).distinct().count() != 20) {
            throw new IllegalArgumentException("formal evidence sample pairs must be unique");
        }
        if (samples.stream().anyMatch(sample -> sample.baselineDistanceMeters() <= 0
                || sample.baselineDurationSeconds() <= 0)) {
            throw new IllegalArgumentException("formal evidence sample baselines must be positive");
        }
        if (calls.size() != 200) {
            throw new IllegalArgumentException("formal evidence requires exactly 200 calls");
        }

        Set<String> sampleIterations = new HashSet<>();
        Map<String, Set<Integer>> iterationsBySample = new LinkedHashMap<>();
        for (RouteCallResult call : calls) {
            if (!sampleIds.contains(call.sampleId())) {
                throw new IllegalArgumentException("formal evidence contains an unknown sample: " + call.sampleId());
            }
            if (call.iteration() < 1 || call.iteration() > 10) {
                throw new IllegalArgumentException("formal evidence iteration must be between 1 and 10");
            }
            if (!sampleIterations.add(call.sampleId() + "#" + call.iteration())) {
                throw new IllegalArgumentException("formal evidence contains a duplicate sample iteration");
            }
            iterationsBySample.computeIfAbsent(call.sampleId(), ignored -> new HashSet<>())
                    .add(call.iteration());
            if (call.requestedAt() == null || call.latencyMs() < 0) {
                throw new IllegalArgumentException("formal evidence call time and latency must be valid");
            }
            if (call.success()) {
                if (!"AMAP".equals(call.provider())) {
                    throw new IllegalArgumentException("successful formal evidence calls must use AMAP");
                }
                if (call.degraded()) {
                    throw new IllegalArgumentException("successful formal evidence calls must not be degraded");
                }
                if (call.distanceMeters() == null || call.distanceMeters() <= 0) {
                    throw new IllegalArgumentException("successful formal evidence calls require positive distance");
                }
                if (call.durationSeconds() == null || call.durationSeconds() <= 0) {
                    throw new IllegalArgumentException("successful formal evidence calls require positive duration");
                }
            }
            else if (call.failureReason() == null || call.failureReason().isBlank()) {
                throw new IllegalArgumentException("failed formal evidence calls require a failure reason");
            }
        }
        for (String sampleId : EXPECTED_SAMPLE_IDS) {
            if (iterationsBySample.getOrDefault(sampleId, Set.of()).size() != 10) {
                throw new IllegalArgumentException("formal evidence requires iterations 1-10 for sample " + sampleId);
            }
        }
    }

    private static double haversineMeters(StopSample origin, StopSample destination) {
        double latitudeDelta = Math.toRadians(destination.latitude().doubleValue() - origin.latitude().doubleValue());
        double longitudeDelta = Math.toRadians(destination.longitude().doubleValue() - origin.longitude().doubleValue());
        double originLatitude = Math.toRadians(origin.latitude().doubleValue());
        double destinationLatitude = Math.toRadians(destination.latitude().doubleValue());
        double a = Math.sin(latitudeDelta / 2) * Math.sin(latitudeDelta / 2)
                + Math.cos(originLatitude) * Math.cos(destinationLatitude)
                * Math.sin(longitudeDelta / 2) * Math.sin(longitudeDelta / 2);
        return 6_371_000D * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static String bearingSector(StopSample origin, StopSample destination) {
        double originLatitude = Math.toRadians(origin.latitude().doubleValue());
        double destinationLatitude = Math.toRadians(destination.latitude().doubleValue());
        double longitudeDelta = Math.toRadians(destination.longitude().doubleValue() - origin.longitude().doubleValue());
        double y = Math.sin(longitudeDelta) * Math.cos(destinationLatitude);
        double x = Math.cos(originLatitude) * Math.sin(destinationLatitude)
                - Math.sin(originLatitude) * Math.cos(destinationLatitude) * Math.cos(longitudeDelta);
        double degrees = (Math.toDegrees(Math.atan2(y, x)) + 360D) % 360D;
        int index = (int) Math.floor((degrees + 22.5D) / 45D) % 8;
        return BEARING_SECTORS.get(index);
    }

    private static BigDecimal changeRate(int baseline, int actual) {
        return BigDecimal.valueOf(Math.abs((long) actual - baseline))
                .divide(BigDecimal.valueOf(baseline), 4, RoundingMode.HALF_UP);
    }

    private static LatencySummary latencySummary(List<RouteCallResult> calls) {
        List<Long> values = calls.stream().map(RouteCallResult::latencyMs).sorted().toList();
        if (values.isEmpty()) {
            return new LatencySummary(0L, 0L, 0L, 0L);
        }
        long median = percentile(values, 0.50D);
        long p90 = percentile(values, 0.90D);
        return new LatencySummary(values.get(0), median, p90, values.get(values.size() - 1));
    }

    private static long percentile(List<Long> sortedValues, double percentile) {
        int index = Math.max(0, (int) Math.ceil(sortedValues.size() * percentile) - 1);
        return sortedValues.get(Math.min(index, sortedValues.size() - 1));
    }

    private static String callsCsv(List<RouteCallResult> calls) {
        StringBuilder csv = new StringBuilder(
                "sample_id,iteration,requested_at,latency_ms,success,distance_meters,duration_seconds,provider,degraded,failure_reason\n");
        for (RouteCallResult call : calls) {
            csv.append(csvCell(call.sampleId())).append(',')
                    .append(call.iteration()).append(',')
                    .append(csvCell(call.requestedAt().toString())).append(',')
                    .append(call.latencyMs()).append(',')
                    .append(call.success()).append(',')
                    .append(nullableNumber(call.distanceMeters())).append(',')
                    .append(nullableNumber(call.durationSeconds())).append(',')
                    .append(csvCell(call.provider() == null ? "" : call.provider())).append(',')
                    .append(call.degraded()).append(',')
                    .append(csvCell(call.success() ? "" : safeFailureReason(call.failureReason())))
                    .append('\n');
        }
        return csv.toString();
    }

    private static String summaryJson(EvaluationSummary summary) throws IOException {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("sampleCount", summary.samples().size());
        json.put("callCount", summary.calls().size());
        json.put("successCount", summary.successCount());
        json.put("failureCount", summary.failureCount());
        json.put("successRate", summary.successRate());
        json.put("meetsSuccessThreshold", summary.meetsSuccessThreshold());
        json.put("samplesWithoutSuccess", summary.samplesWithoutSuccess());
        json.put("failuresByReason", summary.failuresByReason());
        json.put("latency", summary.latency());
        json.put("anomalies", summary.anomalies());
        json.put("etaUsage", "REFERENCE_ONLY");
        return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(json) + "\n";
    }

    private static String summaryMarkdown(EvaluationSummary summary) {
        return """
                # P6-1 真实路径服务与 ETA 技术评估

                > ETA 仅作参考，不用于绩效、拒单或硬性派单判定。

                ## 技术样本汇总

                - 固定路线：%d 组
                - 正式调用：%d 次
                - 成功：%d 次
                - 失败：%d 次
                - 成功率：%s
                - 达到 99%% 阈值：%s
                - 无成功结果的样本：%s
                - 异常跳变：%d 条

                ## 延迟

                - 最小：%d ms
                - 中位数：%d ms
                - P90：%d ms
                - 最大：%d ms

                ## 阶段边界

                本报告只覆盖固定路线技术样本。后续仍需随试运行积累 10 笔真实运营偏差样本；在此之前 ETA 持续按“参考”口径使用。
                """.formatted(
                summary.samples().size(),
                summary.calls().size(),
                summary.successCount(),
                summary.failureCount(),
                summary.successRate().toPlainString(),
                summary.meetsSuccessThreshold(),
                summary.samplesWithoutSuccess().isEmpty() ? "无" : String.join("、", summary.samplesWithoutSuccess()),
                summary.anomalies().size(),
                summary.latency().minimumMs(),
                summary.latency().medianMs(),
                summary.latency().p90Ms(),
                summary.latency().maximumMs());
    }

    private static String safeFailureReason(String reason) {
        return reason != null && SAFE_FAILURE_REASONS.contains(reason) ? reason : "unknown";
    }

    static IllegalStateException sanitizedEvaluationFailure(
            String phase,
            String sampleId,
            String failureReason) {
        return new IllegalStateException(
                "P6-1 " + phase + " failed for " + sampleId + ": " + safeFailureReason(failureReason));
    }

    private static String nullableNumber(Integer value) {
        return value == null ? "" : value.toString();
    }

    private static String csvCell(String value) {
        String escaped = value.replace("\"", "\"\"");
        return escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n")
                ? "\"" + escaped + "\""
                : escaped;
    }

    enum DistanceBand {
        SHORT,
        MEDIUM,
        LONG
    }

    record StopSample(String id, String name, BigDecimal longitude, BigDecimal latitude) {
    }

    record RoutePair(String id, StopSample origin, StopSample destination, String bearingSector) {
    }

    record PreclassificationResult(
            RoutePair pair,
            boolean success,
            Integer distanceMeters,
            Integer durationSeconds,
            long latencyMs,
            String failureReason) {
    }

    record FixedRouteSample(
            String sampleId,
            DistanceBand band,
            RoutePair pair,
            int baselineDistanceMeters,
            int baselineDurationSeconds) {

        String bearingSector() {
            return pair.bearingSector();
        }
    }

    record RouteCallResult(
            String sampleId,
            int iteration,
            Instant requestedAt,
            long latencyMs,
            boolean success,
            Integer distanceMeters,
            Integer durationSeconds,
            String provider,
            boolean degraded,
            String failureReason) {
    }

    record RouteAnomaly(String sampleId, String type, BigDecimal changeRate, int iteration) {
    }

    record RouteShapeSummary(
            String sampleId,
            int pathPointCount,
            String routeFingerprint,
            int distanceMeters,
            int durationSeconds,
            long latencyMs) {
    }

    record LatencySummary(long minimumMs, long medianMs, long p90Ms, long maximumMs) {
    }

    record EvaluationSummary(
            List<FixedRouteSample> samples,
            List<RouteCallResult> calls,
            int successCount,
            int failureCount,
            BigDecimal successRate,
            boolean meetsSuccessThreshold,
            Map<String, Integer> failuresByReason,
            List<RouteAnomaly> anomalies,
            LatencySummary latency,
            List<String> samplesWithoutSuccess) {
    }

    private record CandidateWithDistance(RoutePair pair, double distanceMeters) {
    }
}
