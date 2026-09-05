package com.idavy.drtops.jtsimulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.jt.protocol.codec.Jt808Frame;
import com.idavy.drtops.jt.protocol.codec.Jt808FrameDecoder;
import com.idavy.drtops.jt.protocol.codec.Jt808FrameEncoder;
import com.idavy.drtops.jt.protocol.codec.Jt808MessageHeader;
import com.idavy.drtops.jt.protocol.codec.ProtocolVersion;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Drives the scenario DSL against a minimal but real loopback platform: frames travel over TCP,
 * the platform decodes them with the production codec and answers with the production encoder.
 */
class ScenarioRunnerTest {
    private static final String IDENTITY = "000000000001";
    private static final String MASKED_ALIAS = "****0001";

    @Test
    void parsesConfiguredRegistrationIdentityFields() {
        Scenario scenario = Scenario.parse("""
                {
                  "scenario": "configured-registration-identity",
                  "terminal": {
                    "identity": "000000000002",
                    "manufacturerId": "MFG02",
                    "model": "MODEL-BETA",
                    "terminalCode": "SIM0002"
                  },
                  "steps": [{"action": "connect"}]
                }
                """);

        JsonNode terminal = new ObjectMapper().valueToTree(scenario.terminal());

        assertEquals("MFG02", terminal.path("manufacturerId").asText());
        assertEquals("MODEL-BETA", terminal.path("model").asText());
        assertEquals("SIM0002", terminal.path("terminalCode").asText());
    }

    @Test
    void emitsConfiguredRegistrationIdentityFieldsInReal0100Frame() throws Exception {
        try (FakePlatform platform = new FakePlatform()) {
            ScenarioReport report = ScenarioRunner.run(Scenario.parse("""
                    {
                      "scenario": "configured-registration-frame",
                      "terminal": {
                        "identity": "000000000002",
                        "manufacturerId": "MFG02",
                        "model": "MODEL-BETA",
                        "terminalCode": "SIM0002",
                        "plateNumber": "SIM-B02"
                      },
                      "steps": [
                        {"action": "connect"},
                        {"action": "register"},
                        {"action": "disconnect"}
                      ]
                    }
                    """), platform.endpoint());

            assertTrue(report.allPassed(), report::asText);
            assertEquals(
                    List.of(new RegistrationIdentity("MFG02", "MODEL-BETA", "SIM0002")),
                    platform.registrationIdentities());
        }
    }

    @Test
    void keepsLegacyRegistrationIdentityDefaultsWhenFieldsAreAbsent() throws Exception {
        try (FakePlatform platform = new FakePlatform()) {
            ScenarioReport report = ScenarioRunner.run(Scenario.parse("""
                    {
                      "scenario": "legacy-registration-defaults",
                      "terminal": {"identity": "000000000001"},
                      "steps": [
                        {"action": "connect"},
                        {"action": "register"},
                        {"action": "disconnect"}
                      ]
                    }
                    """), platform.endpoint());

            assertTrue(report.allPassed(), report::asText);
            assertEquals(
                    List.of(new RegistrationIdentity("SIMMF", "SIM-MODEL", "SIM0001")),
                    platform.registrationIdentities());
        }
    }

    @Test
    void runsFullProtocolJourneyAndMasksTerminalIdentity() throws Exception {
        try (FakePlatform platform = new FakePlatform()) {
            ScenarioReport report = ScenarioRunner.run(Scenario.parse("""
                    {
                      "scenario": "full-journey",
                      "terminal": {"identity": "000000000001", "protocolVersion": "JT808_2013",
                          "plateNumber": "SIMA01"},
                      "steps": [
                        {"action": "connect"},
                        {"action": "register"},
                        {"action": "authenticate"},
                        {"action": "heartbeat"},
                        {"action": "position"},
                        {"action": "activeSafetyAlarm", "sampleId": "S01"},
                        {"action": "attachmentInfo", "sampleId": "M01"},
                        {"action": "fileUploadCompleteNotification", "sampleId": "A06"},
                        {"action": "disconnect"}
                      ]
                    }
                    """), platform.endpoint());

            assertTrue(report.allPassed(), report::asText);
            assertEquals(
                    List.of(0x8100, 0x8001, 0x8001, 0x8001, 0x8001, 0x8001, 0x8001),
                    report.replies().stream().map(ScenarioReport.ReplyRecord::messageId).toList());
            assertTrue(report.replies().stream().allMatch(reply -> reply.result() == 0),
                    report::asText);
            assertEquals(
                    List.of(0x0100, 0x0102, 0x0002, 0x0200, 0x0200, 0x1210, 0x1206),
                    platform.receivedMessageIds());
            String text = report.asText();
            assertTrue(text.contains(MASKED_ALIAS), text);
            assertFalse(text.contains(IDENTITY), () -> "report must not leak the full identity: " + text);
        }
    }

    @Test
    void runsTwoIndependentConnectionsForOneSyntheticVehicleIdentifier() throws Exception {
        // Mutation caught: collapsing same-vehicle terminals into one connection or one identity.
        Scenario scenario = assertDoesNotThrow(() -> Scenario.parse("""
                {
                  "scenario": "dual-device-shared-vehicle",
                  "terminals": [
                    {"alias": "dispatch-01", "identity": "000000000101", "terminalCode": "DSP001",
                     "vehicleIdentifier": "VEHICLE-A", "roles": ["LOCATION_PRIMARY"]},
                    {"alias": "recorder-01", "identity": "000000000202", "terminalCode": "REC001",
                     "vehicleIdentifier": "VEHICLE-A", "roles": ["LOCATION_BACKUP", "ACTIVE_SAFETY", "VIDEO"]}
                  ],
                  "steps": [
                    {"action": "connect", "terminal": "dispatch-01"},
                    {"action": "register", "terminal": "dispatch-01"},
                    {"action": "authenticate", "terminal": "dispatch-01"},
                    {"action": "connect", "terminal": "recorder-01"},
                    {"action": "register", "terminal": "recorder-01"},
                    {"action": "authenticate", "terminal": "recorder-01"},
                    {"action": "disconnect", "terminal": "dispatch-01"},
                    {"action": "disconnect", "terminal": "recorder-01"}
                  ]
                }
                """));

        try (FakePlatform platform = new FakePlatform()) {
            ScenarioReport report = ScenarioRunner.run(scenario, platform.endpoint());

            assertTrue(report.allPassed(), report::asText);
            assertEquals(
                    List.of("DSP001", "REC001"),
                    platform.registrationIdentities().stream().map(RegistrationIdentity::terminalCode).toList());
            assertEquals(List.of("VEHICLE-A", "VEHICLE-A"), platform.registrationVehicleIdentifiers());
        }
    }

    @Test
    void dualDeviceFactoryBuildsAndRunsBothSyntheticDevicesWithoutReflection() throws Exception {
        // Mutation caught: returning an empty/default-incomplete factory scenario instead of two authenticated connections.
        Scenario.TerminalDefinition dispatch = terminal(
                "dispatch-01", "000000000111", "DSP111", "VEHICLE-A", "LOCATION_PRIMARY");
        Scenario.TerminalDefinition recorder = terminal(
                "recorder-01", "000000000222", "REC222", "VEHICLE-A", "LOCATION_BACKUP");
        Scenario scenario = assertDoesNotThrow(() -> Scenario.dualDevice(dispatch, recorder));

        try (FakePlatform platform = new FakePlatform()) {
            ScenarioRunner runner = new ScenarioRunner(platform.endpoint(), noOpControl());
            Scenario.ScenarioResult result = runner.run(scenario);

            assertEquals(2, result.connectionCount());
            assertEquals(Set.of("dispatch-01", "recorder-01"), result.authenticatedAliases());
            assertEquals(Set.of("VEHICLE-A"), result.vehicleIdentifiers());
        }
    }

    @Test
    void instanceRunnerRejectsSecondRunAfterSuccessBeforeExecutingSteps() throws Exception {
        // Mutation caught: reusing accumulated connections/results after a completed instance run.
        Scenario scenario = authenticatedDualDevice(
                terminal("dispatch-01", "000000000311", "DSP311", "VEHICLE-A", "LOCATION_PRIMARY"),
                terminal("recorder-01", "000000000322", "REC322", "VEHICLE-A", "LOCATION_BACKUP"));
        try (FakePlatform platform = new FakePlatform()) {
            ScenarioRunner runner = new ScenarioRunner(platform.endpoint(), noOpControl());
            assertEquals(2, runner.run(scenario).connectionCount());
            int registrations = platform.registrationIdentities().size();

            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> runner.run(scenario));
            assertTrue(failure.getMessage().contains("single-use"));
            assertEquals(registrations, platform.registrationIdentities().size());
        }
    }

    @Test
    void instanceRunnerRejectsSecondRunAfterFailureBeforeExecutingSteps() throws Exception {
        // Mutation caught: allowing a failed runner to retry with stale failed/connection state.
        Scenario failing = Scenario.multiDevice("failing", List.of(
                terminal("dispatch-01", "000000000411", "DSP411", "VEHICLE-A", "LOCATION_PRIMARY")),
                List.of(new Scenario.ScenarioStep(Scenario.MultiAction.REGISTER, "dispatch-01", null)));
        Scenario valid = authenticatedDualDevice(
                terminal("dispatch-02", "000000000412", "DSP412", "VEHICLE-A", "LOCATION_PRIMARY"),
                terminal("recorder-02", "000000000422", "REC422", "VEHICLE-A", "LOCATION_BACKUP"));
        try (FakePlatform platform = new FakePlatform()) {
            ScenarioRunner runner = new ScenarioRunner(platform.endpoint(), noOpControl());
            assertThrows(IllegalStateException.class, () -> runner.run(failing));
            int registrations = platform.registrationIdentities().size();

            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> runner.run(valid));
            assertTrue(failure.getMessage().contains("single-use"));
            assertEquals(registrations, platform.registrationIdentities().size());
        }
    }

    @Test
    void instanceRunnerRejectsConcurrentSecondRunWhileControlAdapterBlocksFirst() throws Exception {
        // Mutation caught: concurrent runs mutating one runner's scenario/connections/result collections.
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ScenarioControlBlocker control = new ScenarioControlBlocker(entered, release);
        Scenario blocked = Scenario.multiDevice("blocked", List.of(
                terminal("dispatch-01", "000000000511", "DSP511", "VEHICLE-A", "LOCATION_PRIMARY")),
                List.of(new Scenario.ScenarioStep(Scenario.MultiAction.ADVANCE_CLOCK, null, 1)));
        Scenario second = authenticatedDualDevice(
                terminal("dispatch-02", "000000000512", "DSP512", "VEHICLE-A", "LOCATION_PRIMARY"),
                terminal("recorder-02", "000000000522", "REC522", "VEHICLE-A", "LOCATION_BACKUP"));
        try (FakePlatform platform = new FakePlatform()) {
            ScenarioRunner runner = new ScenarioRunner(platform.endpoint(), control);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Scenario.ScenarioResult> first = executor.submit(() -> runner.run(blocked));
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                IllegalStateException failure = assertThrows(IllegalStateException.class, () -> runner.run(second));
                assertTrue(failure.getMessage().contains("single-use"));
                assertEquals(0, platform.registrationIdentities().size());
                release.countDown();
                assertEquals(1, first.get(2, TimeUnit.SECONDS).completedSteps().size());
            } finally {
                release.countDown();
                executor.shutdownNow();
            }
        }
    }

    @Test
    void terminalConnectionIdCannotSpanTwoTcpGenerations() throws Exception {
        // Mutation caught: allowing disconnect/connect on one terminal instance while retaining its connectionId.
        try (FakePlatform platform = new FakePlatform();
                SimulatedTerminal terminal = new SimulatedTerminal(
                        "000000000611", ProtocolVersion.JT808_2013, "VEHICLE-A")) {
            UUID connectionId = terminal.connectionId();
            terminal.connect(platform.endpoint());
            terminal.disconnect();

            assertThrows(IllegalStateException.class, () -> terminal.connect(platform.endpoint()));
            assertEquals(connectionId, terminal.connectionId());
        }
    }

    @Test
    void closeClearsCapturedRegistrationTokenAndQueuedReplyIdempotently() throws Exception {
        // Mutation caught: a closed terminal retaining authentication token or decoded platform reply state.
        try (FakePlatform platform = new FakePlatform();
                SimulatedTerminal terminal = new SimulatedTerminal(
                        "000000000711", ProtocolVersion.JT808_2013, "VEHICLE-A")) {
            terminal.connect(platform.endpoint());
            terminal.sendRegistration();
            await(() -> hasRegistrationTokenAndReply(terminal));

            long started = System.nanoTime();
            terminal.close();
            terminal.close();
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000);
            assertEquals(0, registrationToken(terminal).length);
            assertTrue(queuedReplies(terminal).isEmpty());
        }
    }

    @Test
    void delayedRegistrationReplyCannotRestoreTokenOrReplyAfterTerminalCloses() throws Exception {
        // Mutation caught: reader commits a decoded registration reply after CLOSED cleanup has completed.
        CountDownLatch registrationReplyEntered = new CountDownLatch(1);
        CountDownLatch releaseRegistrationReply = new CountDownLatch(1);
        try (FakePlatform platform = new FakePlatform(registrationReplyEntered, releaseRegistrationReply);
                SimulatedTerminal terminal = new SimulatedTerminal(
                        "000000000712", ProtocolVersion.JT808_2013, "VEHICLE-A")) {
            terminal.connect(platform.endpoint());
            terminal.sendRegistration();
            assertTrue(registrationReplyEntered.await(2, TimeUnit.SECONDS));
            terminal.close();
            releaseRegistrationReply.countDown();
            Thread.sleep(100);

            assertEquals(0, registrationToken(terminal).length);
            assertTrue(queuedReplies(terminal).isEmpty());
        } finally {
            releaseRegistrationReply.countDown();
        }
    }

    @Test
    void closeClearsReplyThatReaderWasAlreadyCommitting() throws Exception {
        // Mutation caught: close clears first, then an already-decoded reader reply writes back into the queue.
        CountDownLatch addEntered = new CountDownLatch(1);
        CountDownLatch releaseAdd = new CountDownLatch(1);
        CountDownLatch clearCompleted = new CountDownLatch(1);
        try (FakePlatform platform = new FakePlatform();
                SimulatedTerminal terminal = new SimulatedTerminal(
                        "000000000713", ProtocolVersion.JT808_2013, "VEHICLE-A")) {
            BlockingReplyQueue replies = new BlockingReplyQueue(addEntered, releaseAdd, clearCompleted);
            replaceReplies(terminal, replies);
            terminal.connect(platform.endpoint());
            terminal.sendRegistration();
            assertTrue(addEntered.await(2, TimeUnit.SECONDS));

            Thread closer = new Thread(terminal::close, "jt-sim-close-race");
            try {
                closer.start();
                awaitCloseOrderingEvent(clearCompleted, closer);
                releaseAdd.countDown();
                closer.join(2_000);
                assertFalse(closer.isAlive(), "close must complete within the bounded join");
            } finally {
                releaseAdd.countDown();
                if (closer.isAlive()) {
                    closer.interrupt();
                    closer.join(2_000);
                }
            }
            assertTrue(replies.isEmpty());
            assertEquals(0, registrationToken(terminal).length);
        }
    }

    @Test
    void acceptsControlPlaneStepsButFailsClosedWithoutATestAdapter() throws Exception {
        // Mutation caught: silently skipping a control-plane action during a live socket scenario.
        Scenario scenario = assertDoesNotThrow(() -> Scenario.parse("""
                {
                  "scenario": "control-plane-fails-closed",
                  "terminals": [
                    {"alias": "dispatch-01", "identity": "000000000101", "terminalCode": "DSP001",
                     "vehicleIdentifier": "VEHICLE-A", "roles": ["LOCATION_PRIMARY", "WAN_UPLINK"]}
                  ],
                  "steps": [
                    {"action": "advanceClock", "millis": 30000},
                    {"action": "expectActiveSource", "terminal": "dispatch-01"},
                    {"action": "changeWanUplink", "terminal": "dispatch-01"}
                  ]
                }
                """));

        try (FakePlatform platform = new FakePlatform()) {
            ScenarioReport report = ScenarioRunner.run(scenario, platform.endpoint());

            assertFalse(report.allPassed());
            assertTrue(report.asText().contains("control adapter"), report::asText);
        }
    }

    @Test
    void runsTimeoutTakeoverThreeReportFailbackAndWanChangeThroughTheNarrowControlAdapter() throws Exception {
        // Mutations caught: skipping control steps, failing back before report three, or reconnecting on WAN changes.
        Scenario scenario = assertDoesNotThrow(() -> Scenario.parse("""
                {
                  "scenario": "dual-device-failover-failback",
                  "terminals": [
                    {"alias": "dispatch-01", "identity": "000000000101", "terminalCode": "DSP001",
                     "vehicleIdentifier": "VEHICLE-A", "roles": ["LOCATION_PRIMARY"]},
                    {"alias": "recorder-01", "identity": "000000000202", "terminalCode": "REC001",
                     "vehicleIdentifier": "VEHICLE-A", "roles": ["LOCATION_BACKUP", "WAN_UPLINK"]}
                  ],
                  "steps": [
                    {"action": "connect", "terminal": "dispatch-01"},
                    {"action": "register", "terminal": "dispatch-01"},
                    {"action": "authenticate", "terminal": "dispatch-01"},
                    {"action": "connect", "terminal": "recorder-01"},
                    {"action": "register", "terminal": "recorder-01"},
                    {"action": "authenticate", "terminal": "recorder-01"},
                    {"action": "location", "terminal": "dispatch-01"},
                    {"action": "advanceClock", "millis": 30000},
                    {"action": "location", "terminal": "recorder-01"},
                    {"action": "expectActiveSource", "terminal": "recorder-01"},
                    {"action": "location", "terminal": "dispatch-01"},
                    {"action": "expectActiveSource", "terminal": "recorder-01"},
                    {"action": "location", "terminal": "dispatch-01"},
                    {"action": "expectActiveSource", "terminal": "recorder-01"},
                    {"action": "location", "terminal": "dispatch-01"},
                    {"action": "expectActiveSource", "terminal": "dispatch-01"},
                    {"action": "changeWanUplink", "terminal": "recorder-01"},
                    {"action": "disconnect", "terminal": "dispatch-01"},
                    {"action": "disconnect", "terminal": "recorder-01"}
                  ]
                }
                """));

        Class<?> controlType = assertDoesNotThrow(
                () -> Class.forName("com.idavy.drtops.jtsimulator.ScenarioRunner$ScenarioControl"));
        List<String> controls = new ArrayList<>();
        AtomicReference<Object> runnerReference = new AtomicReference<>();
        AtomicReference<UUID> recorderConnectionAtWanChange = new AtomicReference<>();
        Method connectionId = assertDoesNotThrow(
                () -> ScenarioRunner.class.getMethod("connectionId", String.class));
        Object control = Proxy.newProxyInstance(
                controlType.getClassLoader(), new Class<?>[] {controlType}, (proxy, method, arguments) -> {
                    controls.add(method.getName());
                    if ("changeWanUplink".equals(method.getName())) {
                        recorderConnectionAtWanChange.set((UUID) connectionId.invoke(
                                runnerReference.get(), "recorder-01"));
                    }
                    return null;
                });
        Constructor<?> constructor = assertDoesNotThrow(
                () -> ScenarioRunner.class.getConstructor(InetSocketAddress.class, controlType));
        Method run = assertDoesNotThrow(() -> ScenarioRunner.class.getMethod("run", Scenario.class));

        try (FakePlatform platform = new FakePlatform()) {
            Object runner = constructor.newInstance(platform.endpoint(), control);
            runnerReference.set(runner);
            Object result = run.invoke(runner, scenario);

            assertEquals(2, result.getClass().getMethod("connectionCount").invoke(result));
            assertEquals(
                    List.of("advanceClock", "expectActiveSource", "expectActiveSource",
                            "expectActiveSource", "expectActiveSource", "changeWanUplink"),
                    controls);
            assertEquals(recorderConnectionAtWanChange.get(), connectionId.invoke(runner, "recorder-01"));
        }
    }

    @Test
    void rejectsMultiDeviceDefinitionsThatReusePhysicalIdentityOrTerminalCode() {
        // Mutation caught: allowing two physical devices to share one terminal identity or terminal code.
        assertThrows(IllegalArgumentException.class, () -> Scenario.parse("""
                {"terminals":[
                  {"alias":"dispatch-01","identity":"000000000101","terminalCode":"DSP001","vehicleIdentifier":"VEHICLE-A"},
                  {"alias":"recorder-01","identity":"000000000101","terminalCode":"REC001","vehicleIdentifier":"VEHICLE-A"}],
                 "steps":[{"action":"connect","terminal":"dispatch-01"}]}
                """));
        assertThrows(IllegalArgumentException.class, () -> Scenario.parse("""
                {"terminals":[
                  {"alias":"dispatch-01","identity":"000000000101","terminalCode":"DSP001","vehicleIdentifier":"VEHICLE-A"},
                  {"alias":"recorder-01","identity":"000000000202","terminalCode":"DSP001","vehicleIdentifier":"VEHICLE-A"}],
                 "steps":[{"action":"connect","terminal":"dispatch-01"}]}
                """));
        IllegalArgumentException aliasFailure = assertThrows(IllegalArgumentException.class, () -> Scenario.parse("""
                {"terminals":[
                  {"alias":"same","identity":"000000000301","terminalCode":"DSP301","vehicleIdentifier":"VEHICLE-A"},
                  {"alias":"same","identity":"000000000302","terminalCode":"REC302","vehicleIdentifier":"VEHICLE-A"}],
                 "steps":[{"action":"connect","terminal":"same"}]}
                """));
        assertTrue(aliasFailure.getMessage().contains("alias"));
    }

    @Test
    void instanceMultiDeviceRunFailsClosedInsteadOfReturningPartialSuccess() throws Exception {
        // Mutation caught: swallowing a failed wire/control step and returning a partial ScenarioResult.
        Scenario scenario = Scenario.parse("""
                {"terminals":[{"alias":"dispatch-01","identity":"000000000101","terminalCode":"DSP001",
                                 "vehicleIdentifier":"VEHICLE-A"}],
                 "steps":[{"action":"register","terminal":"dispatch-01"}]}
                """);
        Class<?> controlType = Class.forName("com.idavy.drtops.jtsimulator.ScenarioRunner$ScenarioControl");
        Object control = Proxy.newProxyInstance(controlType.getClassLoader(), new Class<?>[] {controlType},
                (proxy, method, arguments) -> null);
        Constructor<?> constructor = ScenarioRunner.class.getConstructor(InetSocketAddress.class, controlType);
        Method run = ScenarioRunner.class.getMethod("run", Scenario.class);

        try (FakePlatform platform = new FakePlatform()) {
            Object runner = constructor.newInstance(platform.endpoint(), control);
            java.lang.reflect.InvocationTargetException failure = assertThrows(
                    java.lang.reflect.InvocationTargetException.class, () -> run.invoke(runner, scenario));
            assertTrue(failure.getCause() instanceof IllegalStateException, failure::toString);
        }
    }

    private static Scenario.TerminalDefinition terminal(
            String alias, String identity, String code, String vehicleIdentifier, String role) {
        return new Scenario.TerminalDefinition(alias, identity, code, vehicleIdentifier,
                ProtocolVersion.JT808_2013, Set.of("JT808_LOCATION"), Set.of(role));
    }

    private static byte[] registrationToken(SimulatedTerminal terminal) throws Exception {
        Field field = SimulatedTerminal.class.getDeclaredField("registrationToken");
        field.setAccessible(true);
        return (byte[]) field.get(terminal);
    }

    @SuppressWarnings("unchecked")
    private static java.util.concurrent.BlockingQueue<SimulatedTerminal.ReplyRecord> queuedReplies(
            SimulatedTerminal terminal) throws Exception {
        Field field = SimulatedTerminal.class.getDeclaredField("replies");
        field.setAccessible(true);
        return (java.util.concurrent.BlockingQueue<SimulatedTerminal.ReplyRecord>) field.get(terminal);
    }

    private static void replaceReplies(
            SimulatedTerminal terminal,
            java.util.concurrent.BlockingQueue<SimulatedTerminal.ReplyRecord> replies) throws Exception {
        Field field = SimulatedTerminal.class.getDeclaredField("replies");
        field.setAccessible(true);
        field.set(terminal, replies);
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "condition did not become true");
    }

    private static boolean hasRegistrationTokenAndReply(SimulatedTerminal terminal) {
        try {
            return registrationToken(terminal).length > 0 && !queuedReplies(terminal).isEmpty();
        } catch (Exception reflectionFailure) {
            throw new AssertionError(reflectionFailure);
        }
    }

    private static void awaitCloseOrderingEvent(CountDownLatch clearCompleted, Thread closer) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (clearCompleted.getCount() != 0 && closer.getState() != Thread.State.BLOCKED
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(clearCompleted.getCount() == 0 || closer.getState() == Thread.State.BLOCKED,
                "close must either clear in old ordering or block on the terminal monitor in new ordering");
    }

    private static final class BlockingReplyQueue
            extends java.util.concurrent.LinkedBlockingQueue<SimulatedTerminal.ReplyRecord> {
        private final CountDownLatch addEntered;
        private final CountDownLatch releaseAdd;
        private final CountDownLatch clearCompleted;

        private BlockingReplyQueue(
                CountDownLatch addEntered, CountDownLatch releaseAdd, CountDownLatch clearCompleted) {
            this.addEntered = addEntered;
            this.releaseAdd = releaseAdd;
            this.clearCompleted = clearCompleted;
        }

        @Override
        public boolean add(SimulatedTerminal.ReplyRecord reply) {
            addEntered.countDown();
            try {
                if (!releaseAdd.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("reader reply add was not released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            return super.add(reply);
        }

        @Override
        public void clear() {
            super.clear();
            clearCompleted.countDown();
        }
    }

    private static Scenario authenticatedDualDevice(
            Scenario.TerminalDefinition first, Scenario.TerminalDefinition second) {
        return Scenario.multiDevice("authenticated-dual", List.of(first, second), List.of(
                new Scenario.ScenarioStep(Scenario.MultiAction.CONNECT, first.alias(), null),
                new Scenario.ScenarioStep(Scenario.MultiAction.REGISTER, first.alias(), null),
                new Scenario.ScenarioStep(Scenario.MultiAction.AUTHENTICATE, first.alias(), null),
                new Scenario.ScenarioStep(Scenario.MultiAction.CONNECT, second.alias(), null),
                new Scenario.ScenarioStep(Scenario.MultiAction.REGISTER, second.alias(), null),
                new Scenario.ScenarioStep(Scenario.MultiAction.AUTHENTICATE, second.alias(), null)));
    }

    private static ScenarioRunner.ScenarioControl noOpControl() {
        return new ScenarioRunner.ScenarioControl() {
            @Override public void advanceClock(Scenario.ScenarioStep step) { }
            @Override public void expectActiveSource(Scenario.ScenarioStep step) { }
            @Override public void changeWanUplink(Scenario.ScenarioStep step) { }
        };
    }

    private static final class ScenarioControlBlocker implements ScenarioRunner.ScenarioControl {
        private final CountDownLatch entered;
        private final CountDownLatch release;

        private ScenarioControlBlocker(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        @Override public void advanceClock(Scenario.ScenarioStep step) {
            entered.countDown();
            try {
                if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("blocked control was not released");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
        @Override public void expectActiveSource(Scenario.ScenarioStep step) { }
        @Override public void changeWanUplink(Scenario.ScenarioStep step) { }
    }

    @Test
    void reassemblesHalfPacketBeforeReplying() throws Exception {
        try (FakePlatform platform = new FakePlatform()) {
            String heartbeat = encodeHex(0x0002, new byte[0], IDENTITY, 7);
            int split = heartbeat.length() / 4;
            split -= split % 2;
            ScenarioReport report = ScenarioRunner.run(Scenario.parse("""
                    {
                      "scenario": "half-packet",
                      "terminal": {"identity": "%s"},
                      "steps": [
                        {"action": "connect"},
                        {"action": "register"},
                        {"action": "authenticate"},
                        {"action": "sendRaw",
                          "hexParts": ["%s", "%s"],
                          "delayBetweenMillis": 60},
                        {"action": "expectReply", "messageId": "0x8001", "result": 0}
                      ]
                    }
                    """.formatted(IDENTITY, heartbeat.substring(0, split), heartbeat.substring(split))),
                    platform.endpoint());

            assertTrue(report.allPassed(), report::asText);
            assertEquals(List.of(0x0100, 0x0102, 0x0002), platform.receivedMessageIds());
        }
    }

    @Test
    void separatesStickyPacketsIntoDistinctReplies() throws Exception {
        try (FakePlatform platform = new FakePlatform()) {
            String sticky = encodeHex(0x0002, new byte[0], IDENTITY, 3)
                    + encodeHex(0x0002, new byte[0], IDENTITY, 4);
            ScenarioReport report = ScenarioRunner.run(Scenario.parse("""
                    {
                      "scenario": "sticky-packet",
                      "terminal": {"identity": "%s"},
                      "steps": [
                        {"action": "connect"},
                        {"action": "register"},
                        {"action": "authenticate"},
                        {"action": "sendRaw", "hexParts": ["%s"]},
                        {"action": "expectReply", "messageId": "0x8001", "result": 0, "requestSerialNo": 3},
                        {"action": "expectReply", "messageId": "0x8001", "result": 0, "requestSerialNo": 4}
                      ]
                    }
                    """.formatted(IDENTITY, sticky)), platform.endpoint());

            assertTrue(report.allPassed(), report::asText);
            assertEquals(2, report.replies().stream()
                    .filter(reply -> reply.requestMessageId() != null && reply.requestMessageId() == 0x0002)
                    .count());
        }
    }

    @Test
    void reportsChecksumFailureAsSilenceWithoutForgedSuccess() throws Exception {
        try (FakePlatform platform = new FakePlatform()) {
            String corrupted = corruptCheckByte(encodeHex(0x0002, new byte[0], IDENTITY, 9));
            ScenarioReport report = ScenarioRunner.run(Scenario.parse("""
                    {
                      "scenario": "checksum-error",
                      "terminal": {"identity": "%s"},
                      "steps": [
                        {"action": "connect"},
                        {"action": "register"},
                        {"action": "authenticate"},
                        {"action": "sendRaw", "hexParts": ["%s"]},
                        {"action": "expectSilence", "withinMillis": 400}
                      ]
                    }
                    """.formatted(IDENTITY, corrupted)), platform.endpoint());

            assertTrue(report.allPassed(), report::asText);
            assertEquals(List.of(0x0100, 0x0102), platform.receivedMessageIds());
            assertTrue(report.asText().contains("expectSilence"));
        }
    }

    @Test
    void supportsDuplicateLoginWithNamedConnections() throws Exception {
        try (FakePlatform platform = new FakePlatform()) {
            ScenarioReport report = ScenarioRunner.run(Scenario.parse("""
                    {
                      "scenario": "duplicate-login",
                      "terminal": {"identity": "%s"},
                      "steps": [
                        {"action": "connect", "as": "first"},
                        {"action": "register"},
                        {"action": "authenticate"},
                        {"action": "connect", "as": "second"},
                        {"action": "register"},
                        {"action": "authenticate"},
                        {"action": "heartbeat", "connection": "first"},
                        {"action": "disconnect", "connection": "first"},
                        {"action": "disconnect", "connection": "second"}
                      ]
                    }
                    """.formatted(IDENTITY)), platform.endpoint());

            assertTrue(report.allPassed(), report::asText);
            assertEquals(2, platform.receivedMessageIds().stream().filter(id -> id == 0x0100).count());
            assertEquals(2, platform.receivedMessageIds().stream().filter(id -> id == 0x0102).count());
            assertEquals(1, platform.receivedMessageIds().stream().filter(id -> id == 0x0002).count());
        }
    }

    @Test
    void executesRateBurstAndCountsEveryReply() throws Exception {
        try (FakePlatform platform = new FakePlatform()) {
            ScenarioReport report = ScenarioRunner.run(Scenario.parse("""
                    {
                      "scenario": "rate-burst",
                      "terminal": {"identity": "%s"},
                      "steps": [
                        {"action": "connect"},
                        {"action": "register"},
                        {"action": "authenticate"},
                        {"action": "burst", "message": "heartbeat", "count": 25, "intervalMillis": 5}
                      ]
                    }
                    """.formatted(IDENTITY)), platform.endpoint());

            assertTrue(report.allPassed(), report::asText);
            assertEquals(25, report.replies().stream()
                    .filter(reply -> reply.messageId() == 0x8001
                            && reply.requestMessageId() != null && reply.requestMessageId() == 0x0002
                            && reply.result() == 0)
                    .count());
        }
    }

    @Test
    void rejectsUnknownDslActionsInsteadOfSkippingThem() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Scenario.parse("""
                        {
                          "scenario": "bad-action",
                          "terminal": {"identity": "000000000001"},
                          "steps": [{"action": "teleport"}]
                        }
                        """));
        assertTrue(failure.getMessage().contains("teleport"));
    }

    @Test
    void rejectsScenariosWithoutTerminalOrSteps() {
        assertThrows(IllegalArgumentException.class, () -> Scenario.parse("{\"steps\": []}"));
        assertThrows(IllegalArgumentException.class,
                () -> Scenario.parse("{\"terminal\": {\"identity\": \"000000000001\"}, \"steps\": []}"));
    }

    private static String encodeHex(int messageId, byte[] body, String identity, int serial) {
        EmbeddedChannel encoder = new EmbeddedChannel(new Jt808FrameEncoder());
        Jt808Frame frame = new Jt808Frame(new Jt808MessageHeader(
                messageId, body.length, body.length, 0, false,
                ProtocolVersion.JT808_2013, 0, identity, serial, null, null),
                Unpooled.wrappedBuffer(body), (byte) 0);
        try {
            assertTrue(encoder.writeOutbound(frame));
            ByteBuf encoded = encoder.readOutbound();
            try {
                byte[] bytes = new byte[encoded.readableBytes()];
                encoded.readBytes(bytes);
                return HexFormat.of().formatHex(bytes);
            } finally {
                encoded.release();
            }
        } finally {
            if (frame.body().refCnt() > 0) {
                frame.body().release();
            }
            encoder.finishAndReleaseAll();
        }
    }

    /** Flips the check byte (or its escape payload) so the frame framing stays intact. */
    private static String corruptCheckByte(String frameHex) {
        byte[] bytes = HexFormat.of().parseHex(frameHex);
        bytes[bytes.length - 2] ^= 0x01;
        return HexFormat.of().formatHex(bytes);
    }

    /** Minimal loopback JT/T 808 platform: decodes with the production codec, replies by the book. */
    private static final class FakePlatform implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final ExecutorService threads = Executors.newCachedThreadPool();
        private final List<Integer> received = new CopyOnWriteArrayList<>();
        private final List<RegistrationIdentity> registrationIdentities = new CopyOnWriteArrayList<>();
        private final List<String> registrationVehicleIdentifiers = new CopyOnWriteArrayList<>();
        private final List<String> events = new CopyOnWriteArrayList<>();
        private final AtomicInteger platformSerial = new AtomicInteger();
        private final CountDownLatch registrationReplyEntered;
        private final CountDownLatch releaseRegistrationReply;

        FakePlatform() throws IOException {
            this(null, null);
        }

        FakePlatform(CountDownLatch registrationReplyEntered, CountDownLatch releaseRegistrationReply)
                throws IOException {
            this.registrationReplyEntered = registrationReplyEntered;
            this.releaseRegistrationReply = releaseRegistrationReply;
            serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            threads.submit(this::acceptLoop);
        }

        InetSocketAddress endpoint() {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), serverSocket.getLocalPort());
        }

        List<Integer> receivedMessageIds() {
            return List.copyOf(received);
        }

        List<RegistrationIdentity> registrationIdentities() {
            return List.copyOf(registrationIdentities);
        }

        List<String> registrationVehicleIdentifiers() {
            return List.copyOf(registrationVehicleIdentifiers);
        }

        List<String> events() {
            return List.copyOf(events);
        }

        private void acceptLoop() {
            while (!serverSocket.isClosed()) {
                try {
                    Socket socket = serverSocket.accept();
                    threads.submit(() -> serve(socket));
                } catch (IOException closed) {
                    return;
                }
            }
        }

        private void serve(Socket socket) {
            EmbeddedChannel decoder = new EmbeddedChannel(new Jt808FrameDecoder());
            EmbeddedChannel encoder = new EmbeddedChannel(new Jt808FrameEncoder());
            try (socket) {
                InputStream input = socket.getInputStream();
                byte[] chunk = new byte[1024];
                int read;
                while ((read = input.read(chunk)) >= 0) {
                    decoder.writeInbound(Unpooled.wrappedBuffer(chunk, 0, read));
                    Object decoded;
                    while ((decoded = decoder.readInbound()) != null) {
                        Jt808Frame frame = (Jt808Frame) decoded;
                        try {
                            received.add(frame.header().messageId());
                            if (frame.header().messageId() == 0x0100) {
                                registrationIdentities.add(readRegistrationIdentity(frame));
                                registrationVehicleIdentifiers.add(readVehicleIdentifier(frame));
                            }
                            reply(socket, encoder, frame);
                        } finally {
                            if (frame.body().refCnt() > 0) {
                                frame.body().release();
                            }
                        }
                    }
                }
                events.add("peer-closed");
            } catch (IOException | RuntimeException failure) {
                events.add("connection-ended:" + failure.getClass().getSimpleName());
            } finally {
                decoder.finishAndReleaseAll();
                encoder.finishAndReleaseAll();
            }
        }

        private RegistrationIdentity readRegistrationIdentity(Jt808Frame frame) {
            ByteBuf body = frame.body().duplicate();
            body.skipBytes(4);
            return new RegistrationIdentity(
                    readFixedAscii(body, 5),
                    readFixedAscii(body, 20),
                    readFixedAscii(body, 7));
        }

        private String readVehicleIdentifier(Jt808Frame frame) {
            ByteBuf body = frame.body().duplicate();
            body.skipBytes(4 + 5 + 20 + 7 + 1);
            byte[] bytes = new byte[body.readableBytes()];
            body.readBytes(bytes);
            return new String(bytes, Charset.forName("GBK"));
        }

        private String readFixedAscii(ByteBuf body, int length) {
            byte[] bytes = new byte[length];
            body.readBytes(bytes);
            int end = bytes.length;
            while (end > 0 && bytes[end - 1] == 0) {
                end--;
            }
            return new String(bytes, 0, end, StandardCharsets.US_ASCII);
        }

        private void reply(Socket socket, EmbeddedChannel encoder, Jt808Frame request) throws IOException {
            int messageId = request.header().messageId();
            ByteBuf body = Unpooled.buffer();
            int replyId;
            if (messageId == 0x0100) {
                replyId = 0x8100;
                body.writeShort(request.header().serialNumber()).writeByte(0);
                body.writeCharSequence("SIM-TOKEN", StandardCharsets.US_ASCII);
                delayRegistrationReplyIfRequested();
            } else {
                replyId = 0x8001;
                int result = switch (messageId) {
                    case 0x0102, 0x0002, 0x0200, 0x1210, 0x1206 -> 0;
                    default -> 3;
                };
                body.writeShort(request.header().serialNumber())
                        .writeShort(messageId)
                        .writeByte(result);
            }
            Jt808Frame reply = new Jt808Frame(new Jt808MessageHeader(
                    replyId, body.readableBytes(), body.readableBytes(), 0, false,
                    request.header().protocolVersion(), request.header().protocolVersionByte(),
                    request.header().terminalIdentity(),
                    platformSerial.updateAndGet(current -> current == 0xffff ? 1 : current + 1),
                    null, null), body, (byte) 0);
            synchronized (encoder) {
                if (!encoder.writeOutbound(reply)) {
                    throw new IOException("encoder refused the reply frame");
                }
                ByteBuf encoded = encoder.readOutbound();
                try {
                    byte[] bytes = new byte[encoded.readableBytes()];
                    encoded.readBytes(bytes);
                    socket.getOutputStream().write(bytes);
                    socket.getOutputStream().flush();
                } finally {
                    encoded.release();
                    if (reply.body().refCnt() > 0) {
                        reply.body().release();
                    }
                }
            }
        }

        private void delayRegistrationReplyIfRequested() throws IOException {
            if (registrationReplyEntered == null) {
                return;
            }
            registrationReplyEntered.countDown();
            try {
                if (!releaseRegistrationReply.await(2, TimeUnit.SECONDS)) {
                    throw new IOException("registration reply was not released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted delaying registration reply", interrupted);
            }
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
            threads.shutdownNow();
            try {
                threads.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private record RegistrationIdentity(String manufacturerId, String model, String terminalCode) {
    }
}
