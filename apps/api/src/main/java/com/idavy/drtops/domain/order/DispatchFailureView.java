package com.idavy.drtops.domain.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.domain.dispatch.DispatchDecision;
import com.idavy.drtops.domain.dispatch.DispatchRuleSet;
import java.util.ArrayList;
import java.util.List;

public record DispatchFailureView(
        String code,
        String summary,
        int candidateCount,
        List<String> rejectedReasons,
        Integer maxWaitMinutes,
        Integer maxDetourMinutes,
        String mapProvider,
        boolean mapDegraded,
        String mapDegradedReason,
        Integer vehicleToPickupDistanceMeters,
        Integer vehicleToPickupDurationSeconds,
        Integer pickupToDestinationDistanceMeters,
        Integer pickupToDestinationDurationSeconds) {

    static DispatchFailureView from(
            DispatchDecision decision,
            DispatchRuleSet ruleSet,
            ObjectMapper objectMapper) {
        List<String> rejectedReasons = parseRejectedReasons(decision.getRejectedReasonsJson(), objectMapper);
        String code = parseReason(decision.getExplanationJson(), objectMapper, decision.getDecisionResult());
        return new DispatchFailureView(
                code,
                summaryFor(code),
                decision.getCandidateCount(),
                rejectedReasons,
                ruleSet == null ? null : ruleSet.getMaxWaitMinutes(),
                ruleSet == null ? null : ruleSet.getMaxDetourMinutes(),
                decision.getMapProvider(),
                decision.isMapDegraded(),
                decision.getMapDegradedReason(),
                decision.getVehicleToPickupDistanceMeters(),
                decision.getVehicleToPickupDurationSeconds(),
                decision.getPickupToDestinationDistanceMeters(),
                decision.getPickupToDestinationDurationSeconds());
    }

    private static List<String> parseRejectedReasons(String json, ObjectMapper objectMapper) {
        try {
            JsonNode root = objectMapper.readTree(json == null ? "[]" : json);
            List<String> reasons = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode item : root) {
                    String reason = item.isTextual() ? item.asText() : item.path("reason").asText(null);
                    if (reason != null && !reason.isBlank()) {
                        reasons.add(reason);
                    }
                }
            }
            return List.copyOf(reasons);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static String parseReason(String json, ObjectMapper objectMapper, String fallback) {
        try {
            JsonNode root = objectMapper.readTree(json == null ? "{}" : json);
            String reason = root.path("reason").asText(null);
            return reason == null || reason.isBlank() ? fallback : reason;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String summaryFor(String code) {
        return switch (code) {
            case "WAIT_TIME_EXCEEDED" -> "候选车辆预计等待时间超过上限";
            case "NO_CANDIDATE_TASK" -> "当前没有可用车辆任务";
            case "MAP_ROUTE_UNAVAILABLE" -> "路线服务不可用，无法计算可靠路径";
            case "ALL_CANDIDATES_REJECTED" -> "所有候选方案均未满足调度约束";
            default -> "调度约束未满足（代码：" + code + "）";
        };
    }
}
