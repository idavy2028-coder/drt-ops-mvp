package com.idavy.drtops.jtgateway.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.idavy.drtops.jt.protocol.codec.Jt808Frame;
import com.idavy.drtops.jt.protocol.codec.Jt808FrameEncoder;
import com.idavy.drtops.jt.protocol.codec.Jt808MessageHeader;
import com.idavy.drtops.jt.protocol.codec.ProtocolVersion;
import com.idavy.drtops.jt.protocol.jt1078.AlarmAttachmentMessageCodec;
import com.idavy.drtops.jtgateway.attachment.AttachmentCommandService;
import com.idavy.drtops.jtgateway.session.TerminalSessionRegistry;
import com.idavy.drtops.jtsimulator.Scenario;
import com.idavy.drtops.jtsimulator.ScenarioReport;
import com.idavy.drtops.jtsimulator.ScenarioRunner;
import com.idavy.drtops.jtsimulator.SimulatedTerminal;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Full-chain protocol verification: a simulated terminal drives the real gateway over TCP, the
 * gateway persists every business fact to its file-backed outbox before acknowledging, and the
 * dispatcher delivers envelopes to the operations-API HTTP boundary in lane order. The terminal
 * session registry is shared with the attachment control plane (Task 15 mandatory constraint).
 */
class GatewayOperationsFlowIntegrationTest {
    private static final String S01_IDENTIFIER_DIGEST =
            "27f961e72c2f2548dfa8d8baf5e20c9e7e1e6a70ba943d93dabd7bed223a2fc5";
    private static final int MSG_GENERAL_REPLY = 0x8001;
    private static final int MSG_ALARM_ATTACHMENT_INFO = 0x1210;
    private static final int MSG_FILE_UPLOAD_COMPLETE_NOTIFICATION = 0x1206;
    private static final int MSG_ATTACHMENT_UPLOAD_COMMAND = 0x9208;

    @TempDir
    Path tempDir;

    @Test
    void deliversFullJourneyWithAlarmsAndAttachmentMetadataAheadOfLocationBacklog() throws Exception {
        try (GatewayTestRig rig = new GatewayTestRig(tempDir, true)) {
            // Dedicated performance gates own production P95/P99; this functional E2E gives
            // Windows file-backed H2 scheduling room while still failing missing/error replies within 5s.
            ScenarioReport report = ScenarioRunner.run(Scenario.parse("""
                    {
                      "scenario": "gateway-full-journey",
                      "terminal": {"identity": "000000000001"},
                      "steps": [
                        {"action": "connect"},
                        {"action": "register"},
                        {"action": "authenticate"},
                        {"action": "burst", "message": "position", "count": 20, "intervalMillis": 2, "timeoutMillis": 5000},
                        {"action": "activeSafetyAlarm", "sampleId": "S01", "timeoutMillis": 5000},
                        {"action": "attachmentInfo", "sampleId": "M01", "timeoutMillis": 5000},
                        {"action": "fileUploadCompleteNotification", "sampleId": "A06", "timeoutMillis": 5000},
                        {"action": "disconnect"}
                      ]
                    }
                    """), rig.endpoint());
            assertTrue(report.allPassed(), report::asText);

            rig.pumpUntilDrained(100);
            assertEquals(0, rig.repository.pendingCount(), "outbox must be fully delivered");

            List<GatewayTestRig.ReceivedEnvelope> received = rig.api.received();
            assertEquals(24, received.size(),
                    "21 locations + 1 alarm + 2 attachment metadata envelopes, nothing more");
            assertEquals("LOCATION", received.get(0).kind(),
                    "the alarm dependency position is delivered first");
            assertEquals("ALARM", received.get(1).kind());
            assertEquals("ATTACHMENT_METADATA", received.get(2).kind());
            assertEquals("ATTACHMENT_METADATA", received.get(3).kind());
            assertTrue(received.subList(4, 24).stream().allMatch(e -> e.kind().equals("LOCATION")),
                    "the location backlog drains only after the high-priority lane");
            assertTrue(received.stream().allMatch(GatewayTestRig.ReceivedEnvelope::credentialPresented),
                    "every delivery must present the service credential");

            JsonNode alarm = received.get(1).payload(rig.objectMapper);
            assertEquals("FORWARD_COLLISION", alarm.required("alarmType").asText());
            assertEquals("START", alarm.required("state").asText());
            assertEquals(2, alarm.required("attachmentCount").asInt());
            assertEquals(S01_IDENTIFIER_DIGEST, alarm.required("terminalAlarmIdentifier").asText());

            JsonNode attachmentInfo = received.get(2).payload(rig.objectMapper);
            assertEquals(MSG_ALARM_ATTACHMENT_INFO, attachmentInfo.required("messageId").asInt());
            assertEquals(S01_IDENTIFIER_DIGEST,
                    attachmentInfo.required("alarmIdentifierDigest").asText(),
                    "attachment metadata must match the alarm through the same identifier digest");
            assertEquals(0, attachmentInfo.required("infoType").asInt());
            JsonNode files = attachmentInfo.required("files");
            assertEquals(2, files.size());
            assertEquals("00_64_6401_01_SYNTHETIC0001.jpg", files.get(0).required("fileName").asText());
            assertEquals(102400, files.get(0).required("fileSize").asLong());
            assertEquals("02_64_6401_02_SYNTHETIC0001.h264", files.get(1).required("fileName").asText());
            assertEquals(1048576, files.get(1).required("fileSize").asLong());
            assertFalse(attachmentInfo.toString().contains("0000000"),
                    "the raw 7-byte terminal identity must not enter the canonical payload");

            JsonNode uploadNotification = received.get(3).payload(rig.objectMapper);
            assertEquals(MSG_FILE_UPLOAD_COMPLETE_NOTIFICATION,
                    uploadNotification.required("messageId").asInt());
            assertEquals(1, uploadNotification.required("responseSerialNo").asInt());
            assertEquals(0, uploadNotification.required("uploadResult").asInt());
            assertEquals(0, uploadNotification.required("files").size(),
                    "0x1206 is file-upload-complete metadata only and never carries alarm files");
        }
    }

    @Test
    void controlPlaneAndNettyRuntimeShareOneSessionRegistry() throws Exception {
        try (GatewayTestRig rig = new GatewayTestRig(tempDir, true)) {
            SimulatedTerminal terminal = new SimulatedTerminal(
                    GatewayTestRig.TERMINAL_IDENTITY, ProtocolVersion.JT808_2013, "SIMA01");
            try {
                terminal.connect(rig.endpoint());
                terminal.sendRegistration();
                SimulatedTerminal.ReplyRecord registration = terminal.awaitReply(Duration.ofSeconds(2));
                assertNotNull(registration);
                assertEquals(0x8100, registration.messageId());
                assertEquals(0, registration.result());
                terminal.sendAuthentication();
                SimulatedTerminal.ReplyRecord authentication = terminal.awaitReply(Duration.ofSeconds(2));
                assertNotNull(authentication);
                assertEquals(MSG_GENERAL_REPLY, authentication.messageId());
                assertEquals(0, authentication.result());

                AttachmentCommandService.Command command = new AttachmentCommandService.Command(
                        rig.terminalId,
                        GatewayTestRig.TERMINAL_IDENTITY,
                        ProtocolVersion.JT808_2013,
                        0,
                        new AlarmAttachmentMessageCodec.AttachmentUploadCommand(
                                "192.0.2.10", 19001, 19002,
                                "ALARMID000000001".getBytes(StandardCharsets.US_ASCII),
                                "ALARMNO0000000000000000000000001".getBytes(StandardCharsets.US_ASCII),
                                new byte[16]));
                assertEquals(AttachmentCommandService.Result.TERMINAL_OFFLINE,
                        new AttachmentCommandService(new TerminalSessionRegistry())
                                .sendUploadCommand(command),
                        "a control plane with its own registry must stay fail-closed offline");
                assertEquals(AttachmentCommandService.Result.SENT,
                        rig.attachmentCommands.sendUploadCommand(command),
                        "the shared registry must expose the live Netty session to the control plane");

                SimulatedTerminal.ReplyRecord downlink = terminal.awaitReply(Duration.ofSeconds(2));
                assertNotNull(downlink, "the terminal must receive the 0x9208 downlink frame");
                assertEquals(MSG_ATTACHMENT_UPLOAD_COMMAND, downlink.messageId());
                AlarmAttachmentMessageCodec.AttachmentUploadCommand decoded =
                        new AlarmAttachmentMessageCodec().decodeUploadCommand(
                                Unpooled.wrappedBuffer(HexFormat.of().parseHex(downlink.bodyHex())));
                assertEquals("192.0.2.10", decoded.serverAddress());
                assertEquals(19001, decoded.tcpPort());
                assertEquals(19002, decoded.udpPort());
                assertEquals("ALARMID000000001",
                        new String(decoded.alarmIdentifier(), StandardCharsets.US_ASCII));
            } finally {
                terminal.close();
            }
        }
    }

    @Test
    void malformedAttachmentMetadataIsAuditedAndNeverRegistered() throws Exception {
        try (GatewayTestRig rig = new GatewayTestRig(tempDir, true)) {
            String truncated1210 = encodeHex(MSG_ALARM_ATTACHMENT_INFO,
                    new byte[] {0x30, 0x30}, GatewayTestRig.TERMINAL_IDENTITY, 21);
            ScenarioReport report = ScenarioRunner.run(Scenario.parse("""
                    {
                      "scenario": "malformed-attachment-info",
                      "terminal": {"identity": "000000000001"},
                      "steps": [
                        {"action": "connect"},
                        {"action": "register"},
                        {"action": "authenticate"},
                        {"action": "sendRaw", "hexParts": ["%s"]},
                        {"action": "expectReply", "messageId": "0x8001", "result": 0,
                          "requestSerialNo": 21},
                        {"action": "disconnect"}
                      ]
                    }
                    """.formatted(truncated1210)), rig.endpoint());
            assertTrue(report.allPassed(), report::asText);

            rig.pumpUntilDrained(100);
            assertEquals(0, rig.repository.pendingCount());
            assertEquals(0, rig.api.receivedOfKind("ATTACHMENT_METADATA").size(),
                    "malformed metadata must never be registered");
            List<GatewayTestRig.ReceivedEnvelope> audits = rig.api.receivedOfKind("PROTOCOL_AUDIT");
            assertEquals(1, audits.size());
            assertEquals("ACTIVE_SAFETY_ATTACHMENT_METADATA_REJECTED",
                    rig.objectMapper.readTree(audits.get(0).payloadJson()).required("reasonCode").asText());
        }
    }

    @Test
    void attachmentSignalingWithoutVideoRoleProducesOneSafeAudit() throws Exception {
        try (GatewayTestRig rig = new GatewayTestRig(tempDir, false)) {
            ScenarioReport report = ScenarioRunner.run(Scenario.parse("""
                    {
                      "scenario": "attachment-without-capability",
                      "terminal": {"identity": "000000000001"},
                      "steps": [
                        {"action": "connect"},
                        {"action": "register"},
                        {"action": "authenticate"},
                        {"action": "attachmentInfo", "sampleId": "M01"},
                        {"action": "disconnect"}
                      ]
                    }
                    """), rig.endpoint());
            assertTrue(report.allPassed(), report::asText);

            rig.pumpUntilDrained(100);
            assertEquals(0, rig.repository.pendingCount());
            assertEquals(0, rig.api.receivedOfKind("ATTACHMENT_METADATA").size());
            List<GatewayTestRig.ReceivedEnvelope> audits = rig.api.receivedOfKind("PROTOCOL_AUDIT");
            assertEquals(1, audits.size());
            assertEquals("DEVICE_ROLE_VIOLATION",
                    rig.objectMapper.readTree(audits.get(0).payloadJson()).required("reasonCode").asText());
        }
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
}
