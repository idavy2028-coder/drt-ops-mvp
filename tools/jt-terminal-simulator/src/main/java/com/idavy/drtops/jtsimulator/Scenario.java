package com.idavy.drtops.jtsimulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.jt.protocol.codec.ProtocolVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The fixed scenario DSL. Only the actions listed in {@link #SUPPORTED_ACTIONS} exist; anything
 * else is rejected at parse time so a typo can never be silently skipped. Terminal identities in
 * scenarios are synthetic simulator identities; reports only ever emit the masked alias.
 */
public final class Scenario {
    private static final Set<String> SUPPORTED_ACTIONS = Set.of(
            "connect", "register", "authenticate", "heartbeat", "position",
            "activeSafetyAlarm", "attachmentInfo", "fileUploadCompleteNotification",
            "sendRaw", "expectReply", "expectSilence", "expectPeerClose", "burst", "disconnect");

    private final String name;
    private final TerminalSpec terminal;
    private final List<Step> steps;

    private Scenario(String name, TerminalSpec terminal, List<Step> steps) {
        this.name = name;
        this.terminal = terminal;
        this.steps = steps;
    }

    public String name() {
        return name;
    }

    public TerminalSpec terminal() {
        return terminal;
    }

    public List<Step> steps() {
        return steps;
    }

    public static Scenario parse(String json) {
        Objects.requireNonNull(json, "json");
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(json);
        } catch (Exception malformed) {
            throw new IllegalArgumentException("scenario is not valid JSON", malformed);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("scenario document must be a JSON object");
        }
        JsonNode terminalNode = root.get("terminal");
        if (terminalNode == null || !terminalNode.isObject()
                || terminalNode.get("identity") == null
                || terminalNode.get("identity").asText().isBlank()) {
            throw new IllegalArgumentException("scenario requires terminal.identity");
        }
        JsonNode stepsNode = root.get("steps");
        if (stepsNode == null || !stepsNode.isArray() || stepsNode.isEmpty()) {
            throw new IllegalArgumentException("scenario requires a non-empty steps array");
        }
        ProtocolVersion version = ProtocolVersion.JT808_2013;
        if (terminalNode.get("protocolVersion") != null) {
            try {
                version = ProtocolVersion.valueOf(terminalNode.get("protocolVersion").asText().trim());
            } catch (IllegalArgumentException unsupported) {
                throw new IllegalArgumentException("terminal.protocolVersion is not supported", unsupported);
            }
        }
        String plate = terminalNode.get("plateNumber") == null
                ? "SIM-PLATE"
                : terminalNode.get("plateNumber").asText();
        TerminalSpec terminal = new TerminalSpec(
                terminalNode.get("identity").asText(), version, plate);

        List<Step> steps = new ArrayList<>(stepsNode.size());
        for (JsonNode stepNode : stepsNode) {
            steps.add(parseStep(steps.size(), stepNode));
        }
        String name = root.get("scenario") == null ? "unnamed" : root.get("scenario").asText();
        return new Scenario(name, terminal, List.copyOf(steps));
    }

    private static Step parseStep(int index, JsonNode node) {
        if (node == null || !node.isObject() || node.get("action") == null) {
            throw new IllegalArgumentException("step " + index + " requires an action");
        }
        String action = node.get("action").asText();
        if (!SUPPORTED_ACTIONS.contains(action)) {
            throw new IllegalArgumentException(
                    "step " + index + " uses unsupported action '" + action + "'");
        }
        Step step = new Step(
                action,
                text(node, "as"),
                text(node, "connection"),
                text(node, "sampleId"),
                hexParts(node),
                integer(node, "delayBetweenMillis"),
                parseMessageId(node),
                integer(node, "result"),
                integer(node, "requestSerialNo"),
                integer(node, "withinMillis"),
                text(node, "message"),
                integer(node, "count"),
                integer(node, "intervalMillis"),
                integer(node, "timeoutMillis"));
        validateStep(index, step);
        return step;
    }

    private static void validateStep(int index, Step step) {
        switch (step.action()) {
            case "activeSafetyAlarm", "attachmentInfo", "fileUploadCompleteNotification" -> {
                if (step.sampleId() == null) {
                    throw new IllegalArgumentException("step " + index + " requires sampleId");
                }
            }
            case "sendRaw" -> {
                if (step.hexParts().isEmpty()) {
                    throw new IllegalArgumentException("step " + index + " requires hexParts");
                }
            }
            case "burst" -> {
                if (step.count() == null || step.count() < 1) {
                    throw new IllegalArgumentException("step " + index + " requires a positive count");
                }
                if (!"heartbeat".equals(step.message()) && !"position".equals(step.message())) {
                    throw new IllegalArgumentException(
                            "step " + index + " burst message must be heartbeat or position");
                }
            }
            case "expectSilence" -> {
                if (step.withinMillis() == null || step.withinMillis() < 1) {
                    throw new IllegalArgumentException("step " + index + " requires withinMillis");
                }
            }
            default -> { }
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isInt() ? null : value.intValue();
    }

    private static Integer parseMessageId(JsonNode node) {
        String value = text(node, "messageId");
        if (value == null) {
            return null;
        }
        try {
            return value.startsWith("0x") || value.startsWith("0X")
                    ? Integer.parseInt(value.substring(2), 16)
                    : Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("messageId must be a number or 0x-prefixed hex", invalid);
        }
    }

    private static List<String> hexParts(JsonNode node) {
        JsonNode parts = node.get("hexParts");
        if (parts == null || !parts.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>(parts.size());
        for (JsonNode part : parts) {
            values.add(part.asText());
        }
        return List.copyOf(values);
    }

    public record TerminalSpec(String identity, ProtocolVersion protocolVersion, String plateNumber) { }

    public record Step(
            String action,
            String as,
            String connection,
            String sampleId,
            List<String> hexParts,
            Integer delayBetweenMillis,
            Integer messageId,
            Integer result,
            Integer requestSerialNo,
            Integer withinMillis,
            String message,
            Integer count,
            Integer intervalMillis,
            Integer timeoutMillis) { }
}
