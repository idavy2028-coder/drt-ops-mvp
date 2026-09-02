package com.idavy.drtops.jtsimulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Executes a parsed scenario against a live endpoint. Every protocol step requires the honest
 * platform reply (result 0) to pass; a missing or negative reply is a failed step, never a
 * forged success. A failed step stops the scenario and marks the remaining steps as SKIP.
 */
public final class ScenarioRunner {
    private static final Duration DEFAULT_REPLY_TIMEOUT = Duration.ofSeconds(2);
    private static final String FIXTURE_RESOURCE = "/protocol-fixtures/simulator-frames.json";
    private static final Map<String, Integer> ACTION_MESSAGE_IDS = Map.of(
            "activeSafetyAlarm", 0x0200,
            "attachmentInfo", 0x1210,
            "fileUploadCompleteNotification", 0x1206);

    private Scenario scenario;
    private final InetSocketAddress endpoint;
    private final Map<String, SimulatedTerminal> connections = new LinkedHashMap<>();
    private final Map<String, Scenario.TerminalDefinition> terminalDefinitions = new LinkedHashMap<>();
    private final List<ScenarioReport.StepRecord> steps = new ArrayList<>();
    private final List<ScenarioReport.ReplyRecord> replies = new ArrayList<>();
    private final Set<String> authenticatedAliases = new LinkedHashSet<>();
    private final List<String> completedMultiSteps = new ArrayList<>();
    private final Map<String, byte[]> fixtureBodies;
    private final ScenarioControl control;
    private final AtomicReference<InstanceRunState> instanceRunState =
            new AtomicReference<>(InstanceRunState.NEW);
    private String currentConnection;
    private boolean failed;

    private ScenarioRunner(
            Scenario scenario,
            InetSocketAddress endpoint,
            Map<String, byte[]> fixtureBodies,
            ScenarioControl control) {
        this.scenario = scenario;
        this.endpoint = endpoint;
        this.fixtureBodies = fixtureBodies;
        this.control = control;
    }

    /** Test-only multi-device entry point. Control-plane steps are unavailable unless supplied. */
    public ScenarioRunner(InetSocketAddress endpoint, ScenarioControl control) {
        this(null, Objects.requireNonNull(endpoint, "endpoint"), loadFixtures(),
                Objects.requireNonNull(control, "control"));
    }

    /** Wire-only instance entry point; control-plane steps still fail closed. */
    public ScenarioRunner(InetSocketAddress endpoint) {
        this(null, Objects.requireNonNull(endpoint, "endpoint"), loadFixtures(), null);
    }

    public static ScenarioReport run(Scenario scenario, InetSocketAddress endpoint) {
        Objects.requireNonNull(scenario, "scenario");
        Objects.requireNonNull(endpoint, "endpoint");
        ScenarioRunner runner = new ScenarioRunner(scenario, endpoint, loadFixtures(), null);
        return runner.executeReport();
    }

    /** Runs a multi-device scenario and returns its non-sensitive aggregate test result. */
    public Scenario.ScenarioResult run(Scenario scenario) {
        Objects.requireNonNull(scenario, "scenario");
        if (!scenario.multiTerminal()) {
            throw new IllegalArgumentException("multi-device runner requires a multi-terminal scenario");
        }
        if (!instanceRunState.compareAndSet(InstanceRunState.NEW, InstanceRunState.RUNNING)) {
            throw new IllegalStateException("instance runner is single-use and cannot run again");
        }
        try {
            this.scenario = scenario;
            executeMulti(false);
            if (failed) {
                throw new IllegalStateException("multi-device scenario failed; no partial result is available");
            }
            return multiResult();
        } finally {
            instanceRunState.set(InstanceRunState.FINISHED);
        }
    }

    /** Exposes the synthetic per-instance connection identifier to a test control adapter. */
    public UUID connectionId(String terminalAlias) {
        SimulatedTerminal terminal = connections.get(terminalAlias);
        if (terminal == null) {
            throw new IllegalArgumentException("no such terminal alias: " + terminalAlias);
        }
        return terminal.connectionId();
    }

    private ScenarioReport executeReport() {
        if (scenario.multiTerminal()) {
            executeMulti(true);
            return new ScenarioReport(scenario.name(), "multiple-terminals", steps, replies);
        }
        String identity = scenario.terminal().identity();
        String alias = "****" + identity.substring(Math.max(0, identity.length() - 4));
        try {
            for (int index = 0; index < scenario.steps().size(); index++) {
                Scenario.Step step = scenario.steps().get(index);
                if (failed) {
                    steps.add(new ScenarioReport.StepRecord(
                            index, step.action(), step.connection(), ScenarioReport.Outcome.SKIP,
                            "skipped after the first failed step"));
                    continue;
                }
                execute(index, step);
            }
        } finally {
            connections.values().forEach(SimulatedTerminal::close);
        }
        return new ScenarioReport(scenario.name(), alias, steps, replies);
    }

    private void executeMulti(boolean includeReport) {
        terminalDefinitions.clear();
        scenario.terminals().forEach(terminal -> terminalDefinitions.put(terminal.alias(), terminal));
        try {
            for (int index = 0; index < scenario.scenarioSteps().size(); index++) {
                Scenario.ScenarioStep step = scenario.scenarioSteps().get(index);
                if (failed) {
                    if (includeReport) {
                        steps.add(new ScenarioReport.StepRecord(index, step.action().name(), step.terminalAlias(),
                                ScenarioReport.Outcome.SKIP, "skipped after the first failed step"));
                    }
                    continue;
                }
                executeMultiStep(index, step, includeReport);
            }
        } finally {
            connections.values().forEach(SimulatedTerminal::close);
        }
    }

    private void executeMultiStep(int index, Scenario.ScenarioStep step, boolean includeReport) {
        try {
            String detail = switch (step.action()) {
                case CONNECT -> connect(step.terminalAlias());
                case REGISTER -> register(multiConnection(step));
                case AUTHENTICATE -> authenticate(step.terminalAlias(), multiConnection(step));
                case LOCATION -> location(multiConnection(step));
                case DISCONNECT -> disconnect(multiConnection(step));
                case ADVANCE_CLOCK -> advanceClock(step);
                case EXPECT_ACTIVE_SOURCE -> expectActiveSource(step);
                case CHANGE_WAN_UPLINK -> changeWanUplink(step);
            };
            completedMultiSteps.add(step.action().name());
            if (includeReport) {
                steps.add(new ScenarioReport.StepRecord(index, step.action().name(), step.terminalAlias(),
                        ScenarioReport.Outcome.PASS, detail));
            }
        } catch (StepFailure failure) {
            failed = true;
            if (includeReport) {
                steps.add(new ScenarioReport.StepRecord(index, step.action().name(), step.terminalAlias(),
                        ScenarioReport.Outcome.FAIL, failure.getMessage()));
            }
        } catch (RuntimeException unexpected) {
            failed = true;
            if (includeReport) {
                steps.add(new ScenarioReport.StepRecord(index, step.action().name(), step.terminalAlias(),
                        ScenarioReport.Outcome.FAIL, unexpected.getClass().getSimpleName() + ": " + unexpected.getMessage()));
            }
        }
    }

    private String connect(String alias) {
        if (connections.containsKey(alias)) {
            throw new StepFailure("terminal is already connected: " + alias);
        }
        Scenario.TerminalDefinition definition = terminalDefinitions.get(alias);
        if (definition == null) {
            throw new StepFailure("no such terminal alias: " + alias);
        }
        SimulatedTerminal terminal = new SimulatedTerminal(
                definition.terminalIdentity(), definition.protocolVersion(), definition.vehicleIdentifier(),
                "SIMMF", "SIM-MODEL", definition.terminalCode());
        try {
            terminal.connect(endpoint);
        } catch (RuntimeException unreachable) {
            terminal.close();
            throw new StepFailure("connect failed: " + unreachable.getMessage());
        }
        connections.put(alias, terminal);
        return "connected as " + alias;
    }

    private String register(SimulatedTerminal terminal) {
        int serial = terminal.sendRegistration();
        SimulatedTerminal.ReplyRecord reply = awaitReply(terminal, DEFAULT_REPLY_TIMEOUT);
        if (reply == null || reply.messageId() != 0x8100 || reply.result() != 0) {
            throw new StepFailure("registration was not accepted (serial " + serial + ")");
        }
        return "registered";
    }

    private String authenticate(String alias, SimulatedTerminal terminal) {
        int serial = terminal.sendAuthentication();
        SimulatedTerminal.ReplyRecord reply = awaitReply(terminal, DEFAULT_REPLY_TIMEOUT);
        if (reply == null || reply.messageId() != 0x8001 || reply.result() != 0
                || reply.requestMessageId() == null || reply.requestMessageId() != 0x0102
                || reply.requestSerialNo() != serial) {
            throw new StepFailure("authentication was not accepted (serial " + serial + ")");
        }
        authenticatedAliases.add(alias);
        return "authenticated";
    }

    private String location(SimulatedTerminal terminal) {
        int serial = terminal.sendPosition();
        SimulatedTerminal.ReplyRecord reply = awaitReply(terminal, DEFAULT_REPLY_TIMEOUT);
        if (reply == null || reply.messageId() != 0x8001 || reply.result() != 0
                || reply.requestMessageId() == null || reply.requestMessageId() != 0x0200
                || reply.requestSerialNo() != serial) {
            throw new StepFailure("location was not acknowledged with success (serial " + serial + ")");
        }
        return "location acknowledged";
    }

    private String disconnect(SimulatedTerminal terminal) {
        terminal.disconnect();
        return "disconnected";
    }

    private String advanceClock(Scenario.ScenarioStep step) {
        requireControl().advanceClock(step);
        return "clock advanced by " + step.millis() + " ms";
    }

    private String expectActiveSource(Scenario.ScenarioStep step) {
        requireControl().expectActiveSource(step);
        return "active source asserted";
    }

    private String changeWanUplink(Scenario.ScenarioStep step) {
        requireControl().changeWanUplink(step);
        return "WAN uplink changed";
    }

    private ScenarioControl requireControl() {
        if (control == null) {
            throw new StepFailure("control adapter is required for control-plane steps");
        }
        return control;
    }

    private SimulatedTerminal multiConnection(Scenario.ScenarioStep step) {
        SimulatedTerminal terminal = connections.get(step.terminalAlias());
        if (terminal == null) {
            throw new StepFailure("no connected terminal alias: " + step.terminalAlias());
        }
        return terminal;
    }

    private SimulatedTerminal.ReplyRecord awaitReply(SimulatedTerminal terminal, Duration timeout) {
        SimulatedTerminal.ReplyRecord reply = terminal.awaitReply(timeout);
        if (reply != null) {
            record(reply);
        }
        return reply;
    }

    private Scenario.ScenarioResult multiResult() {
        Set<String> vehicleIdentifiers = scenario.terminals().stream()
                .map(Scenario.TerminalDefinition::vehicleIdentifier)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new Scenario.ScenarioResult(connections.size(), Set.copyOf(authenticatedAliases),
                vehicleIdentifiers, List.copyOf(completedMultiSteps));
    }

    private void execute(int index, Scenario.Step step) {
        try {
            String detail = switch (step.action()) {
                case "connect" -> connect(step);
                case "register" -> register(step);
                case "authenticate" -> authenticate(step);
                case "heartbeat" -> sendAndExpectAck(step, thisConnection(step).sendHeartbeat(), 0x0002);
                case "position" -> sendAndExpectAck(step, thisConnection(step).sendPosition(), 0x0200);
                case "activeSafetyAlarm", "attachmentInfo", "fileUploadCompleteNotification" ->
                        sendFixture(step);
                case "sendRaw" -> sendRaw(step);
                case "expectReply" -> expectReply(step);
                case "expectSilence" -> expectSilence(step);
                case "expectPeerClose" -> expectPeerClose(step);
                case "burst" -> burst(step);
                case "disconnect" -> disconnect(step);
                default -> throw new IllegalArgumentException("unsupported action " + step.action());
            };
            steps.add(new ScenarioReport.StepRecord(
                    index, step.action(), step.connection(), ScenarioReport.Outcome.PASS, detail));
        } catch (StepFailure failure) {
            failed = true;
            steps.add(new ScenarioReport.StepRecord(
                    index, step.action(), step.connection(), ScenarioReport.Outcome.FAIL,
                    failure.getMessage()));
        } catch (RuntimeException unexpected) {
            failed = true;
            steps.add(new ScenarioReport.StepRecord(
                    index, step.action(), step.connection(), ScenarioReport.Outcome.FAIL,
                    unexpected.getClass().getSimpleName() + ": " + unexpected.getMessage()));
        }
    }

    private String connect(Scenario.Step step) {
        String name = step.as() == null ? "main" : step.as();
        if (connections.containsKey(name)) {
            throw new StepFailure("connection name is already in use: " + name);
        }
        SimulatedTerminal terminal = new SimulatedTerminal(
                scenario.terminal().identity(),
                scenario.terminal().protocolVersion(),
                scenario.terminal().plateNumber(),
                scenario.terminal().manufacturerId(),
                scenario.terminal().model(),
                scenario.terminal().terminalCode());
        try {
            terminal.connect(endpoint);
        } catch (RuntimeException unreachable) {
            terminal.close();
            throw new StepFailure("connect failed: " + unreachable.getMessage());
        }
        connections.put(name, terminal);
        currentConnection = name;
        return "connected as " + name;
    }

    private String register(Scenario.Step step) {
        SimulatedTerminal terminal = thisConnection(step);
        int serial = terminal.sendRegistration();
        SimulatedTerminal.ReplyRecord reply = awaitReply(terminal, step);
        if (reply == null || reply.messageId() != 0x8100 || reply.result() != 0) {
            throw new StepFailure("registration was not accepted (serial " + serial + ")");
        }
        return "registered";
    }

    private String authenticate(Scenario.Step step) {
        SimulatedTerminal terminal = thisConnection(step);
        int serial = terminal.sendAuthentication();
        SimulatedTerminal.ReplyRecord reply = awaitReply(terminal, step);
        if (reply == null || reply.messageId() != 0x8001 || reply.result() != 0
                || reply.requestMessageId() == null || reply.requestMessageId() != 0x0102
                || reply.requestSerialNo() != serial) {
            throw new StepFailure("authentication was not accepted (serial " + serial + ")");
        }
        return "authenticated";
    }

    private String sendAndExpectAck(Scenario.Step step, int serial, int messageId) {
        SimulatedTerminal terminal = thisConnection(step);
        SimulatedTerminal.ReplyRecord reply = awaitReply(terminal, step);
        if (reply == null || reply.messageId() != 0x8001 || reply.result() != 0
                || reply.requestMessageId() == null || reply.requestMessageId() != messageId
                || reply.requestSerialNo() != serial) {
            throw new StepFailure("message 0x" + Integer.toHexString(messageId)
                    + " was not acknowledged with success (serial " + serial + ")");
        }
        return "acknowledged";
    }

    private String sendFixture(Scenario.Step step) {
        int messageId = ACTION_MESSAGE_IDS.get(step.action());
        byte[] body = fixtureBodies.get(step.sampleId());
        if (body == null) {
            throw new StepFailure("unknown fixture sampleId: " + step.sampleId());
        }
        int serial = thisConnection(step).sendFrame(messageId, body);
        return sendAndExpectAck(step, serial, messageId);
    }

    private String sendRaw(Scenario.Step step) {
        SimulatedTerminal terminal = thisConnection(step);
        long delay = step.delayBetweenMillis() == null ? 0 : step.delayBetweenMillis();
        List<String> parts = step.hexParts();
        for (int partIndex = 0; partIndex < parts.size(); partIndex++) {
            byte[] bytes;
            try {
                bytes = HexFormat.of().parseHex(parts.get(partIndex));
            } catch (IllegalArgumentException malformed) {
                throw new StepFailure("hexParts[" + partIndex + "] is not valid hexadecimal");
            }
            terminal.sendRaw(bytes);
            if (delay > 0 && partIndex + 1 < parts.size()) {
                sleep(delay);
            }
        }
        return "sent " + parts.size() + " raw part(s)";
    }

    private String expectReply(Scenario.Step step) {
        SimulatedTerminal terminal = thisConnection(step);
        SimulatedTerminal.ReplyRecord reply = awaitReply(terminal, step);
        if (reply == null) {
            throw new StepFailure("expected reply never arrived");
        }
        int expectedMessageId = step.messageId() == null ? 0x8001 : step.messageId();
        if (reply.messageId() != expectedMessageId) {
            throw new StepFailure("expected message 0x" + Integer.toHexString(expectedMessageId)
                    + " but received 0x" + Integer.toHexString(reply.messageId()));
        }
        if (step.result() != null && reply.result() != step.result()) {
            throw new StepFailure("expected result " + step.result() + " but received " + reply.result());
        }
        if (step.requestSerialNo() != null && reply.requestSerialNo() != step.requestSerialNo()) {
            throw new StepFailure("expected request serial " + step.requestSerialNo()
                    + " but received " + reply.requestSerialNo());
        }
        return "reply matched";
    }

    private String expectSilence(Scenario.Step step) {
        SimulatedTerminal terminal = thisConnection(step);
        SimulatedTerminal.ReplyRecord reply = terminal.awaitReply(Duration.ofMillis(step.withinMillis()));
        if (reply != null) {
            record(reply);
            throw new StepFailure("expected silence but received message 0x"
                    + Integer.toHexString(reply.messageId()));
        }
        return "silent for " + step.withinMillis() + " ms";
    }

    private String expectPeerClose(Scenario.Step step) {
        SimulatedTerminal terminal = thisConnection(step);
        Duration timeout = step.timeoutMillis() == null
                ? DEFAULT_REPLY_TIMEOUT
                : Duration.ofMillis(step.timeoutMillis());
        if (!terminal.awaitPeerClose(timeout)) {
            throw new StepFailure("platform did not close the connection within " + timeout.toMillis() + " ms");
        }
        return "platform closed the connection";
    }

    private String burst(Scenario.Step step) {
        SimulatedTerminal terminal = thisConnection(step);
        int count = step.count();
        long interval = step.intervalMillis() == null ? 0 : step.intervalMillis();
        int requestMessageId = "position".equals(step.message()) ? 0x0200 : 0x0002;
        for (int index = 0; index < count; index++) {
            if ("position".equals(step.message())) {
                terminal.sendPosition();
            } else {
                terminal.sendHeartbeat();
            }
            if (interval > 0 && index + 1 < count) {
                sleep(interval);
            }
        }
        for (int index = 0; index < count; index++) {
            SimulatedTerminal.ReplyRecord reply = awaitReply(terminal, step);
            if (reply == null || reply.messageId() != 0x8001 || reply.result() != 0
                    || reply.requestMessageId() == null || reply.requestMessageId() != requestMessageId) {
                throw new StepFailure("burst reply " + (index + 1) + "/" + count
                        + " was not a successful acknowledgement");
            }
        }
        return "burst of " + count + " " + step.message() + " message(s) acknowledged";
    }

    private String disconnect(Scenario.Step step) {
        thisConnection(step).disconnect();
        return "disconnected";
    }

    private SimulatedTerminal thisConnection(Scenario.Step step) {
        String name = step.connection() == null ? currentConnection : step.connection();
        SimulatedTerminal terminal = name == null ? null : connections.get(name);
        if (terminal == null) {
            throw new StepFailure("no such connection: " + name);
        }
        return terminal;
    }

    private SimulatedTerminal.ReplyRecord awaitReply(SimulatedTerminal terminal, Scenario.Step step) {
        Duration timeout = step.timeoutMillis() == null
                ? DEFAULT_REPLY_TIMEOUT
                : Duration.ofMillis(step.timeoutMillis());
        SimulatedTerminal.ReplyRecord reply = terminal.awaitReply(timeout);
        if (reply != null) {
            record(reply);
        }
        return reply;
    }

    private void record(SimulatedTerminal.ReplyRecord reply) {
        replies.add(new ScenarioReport.ReplyRecord(
                reply.messageId(), reply.requestMessageId(), reply.result(), reply.requestSerialNo()));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new StepFailure("interrupted during a timed step");
        }
    }

    private static Map<String, byte[]> loadFixtures() {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root;
        try (InputStream resource = ScenarioRunner.class.getResourceAsStream(FIXTURE_RESOURCE)) {
            if (resource == null) {
                throw new IllegalStateException("missing simulator fixture resource " + FIXTURE_RESOURCE);
            }
            root = mapper.readTree(resource);
        } catch (IOException unreadable) {
            throw new IllegalStateException("cannot read simulator fixtures", unreadable);
        }
        Map<String, byte[]> bodies = new LinkedHashMap<>();
        for (JsonNode sample : root.required("samples")) {
            bodies.put(sample.required("sampleId").asText(),
                    HexFormat.of().parseHex(sample.required("bodyHex").asText()));
        }
        return Map.copyOf(bodies);
    }

    private static final class StepFailure extends RuntimeException {
        StepFailure(String message) {
            super(message);
        }
    }

    /** Narrow test adapter for stateful onboard control assertions; live runs fail closed without it. */
    public interface ScenarioControl {
        void advanceClock(Scenario.ScenarioStep step);
        void expectActiveSource(Scenario.ScenarioStep step);
        void changeWanUplink(Scenario.ScenarioStep step);
    }

    private enum InstanceRunState { NEW, RUNNING, FINISHED }
}
