package com.idavy.drtops.jtsimulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.jt.protocol.codec.ProtocolVersion;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Fixed scenario DSL with a compatible single-terminal form and an additive multi-terminal form.
 * Multi-terminal steps always name a physical terminal alias; a shared vehicle identifier is
 * therefore never used as a connection or identity key.
 */
public final class Scenario {
    private static final Set<String> SUPPORTED_ACTIONS = Set.of(
            "connect", "register", "authenticate", "heartbeat", "position",
            "activeSafetyAlarm", "attachmentInfo", "fileUploadCompleteNotification",
            "sendRaw", "expectReply", "expectSilence", "expectPeerClose", "burst", "disconnect");
    private static final Set<String> MULTI_ACTIONS = Set.of(
            "connect", "register", "authenticate", "location", "disconnect",
            "advanceClock", "expectActiveSource", "changeWanUplink");

    private final String name;
    private final TerminalSpec terminal;
    private final List<Step> steps;
    private final List<TerminalDefinition> terminals;
    private final List<ScenarioStep> scenarioSteps;

    private Scenario(String name, TerminalSpec terminal, List<Step> steps) {
        this.name = Objects.requireNonNull(name, "name");
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        this.steps = List.copyOf(steps);
        this.terminals = List.of();
        this.scenarioSteps = List.of();
    }

    private Scenario(String name, List<TerminalDefinition> terminals, List<ScenarioStep> scenarioSteps) {
        this.name = Objects.requireNonNull(name, "name");
        this.terminals = List.copyOf(terminals);
        this.scenarioSteps = List.copyOf(scenarioSteps);
        this.terminal = null;
        this.steps = List.of();
    }

    public String name() { return name; }
    /** Compatibility accessor for established single-terminal consumers. */
    public TerminalSpec terminal() { return terminal; }
    /** Compatibility accessor for established single-terminal consumers. */
    public List<Step> steps() { return steps; }
    public List<TerminalDefinition> terminals() { return terminals; }
    public List<ScenarioStep> scenarioSteps() { return scenarioSteps; }
    public boolean multiTerminal() { return !terminals.isEmpty(); }

    public static Scenario dualDevice(TerminalDefinition first, TerminalDefinition second) {
        return multiDevice("dual-device", List.of(first, second), List.of(
                new ScenarioStep(MultiAction.CONNECT, first.alias(), null),
                new ScenarioStep(MultiAction.REGISTER, first.alias(), null),
                new ScenarioStep(MultiAction.AUTHENTICATE, first.alias(), null),
                new ScenarioStep(MultiAction.CONNECT, second.alias(), null),
                new ScenarioStep(MultiAction.REGISTER, second.alias(), null),
                new ScenarioStep(MultiAction.AUTHENTICATE, second.alias(), null)));
    }

    public static Scenario multiDevice(
            String name, List<TerminalDefinition> terminals, List<ScenarioStep> steps) {
        validateMultiTerminals(terminals);
        validateMultiSteps(terminals, steps);
        return new Scenario(name == null || name.isBlank() ? "unnamed" : name, terminals, steps);
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
        return root.has("terminals") ? parseMulti(root) : parseLegacy(root);
    }

    private static Scenario parseLegacy(JsonNode root) {
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
        ProtocolVersion version = protocolVersion(terminalNode, "terminal.protocolVersion");
        String plate = terminalNode.get("plateNumber") == null ? "SIM-PLATE" : terminalNode.get("plateNumber").asText();
        TerminalSpec terminal = new TerminalSpec(
                terminalNode.get("identity").asText(), version, plate,
                textOrDefault(terminalNode, "manufacturerId", "SIMMF"),
                textOrDefault(terminalNode, "model", "SIM-MODEL"),
                textOrDefault(terminalNode, "terminalCode", "SIM0001"));
        List<Step> steps = new ArrayList<>(stepsNode.size());
        for (JsonNode stepNode : stepsNode) {
            steps.add(parseStep(steps.size(), stepNode));
        }
        return new Scenario(root.path("scenario").asText("unnamed"), terminal, steps);
    }

    private static Scenario parseMulti(JsonNode root) {
        JsonNode terminalsNode = root.get("terminals");
        if (terminalsNode == null || !terminalsNode.isArray() || terminalsNode.isEmpty()) {
            throw new IllegalArgumentException("scenario requires a non-empty terminals array");
        }
        JsonNode stepsNode = root.get("steps");
        if (stepsNode == null || !stepsNode.isArray() || stepsNode.isEmpty()) {
            throw new IllegalArgumentException("scenario requires a non-empty steps array");
        }
        List<TerminalDefinition> terminals = new ArrayList<>(terminalsNode.size());
        for (JsonNode node : terminalsNode) {
            terminals.add(new TerminalDefinition(
                    requiredText(node, "alias"), requiredText(node, "identity"),
                    textOrDefault(node, "terminalCode", "SIM0001"), requiredText(node, "vehicleIdentifier"),
                    protocolVersion(node, "terminal.protocolVersion"), strings(node, "capabilities"), strings(node, "roles")));
        }
        List<ScenarioStep> steps = new ArrayList<>(stepsNode.size());
        for (JsonNode node : stepsNode) {
            steps.add(parseScenarioStep(steps.size(), node));
        }
        return multiDevice(root.path("scenario").asText("unnamed"), terminals, steps);
    }

    private static ScenarioStep parseScenarioStep(int index, JsonNode node) {
        String action = requiredText(node, "action");
        if (!MULTI_ACTIONS.contains(action)) {
            throw new IllegalArgumentException("step " + index + " uses unsupported action '" + action + "'");
        }
        return new ScenarioStep(MultiAction.fromDsl(action), text(node, "terminal"), integer(node, "millis"));
    }

    private static void validateMultiTerminals(List<TerminalDefinition> terminals) {
        if (terminals == null || terminals.isEmpty()) {
            throw new IllegalArgumentException("at least one terminal is required");
        }
        Set<String> aliases = new LinkedHashSet<>();
        Set<String> identities = new LinkedHashSet<>();
        Set<String> terminalCodes = new LinkedHashSet<>();
        for (TerminalDefinition terminal : terminals) {
            if (!aliases.add(terminal.alias())) {
                throw new IllegalArgumentException("terminal alias must be unique: " + terminal.alias());
            }
            if (!identities.add(terminal.terminalIdentity())) {
                throw new IllegalArgumentException("terminal identity must be unique: " + terminal.terminalIdentity());
            }
            if (!terminalCodes.add(terminal.terminalCode())) {
                throw new IllegalArgumentException("terminal code must be unique: " + terminal.terminalCode());
            }
        }
    }

    private static void validateMultiSteps(List<TerminalDefinition> terminals, List<ScenarioStep> steps) {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("at least one scenario step is required");
        }
        Set<String> aliases = terminals.stream().map(TerminalDefinition::alias)
                .collect(java.util.stream.Collectors.toSet());
        for (int index = 0; index < steps.size(); index++) {
            ScenarioStep step = steps.get(index);
            if (step.action().requiresTerminal()) {
                if (step.terminalAlias() == null || !aliases.contains(step.terminalAlias())) {
                    throw new IllegalArgumentException("step " + index + " requires a configured terminal alias");
                }
            } else if (step.terminalAlias() != null) {
                throw new IllegalArgumentException("step " + index + " must not identify a terminal alias");
            }
            if (step.action() == MultiAction.ADVANCE_CLOCK && (step.millis() == null || step.millis() < 1)) {
                throw new IllegalArgumentException("step " + index + " requires positive millis");
            }
        }
    }

    private static ProtocolVersion protocolVersion(JsonNode node, String field) {
        if (node.get("protocolVersion") == null) return ProtocolVersion.JT808_2013;
        try {
            return ProtocolVersion.valueOf(node.get("protocolVersion").asText().trim());
        } catch (IllegalArgumentException unsupported) {
            throw new IllegalArgumentException(field + " is not supported", unsupported);
        }
    }

    private static Step parseStep(int index, JsonNode node) {
        if (node == null || !node.isObject() || node.get("action") == null) {
            throw new IllegalArgumentException("step " + index + " requires an action");
        }
        String action = node.get("action").asText();
        if (!SUPPORTED_ACTIONS.contains(action)) {
            throw new IllegalArgumentException("step " + index + " uses unsupported action '" + action + "'");
        }
        Step step = new Step(action, text(node, "as"), text(node, "connection"), text(node, "sampleId"), hexParts(node),
                integer(node, "delayBetweenMillis"), parseMessageId(node), integer(node, "result"),
                integer(node, "requestSerialNo"), integer(node, "withinMillis"), text(node, "message"),
                integer(node, "count"), integer(node, "intervalMillis"), integer(node, "timeoutMillis"));
        validateStep(index, step);
        return step;
    }

    private static void validateStep(int index, Step step) {
        switch (step.action()) {
            case "activeSafetyAlarm", "attachmentInfo", "fileUploadCompleteNotification" -> {
                if (step.sampleId() == null) throw new IllegalArgumentException("step " + index + " requires sampleId");
            }
            case "sendRaw" -> {
                if (step.hexParts().isEmpty()) throw new IllegalArgumentException("step " + index + " requires hexParts");
            }
            case "burst" -> {
                if (step.count() == null || step.count() < 1) throw new IllegalArgumentException("step " + index + " requires a positive count");
                if (!"heartbeat".equals(step.message()) && !"position".equals(step.message())) {
                    throw new IllegalArgumentException("step " + index + " burst message must be heartbeat or position");
                }
            }
            case "expectSilence" -> {
                if (step.withinMillis() == null || step.withinMillis() < 1) throw new IllegalArgumentException("step " + index + " requires withinMillis");
            }
            default -> { }
        }
    }

    private static String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("terminal requires " + field);
        return value;
    }
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
    private static String textOrDefault(JsonNode node, String field, String defaultValue) {
        String value = text(node, field);
        return value == null ? defaultValue : value;
    }
    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isInt() ? null : value.intValue();
    }
    private static Integer parseMessageId(JsonNode node) {
        String value = text(node, "messageId");
        if (value == null) return null;
        try {
            return value.startsWith("0x") || value.startsWith("0X") ? Integer.parseInt(value.substring(2), 16) : Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("messageId must be a number or 0x-prefixed hex", invalid);
        }
    }
    private static List<String> hexParts(JsonNode node) {
        JsonNode parts = node.get("hexParts");
        if (parts == null || !parts.isArray()) return List.of();
        List<String> values = new ArrayList<>(parts.size());
        for (JsonNode part : parts) values.add(part.asText());
        return List.copyOf(values);
    }
    private static Set<String> strings(JsonNode node, String field) {
        JsonNode values = node.get(field);
        if (values == null || !values.isArray()) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode value : values) {
            String text = value.asText();
            if (text.isBlank()) throw new IllegalArgumentException(field + " must not contain blanks");
            result.add(text);
        }
        return Set.copyOf(result);
    }

    public record TerminalSpec(String identity, ProtocolVersion protocolVersion, String plateNumber,
                               String manufacturerId, String model, String terminalCode) { }
    public record Step(String action, String as, String connection, String sampleId, List<String> hexParts,
                       Integer delayBetweenMillis, Integer messageId, Integer result, Integer requestSerialNo,
                       Integer withinMillis, String message, Integer count, Integer intervalMillis, Integer timeoutMillis) { }
    public record TerminalDefinition(String alias, String terminalIdentity, String terminalCode,
                                     String vehicleIdentifier, ProtocolVersion protocolVersion,
                                     Set<String> capabilities, Set<String> roles) {
        public TerminalDefinition {
            requireText(alias, "alias"); requireText(terminalIdentity, "terminalIdentity");
            requireText(terminalCode, "terminalCode"); requireText(vehicleIdentifier, "vehicleIdentifier");
            protocolVersion = Objects.requireNonNull(protocolVersion, "protocolVersion");
            capabilities = Set.copyOf(capabilities == null ? Set.of() : capabilities);
            roles = Set.copyOf(roles == null ? Set.of() : roles);
        }
        private static void requireText(String value, String field) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        }
    }
    public record ScenarioStep(MultiAction action, String terminalAlias, Integer millis) {
        public ScenarioStep { action = Objects.requireNonNull(action, "action"); }
    }
    public record ScenarioResult(int connectionCount, Set<String> authenticatedAliases,
                                 Set<String> vehicleIdentifiers, List<String> completedSteps) { }
    public enum MultiAction {
        CONNECT(true), REGISTER(true), AUTHENTICATE(true), LOCATION(true), DISCONNECT(true),
        ADVANCE_CLOCK(false), EXPECT_ACTIVE_SOURCE(true), CHANGE_WAN_UPLINK(true);
        private final boolean requiresTerminal;
        MultiAction(boolean requiresTerminal) { this.requiresTerminal = requiresTerminal; }
        boolean requiresTerminal() { return requiresTerminal; }
        static MultiAction fromDsl(String action) {
            return switch (action) {
                case "connect" -> CONNECT; case "register" -> REGISTER; case "authenticate" -> AUTHENTICATE;
                case "location" -> LOCATION; case "disconnect" -> DISCONNECT; case "advanceClock" -> ADVANCE_CLOCK;
                case "expectActiveSource" -> EXPECT_ACTIVE_SOURCE; case "changeWanUplink" -> CHANGE_WAN_UPLINK;
                default -> throw new IllegalArgumentException("unsupported action " + action);
            };
        }
    }
}
