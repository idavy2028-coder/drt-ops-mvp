package com.idavy.drtops.jtsimulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
        private final List<String> events = new CopyOnWriteArrayList<>();
        private final AtomicInteger platformSerial = new AtomicInteger();

        FakePlatform() throws IOException {
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
