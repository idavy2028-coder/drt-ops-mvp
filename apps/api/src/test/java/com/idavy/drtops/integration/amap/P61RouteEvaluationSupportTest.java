package com.idavy.drtops.integration.amap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import com.idavy.drtops.domain.map.Coordinate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class P61RouteEvaluationSupportTest {

    @TempDir
    Path tempDir;

    @Test
    void selectsFiveShortTenMediumAndFiveLongRoutesWithCardinalCoverage() {
        List<P61RouteEvaluationSupport.PreclassificationResult> candidates = candidatesAcrossEightBearingSectors();

        List<P61RouteEvaluationSupport.FixedRouteSample> selected =
                P61RouteEvaluationSupport.selectFixedSamples(candidates);

        assertThat(selected).hasSize(20);
        assertThat(selected)
                .filteredOn(sample -> sample.band() == P61RouteEvaluationSupport.DistanceBand.SHORT)
                .hasSize(5);
        assertThat(selected)
                .filteredOn(sample -> sample.band() == P61RouteEvaluationSupport.DistanceBand.MEDIUM)
                .hasSize(10);
        assertThat(selected)
                .filteredOn(sample -> sample.band() == P61RouteEvaluationSupport.DistanceBand.LONG)
                .hasSize(5);
        assertThat(selected).extracting(P61RouteEvaluationSupport.FixedRouteSample::bearingSector)
                .contains("N", "E", "S", "W");
        assertThat(selected).extracting(P61RouteEvaluationSupport.FixedRouteSample::sampleId)
                .containsExactly(
                        "S01", "S02", "S03", "S04", "S05",
                        "M01", "M02", "M03", "M04", "M05", "M06", "M07", "M08", "M09", "M10",
                        "L01", "L02", "L03", "L04", "L05");
    }

    @Test
    void buildsAtMostFortyEightDirectionalCandidatePairsWithoutSameStopRoutes() {
        List<P61RouteEvaluationSupport.StopSample> stops = compassStops();

        List<P61RouteEvaluationSupport.RoutePair> pairs = P61RouteEvaluationSupport.candidatePairs(stops);

        assertThat(pairs).hasSizeLessThanOrEqualTo(48);
        assertThat(pairs).allSatisfy(pair -> assertThat(pair.origin().id()).isNotEqualTo(pair.destination().id()));
        assertThat(pairs).extracting(P61RouteEvaluationSupport.RoutePair::bearingSector)
                .contains("N", "E", "S", "W");
        assertThat(pairs).extracting(P61RouteEvaluationSupport.RoutePair::id).doesNotHaveDuplicates();
    }

    @Test
    void keepsDistanceBandsStrictlyOrderedWhenSectorDistanceRangesAreUneven() {
        List<P61RouteEvaluationSupport.PreclassificationResult> candidates = new ArrayList<>();
        addSectorCandidates(candidates, "N", 100, 200, 300, 1_000, 2_000, 3_000);
        addSectorCandidates(candidates, "E", 110, 5_000, 6_000, 7_000, 8_000, 9_000);
        addSectorCandidates(candidates, "S", 120, 5_100, 6_100, 7_100, 8_100, 9_100);
        addSectorCandidates(candidates, "W", 130, 5_200, 6_200, 7_200, 8_200, 9_200);
        addSectorCandidates(candidates, "NE", 140, 5_300, 6_300, 7_300, 8_300, 9_300);
        addSectorCandidates(candidates, "SE", 150, 5_400, 6_400, 7_400, 8_400, 9_400);
        addSectorCandidates(candidates, "SW", 160, 5_500, 6_500, 7_500, 8_500, 9_500);
        addSectorCandidates(candidates, "NW", 170, 5_600, 6_600, 7_600, 8_600, 9_600);

        List<P61RouteEvaluationSupport.FixedRouteSample> selected =
                P61RouteEvaluationSupport.selectFixedSamples(candidates);

        int maximumShort = selected.stream()
                .filter(sample -> sample.band() == P61RouteEvaluationSupport.DistanceBand.SHORT)
                .mapToInt(P61RouteEvaluationSupport.FixedRouteSample::baselineDistanceMeters)
                .max().orElseThrow();
        int minimumMedium = selected.stream()
                .filter(sample -> sample.band() == P61RouteEvaluationSupport.DistanceBand.MEDIUM)
                .mapToInt(P61RouteEvaluationSupport.FixedRouteSample::baselineDistanceMeters)
                .min().orElseThrow();
        int maximumMedium = selected.stream()
                .filter(sample -> sample.band() == P61RouteEvaluationSupport.DistanceBand.MEDIUM)
                .mapToInt(P61RouteEvaluationSupport.FixedRouteSample::baselineDistanceMeters)
                .max().orElseThrow();
        int minimumLong = selected.stream()
                .filter(sample -> sample.band() == P61RouteEvaluationSupport.DistanceBand.LONG)
                .mapToInt(P61RouteEvaluationSupport.FixedRouteSample::baselineDistanceMeters)
                .min().orElseThrow();

        assertThat(maximumShort).isLessThan(minimumMedium);
        assertThat(maximumMedium).isLessThan(minimumLong);
    }

    @Test
    void rotatesSectorsWithinEveryDistanceBandWhenGlobalExtremesAreSkewed() {
        List<P61RouteEvaluationSupport.PreclassificationResult> candidates = new ArrayList<>();
        addSectorCandidates(candidates, "N", 100, 110, 120, 130, 140, 8_900);
        addSectorCandidates(candidates, "NE", 1_000, 3_000, 4_000, 5_000, 6_000, 8_800);
        addSectorCandidates(candidates, "E", 1_100, 3_100, 4_100, 5_100, 6_100, 8_700);
        addSectorCandidates(candidates, "SE", 1_200, 3_200, 4_200, 5_200, 6_200, 8_600);
        addSectorCandidates(candidates, "S", 1_300, 3_300, 4_300, 5_300, 6_300, 8_500);
        addSectorCandidates(candidates, "W", 1_400, 3_400, 4_400, 5_400, 6_400, 8_400);
        addSectorCandidates(candidates, "NW", 1_500, 3_500, 4_500, 5_500, 6_500, 8_300);
        addSectorCandidates(candidates, "SW", 1_600, 9_000, 9_100, 9_200, 9_300, 9_400);

        List<P61RouteEvaluationSupport.FixedRouteSample> selected =
                P61RouteEvaluationSupport.selectFixedSamples(candidates);

        for (P61RouteEvaluationSupport.DistanceBand band : P61RouteEvaluationSupport.DistanceBand.values()) {
            assertThat(selected.stream()
                    .filter(sample -> sample.band() == band)
                    .map(P61RouteEvaluationSupport.FixedRouteSample::bearingSector)
                    .distinct()
                    .toList())
                    .as("distance band %s should rotate through sectors", band)
                    .hasSizeGreaterThanOrEqualTo(4);
        }
    }

    @Test
    void requiresAtLeastOneHundredNinetyEightSuccessesAndFlagsOnlyChangesOverThresholds() {
        List<P61RouteEvaluationSupport.FixedRouteSample> samples = twentyFixedSamples();
        List<P61RouteEvaluationSupport.RouteCallResult> calls = routeCalls(samples, 2, true);

        P61RouteEvaluationSupport.EvaluationSummary summary =
                P61RouteEvaluationSupport.summarize(samples, calls);

        assertThat(summary.successCount()).isEqualTo(198);
        assertThat(summary.failureCount()).isEqualTo(2);
        assertThat(summary.successRate()).isEqualByComparingTo("0.9900");
        assertThat(summary.meetsSuccessThreshold()).isTrue();
        assertThat(summary.failuresByReason()).containsEntry("request-timeout", 2);
        assertThat(summary.anomalies()).extracting(P61RouteEvaluationSupport.RouteAnomaly::type)
                .containsExactlyInAnyOrder("DISTANCE_CHANGE_OVER_5_PERCENT", "ETA_CHANGE_OVER_20_PERCENT");
        assertThat(summary.latency().minimumMs()).isEqualTo(40L);
        assertThat(summary.latency().maximumMs()).isEqualTo(239L);
    }

    @Test
    void usesFirstSuccessfulFormalCallAsTheVariationBaseline() {
        List<P61RouteEvaluationSupport.FixedRouteSample> samples = twentyFixedSamples();
        List<P61RouteEvaluationSupport.RouteCallResult> calls = new ArrayList<>(routeCalls(samples, 0, false));
        calls.replaceAll(call -> call.sampleId().equals("S01")
                ? new P61RouteEvaluationSupport.RouteCallResult(
                        call.sampleId(), call.iteration(), call.requestedAt(), call.latencyMs(), true,
                        2_000, 200, "AMAP", false, null)
                : call);

        P61RouteEvaluationSupport.EvaluationSummary summary =
                P61RouteEvaluationSupport.summarize(samples, calls);

        assertThat(summary.anomalies()).isEmpty();
    }

    @Test
    void rejectsDuplicateIterationsAndUnknownSamplesInFormalEvidence() {
        List<P61RouteEvaluationSupport.FixedRouteSample> samples = twentyFixedSamples();
        List<P61RouteEvaluationSupport.RouteCallResult> duplicateIteration =
                new ArrayList<>(routeCalls(samples, 0, false));
        P61RouteEvaluationSupport.RouteCallResult secondCall = duplicateIteration.get(1);
        duplicateIteration.set(1, new P61RouteEvaluationSupport.RouteCallResult(
                secondCall.sampleId(), 1, secondCall.requestedAt(), secondCall.latencyMs(), true,
                secondCall.distanceMeters(), secondCall.durationSeconds(), "AMAP", false, null));

        assertThatThrownBy(() -> P61RouteEvaluationSupport.summarize(samples, duplicateIteration))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("iteration");

        List<P61RouteEvaluationSupport.RouteCallResult> unknownSample =
                new ArrayList<>(routeCalls(samples, 0, false));
        P61RouteEvaluationSupport.RouteCallResult firstCall = unknownSample.get(0);
        unknownSample.set(0, new P61RouteEvaluationSupport.RouteCallResult(
                "UNKNOWN", firstCall.iteration(), firstCall.requestedAt(), firstCall.latencyMs(), true,
                firstCall.distanceMeters(), firstCall.durationSeconds(), "AMAP", false, null));

        assertThatThrownBy(() -> P61RouteEvaluationSupport.summarize(samples, unknownSample))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sample");
    }

    @Test
    void rejectsSuccessfulCallsThatAreDegradedNonAmapOrMissingMeasures() {
        List<P61RouteEvaluationSupport.FixedRouteSample> samples = twentyFixedSamples();

        assertInvalidSuccessfulCall(samples, new P61RouteEvaluationSupport.RouteCallResult(
                "S01", 1, Instant.parse("2026-08-09T02:00:00Z"), 40L, true,
                1_000, 100, "AMAP", true, null), "degraded");
        assertInvalidSuccessfulCall(samples, new P61RouteEvaluationSupport.RouteCallResult(
                "S01", 1, Instant.parse("2026-08-09T02:00:00Z"), 40L, true,
                1_000, 100, "FALLBACK", false, null), "AMAP");
        assertInvalidSuccessfulCall(samples, new P61RouteEvaluationSupport.RouteCallResult(
                "S01", 1, Instant.parse("2026-08-09T02:00:00Z"), 40L, true,
                null, 100, "AMAP", false, null), "distance");
    }

    @Test
    void validatesFormalEvidenceBeforeCreatingOutputFiles() {
        List<P61RouteEvaluationSupport.FixedRouteSample> samples = twentyFixedSamples();
        P61RouteEvaluationSupport.EvaluationSummary valid =
                P61RouteEvaluationSupport.summarize(samples, routeCalls(samples, 0, false));
        List<P61RouteEvaluationSupport.RouteCallResult> invalidCalls = new ArrayList<>(valid.calls());
        invalidCalls.remove(invalidCalls.size() - 1);
        P61RouteEvaluationSupport.EvaluationSummary invalid = new P61RouteEvaluationSupport.EvaluationSummary(
                valid.samples(), invalidCalls, valid.successCount(), valid.failureCount(), valid.successRate(),
                valid.meetsSuccessThreshold(), valid.failuresByReason(), valid.anomalies(), valid.latency(),
                valid.samplesWithoutSuccess());
        Path outputDirectory = tempDir.resolve("invalid-evidence");

        assertThatThrownBy(() -> P61RouteEvaluationSupport.writeEvidence(outputDirectory, invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("200");
        assertThat(outputDirectory).doesNotExist();
    }

    @Test
    void buildsSanitizedEvaluationFailuresWithoutRetainingTheUnsafeCause() {
        RuntimeException unsafeCause = new RuntimeException(
                "key=test-secret-key https://restapi.amap.com/v3/direction/driving");

        IllegalStateException failure = P61RouteEvaluationSupport.sanitizedEvaluationFailure(
                "shapes", "S01", unsafeCause.getMessage());

        assertThat(failure)
                .hasMessage("P6-1 shapes failed for S01: unknown")
                .hasNoCause();
        assertThat(failure.getMessage())
                .doesNotContain("test-secret-key", "key=", "restapi.amap.com", "/v3/");
    }

    @Test
    void rejectsNinetyEightPointFivePercentAndAGroupWithoutAnySuccess() {
        List<P61RouteEvaluationSupport.FixedRouteSample> samples = twentyFixedSamples();
        P61RouteEvaluationSupport.EvaluationSummary tooManyFailures =
                P61RouteEvaluationSupport.summarize(samples, routeCalls(samples, 3, false));

        List<P61RouteEvaluationSupport.RouteCallResult> missingGroup = new ArrayList<>(routeCalls(samples, 0, false));
        missingGroup.replaceAll(call -> call.sampleId().equals("S01")
                ? new P61RouteEvaluationSupport.RouteCallResult(
                        call.sampleId(), call.iteration(), call.requestedAt(), call.latencyMs(), false,
                        null, null, null, false, "request-timeout")
                : call);
        P61RouteEvaluationSupport.EvaluationSummary groupWithoutSuccess =
                P61RouteEvaluationSupport.summarize(samples, missingGroup);

        assertThat(tooManyFailures.successRate()).isEqualByComparingTo("0.9850");
        assertThat(tooManyFailures.meetsSuccessThreshold()).isFalse();
        assertThat(groupWithoutSuccess.meetsSuccessThreshold()).isFalse();
        assertThat(groupWithoutSuccess.samplesWithoutSuccess()).containsExactly("S01");
    }

    @Test
    void doesNotFlagDistanceAtFivePercentOrEtaAtTwentyPercent() {
        List<P61RouteEvaluationSupport.FixedRouteSample> samples = twentyFixedSamples();
        List<P61RouteEvaluationSupport.RouteCallResult> calls = new ArrayList<>(routeCalls(samples, 0, false));
        P61RouteEvaluationSupport.FixedRouteSample sample = samples.get(0);
        calls.set(1, new P61RouteEvaluationSupport.RouteCallResult(
                sample.sampleId(), 2, Instant.parse("2026-08-09T02:00:01Z"), 41L, true,
                1_050, 120, "AMAP", false, null));

        P61RouteEvaluationSupport.EvaluationSummary summary =
                P61RouteEvaluationSupport.summarize(samples, calls);

        assertThat(summary.anomalies()).isEmpty();
    }

    @Test
    void writesRecalculableEvidenceWithoutKeyOrCompleteRequestUrl() throws IOException {
        List<P61RouteEvaluationSupport.FixedRouteSample> samples = twentyFixedSamples();
        List<P61RouteEvaluationSupport.RouteCallResult> calls = new ArrayList<>(routeCalls(samples, 2, false));
        P61RouteEvaluationSupport.RouteCallResult unsafe = calls.get(0);
        calls.set(0, new P61RouteEvaluationSupport.RouteCallResult(
                unsafe.sampleId(), unsafe.iteration(), unsafe.requestedAt(), unsafe.latencyMs(), false,
                null, null, null, false,
                "key=test-secret-key https://restapi.amap.com/v3/direction/driving"));
        P61RouteEvaluationSupport.EvaluationSummary summary =
                P61RouteEvaluationSupport.summarize(samples, calls);

        P61RouteEvaluationSupport.writeEvidence(tempDir, summary);

        assertThat(tempDir.resolve("p6-1-route-calls-2026-08-09.csv")).exists();
        assertThat(tempDir.resolve("p6-1-route-summary-2026-08-09.json")).exists();
        assertThat(tempDir.resolve("p6-1-real-route-eta-evaluation-2026-08-09.md")).exists();
        String allEvidence;
        try (var paths = Files.walk(tempDir)) {
            allEvidence = paths.filter(Files::isRegularFile)
                    .map(this::readUtf8)
                    .collect(Collectors.joining("\n"));
        }
        assertThat(allEvidence)
                .doesNotContain("test-secret-key", "key=", "restapi.amap.com/v3/")
                .contains("ETA 仅作参考", "\"successCount\" : 198", "S01,1");
    }

    @Test
    void writesPreclassificationAndFixedSampleCatalogs() throws IOException {
        List<P61RouteEvaluationSupport.PreclassificationResult> preclassified =
                candidatesAcrossEightBearingSectors();
        List<P61RouteEvaluationSupport.FixedRouteSample> fixed =
                P61RouteEvaluationSupport.selectFixedSamples(preclassified);

        P61RouteEvaluationSupport.writePreclassification(tempDir, preclassified);
        P61RouteEvaluationSupport.writeFixedSamples(tempDir, fixed);

        String preclassification = Files.readString(
                tempDir.resolve("p6-1-route-preclassification-2026-08-09.csv"), StandardCharsets.UTF_8);
        String samples = Files.readString(
                tempDir.resolve("p6-1-fixed-route-samples-2026-08-09.csv"), StandardCharsets.UTF_8);
        assertThat(preclassification).contains("pair_id,origin_id,origin_name", "P0,O0,起点0");
        assertThat(samples).contains(
                "sample_id,distance_band,pair_id", "S01,SHORT", "M10,MEDIUM", "L05,LONG");
        assertThat(samples.lines()).hasSize(21);
    }

    @Test
    void writesDeidentifiedRouteShapeSummariesWithDeterministicFingerprints() throws IOException {
        String fingerprint = P61RouteEvaluationSupport.routeFingerprint(List.of(
                new Coordinate("105.240000", "35.210000"),
                new Coordinate("105.250000", "35.220000")));
        P61RouteEvaluationSupport.RouteShapeSummary shape = new P61RouteEvaluationSupport.RouteShapeSummary(
                "S01", 2, fingerprint, 1_000, 100, 50L);

        P61RouteEvaluationSupport.writeRouteShapes(tempDir, List.of(shape));

        String csv = Files.readString(
                tempDir.resolve("p6-1-route-shapes-2026-08-09.csv"), StandardCharsets.UTF_8);
        assertThat(fingerprint).hasSize(64);
        assertThat(csv).contains(
                "sample_id,path_point_count,route_fingerprint,distance_meters,duration_seconds,latency_ms",
                "S01,2," + fingerprint + ",1000,100,50");
        assertThat(csv).doesNotContain("105.240000", "35.210000");
    }

    private List<P61RouteEvaluationSupport.PreclassificationResult> candidatesAcrossEightBearingSectors() {
        List<P61RouteEvaluationSupport.PreclassificationResult> results = new ArrayList<>();
        List<String> sectors = List.of("N", "NE", "E", "SE", "S", "SW", "W", "NW");
        int index = 0;
        for (int distanceStep = 1; distanceStep <= 8; distanceStep++) {
            for (String sector : sectors) {
                P61RouteEvaluationSupport.StopSample origin = new P61RouteEvaluationSupport.StopSample(
                        "O" + index, "起点" + index, new BigDecimal("105.240000"), new BigDecimal("35.210000"));
                P61RouteEvaluationSupport.StopSample destination = new P61RouteEvaluationSupport.StopSample(
                        "D" + index, "终点" + index, new BigDecimal("105.250000"), new BigDecimal("35.220000"));
                P61RouteEvaluationSupport.RoutePair pair = new P61RouteEvaluationSupport.RoutePair(
                        "P" + index, origin, destination, sector);
                results.add(new P61RouteEvaluationSupport.PreclassificationResult(
                        pair, true, distanceStep * 1_000 + index, distanceStep * 100, 50L, null));
                index++;
            }
        }
        return results;
    }

    private void addSectorCandidates(
            List<P61RouteEvaluationSupport.PreclassificationResult> target,
            String sector,
            int... distances) {
        for (int index = 0; index < distances.length; index++) {
            P61RouteEvaluationSupport.StopSample origin = stop(
                    sector + "O" + index, sector + "起点" + index, "105.240000", "35.210000");
            P61RouteEvaluationSupport.StopSample destination = stop(
                    sector + "D" + index, sector + "终点" + index, "105.250000", "35.220000");
            P61RouteEvaluationSupport.RoutePair pair = new P61RouteEvaluationSupport.RoutePair(
                    sector + "P" + index, origin, destination, sector);
            target.add(new P61RouteEvaluationSupport.PreclassificationResult(
                    pair, true, distances[index], Math.max(1, distances[index] / 4), 50L, null));
        }
    }

    private List<P61RouteEvaluationSupport.StopSample> compassStops() {
        return List.of(
                stop("C", "中心", "105.240000", "35.210000"),
                stop("N1", "北一", "105.240000", "35.220000"),
                stop("N2", "北二", "105.241000", "35.230000"),
                stop("E1", "东一", "105.250000", "35.210000"),
                stop("E2", "东二", "105.260000", "35.211000"),
                stop("S1", "南一", "105.240000", "35.200000"),
                stop("S2", "南二", "105.239000", "35.190000"),
                stop("W1", "西一", "105.230000", "35.210000"),
                stop("W2", "西二", "105.220000", "35.209000"),
                stop("NE", "东北", "105.250000", "35.220000"),
                stop("SE", "东南", "105.250000", "35.200000"),
                stop("SW", "西南", "105.230000", "35.200000"),
                stop("NW", "西北", "105.230000", "35.220000"));
    }

    private P61RouteEvaluationSupport.StopSample stop(
            String id, String name, String longitude, String latitude) {
        return new P61RouteEvaluationSupport.StopSample(
                id, name, new BigDecimal(longitude), new BigDecimal(latitude));
    }

    private List<P61RouteEvaluationSupport.FixedRouteSample> twentyFixedSamples() {
        return P61RouteEvaluationSupport.selectFixedSamples(candidatesAcrossEightBearingSectors()).stream()
                .map(sample -> new P61RouteEvaluationSupport.FixedRouteSample(
                        sample.sampleId(), sample.band(), sample.pair(), 1_000, 100))
                .toList();
    }

    private List<P61RouteEvaluationSupport.RouteCallResult> routeCalls(
            List<P61RouteEvaluationSupport.FixedRouteSample> samples,
            int failureCount,
            boolean includeJumps) {
        List<P61RouteEvaluationSupport.RouteCallResult> calls = new ArrayList<>();
        int callIndex = 0;
        for (P61RouteEvaluationSupport.FixedRouteSample sample : samples) {
            for (int iteration = 1; iteration <= 10; iteration++) {
                boolean failure = callIndex < failureCount;
                int distance = includeJumps && sample.sampleId().equals("S02") && iteration == 3 ? 1_051 : 1_000;
                int duration = includeJumps && sample.sampleId().equals("S02") && iteration == 3 ? 121 : 100;
                calls.add(new P61RouteEvaluationSupport.RouteCallResult(
                        sample.sampleId(),
                        iteration,
                        Instant.parse("2026-08-09T02:00:00Z").plusSeconds(callIndex),
                        40L + callIndex,
                        !failure,
                        failure ? null : distance,
                        failure ? null : duration,
                        failure ? null : "AMAP",
                        false,
                        failure ? "request-timeout" : null));
                callIndex++;
            }
        }
        return calls;
    }

    private void assertInvalidSuccessfulCall(
            List<P61RouteEvaluationSupport.FixedRouteSample> samples,
            P61RouteEvaluationSupport.RouteCallResult replacement,
            String expectedMessage) {
        List<P61RouteEvaluationSupport.RouteCallResult> calls = new ArrayList<>(routeCalls(samples, 0, false));
        calls.set(0, replacement);

        assertThatThrownBy(() -> P61RouteEvaluationSupport.summarize(samples, calls))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedMessage);
    }

    private String readUtf8(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        }
        catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
