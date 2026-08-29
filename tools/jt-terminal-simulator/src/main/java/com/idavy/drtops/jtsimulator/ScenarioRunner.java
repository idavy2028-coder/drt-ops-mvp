package com.idavy.drtops.jtsimulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    private final Scenario scenario;
    private final InetSocketAddress endpoint;
    private final Map<String, SimulatedTerminal> connections = new LinkedHashMap<>();
    private final List<ScenarioReport.StepRecord> steps = new ArrayList<>();
    private final List<ScenarioReport.ReplyRecord> replies = new ArrayList<>();
    private final Map<String, byte[]> fixtureBodies;
    private String currentConnection;
    private boolean failed;

    private ScenarioRunner(Scenario scenario, InetSocketAddress endpoint, Map<String, byte[]> fixtureBodies) {
        this.scenario = scenario;
        this.endpoint = endpoint;
        this.fixtureBodies = fixtureBodies;
    }

    public static ScenarioReport run(Scenario scenario, InetSocketAddress endpoint) {
        Objects.requireNonNull(scenario, "scenario");
        Objects.requireNonNull(endpoint, "endpoint");
        ScenarioRunner runner = new ScenarioRunner(scenario, endpoint, loadFixtures());
        return runner.execute();
    }

    private ScenarioReport execute() {
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
}
