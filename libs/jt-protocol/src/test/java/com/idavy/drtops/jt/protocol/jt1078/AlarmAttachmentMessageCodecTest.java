package com.idavy.drtops.jt.protocol.jt1078;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.jt.protocol.codec.Jt808CodecException;
import com.idavy.drtops.jt.protocol.codec.Jt808DecodeError;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Fixed-vector tests for the T/JSATL12-2017 attachment signaling and the JT/T 1078 file control compatibility. */
class AlarmAttachmentMessageCodecTest {

    private final AlarmAttachmentMessageCodec codec = new AlarmAttachmentMessageCodec();
    private final Jt1078ControlModule module = new Jt1078ControlModule();
    private final JsonNode fixtures = readFixtures();

    @Test
    void fixturesAreDerivedSyntheticWithoutRealTerminalCapture() {
        assertEquals("DERIVED_SYNTHETIC", fixtures.get("fixtureStatus").asText());
        assertFalse(fixtures.get("deidentification").get("containsRealTerminalCapture").asBoolean());
    }

    @Test
    void decodesAndReencodesThe9208UploadCommand() {
        JsonNode sample = sample("A01");
        AlarmAttachmentMessageCodec.AttachmentUploadCommand decoded =
                codec.decodeUploadCommand(body(sample));

        JsonNode expected = sample.get("expected");
        assertEquals(expected.get("serverAddress").asText(), decoded.serverAddress());
        assertEquals(expected.get("tcpPort").asInt(), decoded.tcpPort());
        assertEquals(expected.get("udpPort").asInt(), decoded.udpPort());
        assertEquals(expected.get("alarmIdentifierAscii").asText(),
                new String(decoded.alarmIdentifier(), StandardCharsets.US_ASCII));
        assertEquals(expected.get("alarmNumberAscii").asText(),
                new String(decoded.alarmNumber(), StandardCharsets.US_ASCII));
        assertEquals(expected.get("reservedHex").asText(), HexFormat.of().formatHex(decoded.reserved()));
        assertEquals(sample.get("bodyHex").asText(), reencode(decoded));
    }

    @Test
    void decodesThe1210AttachmentInfoWithFileList() {
        JsonNode sample = sample("A02");
        AlarmAttachmentMessageCodec.AlarmAttachmentInfo decoded = codec.decodeAttachmentInfo(body(sample));

        JsonNode expected = sample.get("expected");
        assertEquals(expected.get("terminalIdAscii").asText(),
                new String(decoded.terminalId(), StandardCharsets.US_ASCII));
        assertEquals(expected.get("alarmIdentifierAscii").asText(),
                new String(decoded.alarmIdentifier(), StandardCharsets.US_ASCII));
        assertEquals(expected.get("alarmNumberAscii").asText(),
                new String(decoded.alarmNumber(), StandardCharsets.US_ASCII));
        assertEquals(expected.get("infoType").asInt(), decoded.infoType());
        List<AlarmAttachmentMessageCodec.AttachmentFile> files = decoded.attachments();
        assertEquals(2, files.size());
        JsonNode first = expected.get("attachments").get(0);
        assertEquals(first.get("fileName").asText(), files.get(0).fileName());
        assertEquals(first.get("fileSize").asLong(), files.get(0).fileSize());
        JsonNode second = expected.get("attachments").get(1);
        assertEquals(second.get("fileName").asText(), files.get(1).fileName());
        assertEquals(second.get("fileSize").asLong(), files.get(1).fileSize());
    }

    @Test
    void reencodesThe1210AttachmentInfoToTheProtocolEquivalentBytes() {
        JsonNode sample = sample("A02");
        AlarmAttachmentMessageCodec.AlarmAttachmentInfo decoded = codec.decodeAttachmentInfo(body(sample));
        ByteBuf target = Unpooled.buffer();
        codec.encodeAttachmentInfo(decoded, target);
        assertEquals(sample.get("bodyHex").asText(), hex(target));
    }

    @Test
    void decodesAndReencodesThe1211FileInfoUpload() {
        JsonNode sample = sample("A03");
        AlarmAttachmentMessageCodec.FileInfoUpload decoded = codec.decodeFileInfoUpload(body(sample));
        assertEquals(sample.get("expected").get("fileName").asText(), decoded.fileName());
        assertEquals(sample.get("expected").get("fileType").asInt(), decoded.fileType());
        assertEquals(sample.get("expected").get("fileSize").asLong(), decoded.fileSize());

        ByteBuf target = Unpooled.buffer();
        codec.encodeFileInfoUpload(decoded, target);
        assertEquals(sample.get("bodyHex").asText(), hex(target));
    }

    @Test
    void decodesAndReencodesThe1212FileUploadComplete() {
        JsonNode sample = sample("A04");
        AlarmAttachmentMessageCodec.FileUploadComplete decoded = codec.decodeFileUploadComplete(body(sample));
        assertEquals(sample.get("expected").get("fileName").asText(), decoded.fileName());
        assertEquals(sample.get("expected").get("fileType").asInt(), decoded.fileType());
        assertEquals(sample.get("expected").get("fileSize").asLong(), decoded.fileSize());

        ByteBuf target = Unpooled.buffer();
        codec.encodeFileUploadComplete(decoded, target);
        assertEquals(sample.get("bodyHex").asText(), hex(target));
    }

    @Test
    void encodesThe9212CompletionAckWithRetransmitPackages() {
        JsonNode sample = sample("A05");
        AlarmAttachmentMessageCodec.FileUploadCompleteAck decoded =
                codec.decodeFileUploadCompleteAck(body(sample));
        JsonNode expected = sample.get("expected");
        assertEquals(expected.get("fileName").asText(), decoded.fileName());
        assertEquals(expected.get("fileType").asInt(), decoded.fileType());
        assertEquals(expected.get("uploadResult").asInt(), decoded.uploadResult());
        assertEquals(1, decoded.retransmitPackages().size());
        assertEquals(expected.get("retransmitPackages").get(0).get("offset").asLong(),
                decoded.retransmitPackages().get(0).offset());
        assertEquals(expected.get("retransmitPackages").get(0).get("length").asLong(),
                decoded.retransmitPackages().get(0).length());

        ByteBuf target = Unpooled.buffer();
        codec.encodeFileUploadCompleteAck(decoded, target);
        assertEquals(sample.get("bodyHex").asText(), hex(target));
    }

    @Test
    void the1206NotificationIsUploadMetadataOnlyAndNeverAnAlarm() {
        JsonNode sample = sample("A06");
        Object decoded = module.decode(Jt1078ControlModule.MSG_FILE_UPLOAD_COMPLETE_NOTIFICATION, body(sample));

        assertEquals(AlarmAttachmentMessageCodec.FileUploadCompleteNotification.class, decoded.getClass());
        AlarmAttachmentMessageCodec.FileUploadCompleteNotification notification =
                (AlarmAttachmentMessageCodec.FileUploadCompleteNotification) decoded;
        assertEquals(sample.get("expected").get("responseSerialNo").asInt(), notification.responseSerialNo());
        assertEquals(sample.get("expected").get("result").asInt(), notification.result());
        // 元数据语义边界：0x1206 不携带任何可创建苏标报警的字段（无报警类型/模块/坐标/时间）。
        assertFalse(sample.get("expected").get("createsAlarm").asBoolean());
        assertEquals(0, AlarmAttachmentMessageCodec.FileUploadCompleteNotification.class
                .getRecordComponents().length - 2);
    }

    @Test
    void rejectsA1206BodyThatIsNotExactlyThreeBytes() {
        Jt808CodecException tooShort = assertThrows(Jt808CodecException.class,
                () -> codec.decodeFileUploadCompleteNotification(Unpooled.wrappedBuffer(new byte[]{0x00, 0x01})));
        assertEquals(Jt808DecodeError.LENGTH_MISMATCH, tooShort.reason());

        assertThrows(Jt808CodecException.class,
                () -> codec.decodeFileUploadCompleteNotification(
                        Unpooled.wrappedBuffer(new byte[]{0x00, 0x01, 0x00, 0x00})));
    }

    @Test
    void decodesThe1206FailureResultAsTerminalReportedUploadFailure() {
        JsonNode sample = sample("A06-FAIL");
        AlarmAttachmentMessageCodec.FileUploadCompleteNotification notification =
                codec.decodeFileUploadCompleteNotification(body(sample));
        assertEquals(2, notification.responseSerialNo());
        assertEquals(1, notification.result());
    }

    @Test
    void encodesAndDecodesThe9207UploadControl() {
        JsonNode sample = sample("A07");
        AlarmAttachmentMessageCodec.FileUploadControl control =
                new AlarmAttachmentMessageCodec.FileUploadControl(
                        sample.get("expected").get("responseSerialNo").asInt(),
                        sample.get("expected").get("control").asInt());
        ByteBuf target = Unpooled.buffer();
        codec.encodeFileUploadControl(control, target);
        assertEquals(sample.get("bodyHex").asText(), hex(target));

        AlarmAttachmentMessageCodec.FileUploadControl decoded = codec.decodeFileUploadControl(body(sample));
        assertEquals(control, decoded);
    }

    @Test
    void roundtripsThe9206UploadCommandWithoutPersistingCredentials() {
        JsonNode sample = sample("A08");
        JsonNode expected = sample.get("expected");
        AlarmAttachmentMessageCodec.FileUploadCommand decoded = codec.decodeFileUploadCommand(body(sample));
        assertEquals(expected.get("serverAddress").asText(), decoded.serverAddress());
        assertEquals(expected.get("serverPort").asInt(), decoded.serverPort());
        assertEquals(expected.get("username").asText(), decoded.username());
        assertEquals(expected.get("password").asText(), decoded.password());
        assertEquals(expected.get("uploadPath").asText(), decoded.uploadPath());
        assertEquals(expected.get("channel").asInt(), decoded.channel());
        assertEquals(expected.get("startTimeBcd").asText(), decoded.startTimeBcd());
        assertEquals(expected.get("endTimeBcd").asText(), decoded.endTimeBcd());
        assertEquals(expected.get("alarmFlagHex").asText(), HexFormat.of().formatHex(decoded.alarmFlag()));
        assertEquals(expected.get("mediaResourceType").asInt(), decoded.mediaResourceType());
        assertEquals(expected.get("streamType").asInt(), decoded.streamType());
        assertEquals(expected.get("storageLocation").asInt(), decoded.storageLocation());
        assertEquals(expected.get("taskExecutionCondition").asInt(), decoded.taskExecutionCondition());

        ByteBuf target = Unpooled.buffer();
        codec.encodeFileUploadCommand(decoded, target);
        assertEquals(sample.get("bodyHex").asText(), hex(target));
    }

    @Test
    void rejectsTruncatedAttachmentListsAndTrailingGarbage() {
        JsonNode sample = sample("A02");
        byte[] full = HexFormat.of().parseHex(sample.get("bodyHex").asText());
        // 附件数量声明为 2，但报文在第一个附件条目中途截断
        byte[] truncated = new byte[full.length - 10];
        System.arraycopy(full, 0, truncated, 0, truncated.length);
        assertThrows(Jt808CodecException.class,
                () -> codec.decodeAttachmentInfo(Unpooled.wrappedBuffer(truncated)));

        byte[] withTrailing = new byte[full.length + 1];
        System.arraycopy(full, 0, withTrailing, 0, full.length);
        assertThrows(Jt808CodecException.class,
                () -> codec.decodeAttachmentInfo(Unpooled.wrappedBuffer(withTrailing)));
    }

    @Test
    void rejectsMalformed9208Lengths() {
        JsonNode sample = sample("A01");
        byte[] full = HexFormat.of().parseHex(sample.get("bodyHex").asText());
        byte[] truncated = new byte[full.length - 1];
        System.arraycopy(full, 0, truncated, 0, truncated.length);
        assertThrows(Jt808CodecException.class,
                () -> codec.decodeUploadCommand(Unpooled.wrappedBuffer(truncated)));

        AlarmAttachmentMessageCodec.AttachmentUploadCommand decoded = codec.decodeUploadCommand(body(sample));
        assertThrows(IllegalArgumentException.class, () -> codec.encodeUploadCommand(
                new AlarmAttachmentMessageCodec.AttachmentUploadCommand(
                        decoded.serverAddress(), decoded.tcpPort(), decoded.udpPort(),
                        new byte[15], decoded.alarmNumber(), decoded.reserved()),
                Unpooled.buffer()));
    }

    @Test
    void rejectsStreamDataPacketsOnThe808Link() {
        JsonNode sample = sample("A09-STREAM-REJECT");
        ByteBuf streamPacket = Unpooled.wrappedBuffer(
                HexFormat.of().parseHex(sample.get("bodyHex").asText() + "00000000"));
        Jt808CodecException rejection = assertThrows(Jt808CodecException.class,
                () -> module.rejectStreamData(streamPacket));
        assertEquals(sample.get("expected").get("reasonCode").asText(), rejection.reason().name());

        // 普通 808 消息体不被误伤
        module.rejectStreamData(body(sample("A02")));
    }

    @Test
    void moduleDispatchesOnlyTheKnownAttachmentControlMessages() {
        assertTrue(module.isAttachmentControlMessage(0x9208));
        assertTrue(module.isAttachmentControlMessage(0x1210));
        assertTrue(module.isAttachmentControlMessage(0x1211));
        assertTrue(module.isAttachmentControlMessage(0x1212));
        assertTrue(module.isAttachmentControlMessage(0x9212));
        assertTrue(module.isAttachmentControlMessage(0x9206));
        assertTrue(module.isAttachmentControlMessage(0x9207));
        assertTrue(module.isAttachmentControlMessage(0x1206));
        assertFalse(module.isAttachmentControlMessage(0x0200));

        Object info = module.decode(0x1210, body(sample("A02")));
        assertEquals(AlarmAttachmentMessageCodec.AlarmAttachmentInfo.class, info.getClass());
        assertThrows(Jt808CodecException.class,
                () -> module.decode(0x9999, Unpooled.wrappedBuffer(new byte[]{0x00})));
    }

    private String reencode(AlarmAttachmentMessageCodec.AttachmentUploadCommand value) {
        ByteBuf target = Unpooled.buffer();
        codec.encodeUploadCommand(value, target);
        return hex(target);
    }

    private JsonNode sample(String sampleId) {
        for (JsonNode candidate : fixtures.get("samples")) {
            if (sampleId.equals(candidate.get("sampleId").asText())) {
                return candidate;
            }
        }
        throw new IllegalStateException("missing fixture sample " + sampleId);
    }

    private static ByteBuf body(JsonNode sample) {
        return Unpooled.wrappedBuffer(HexFormat.of().parseHex(sample.get("bodyHex").asText()));
    }

    private static String hex(ByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static JsonNode readFixtures() {
        try (InputStream stream = AlarmAttachmentMessageCodecTest.class
                .getResourceAsStream("/protocol-fixtures/attachment-control-fixtures.json")) {
            if (stream == null) {
                throw new IllegalStateException("attachment-control-fixtures.json is missing");
            }
            return new ObjectMapper().readTree(stream);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("cannot read attachment control fixtures", exception);
        }
    }
}
