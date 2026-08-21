package com.idavy.drtops.jtgateway.attachment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.idavy.drtops.jt.protocol.codec.Jt808Frame;
import com.idavy.drtops.jt.protocol.codec.ProtocolVersion;
import com.idavy.drtops.jt.protocol.jt1078.AlarmAttachmentMessageCodec;
import com.idavy.drtops.jt.protocol.jt1078.Jt1078ControlModule;
import com.idavy.drtops.jtgateway.session.TerminalSession;
import com.idavy.drtops.jtgateway.session.TerminalSessionRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Fixture identifiers are DERIVED_SYNTHETIC placeholders; no real terminal capture is involved.
 * The service is capability-ready: nothing calls it in production until the raw alarm identifier
 * retention ruling is revisited.
 */
class AttachmentCommandServiceTest {
    private static final UUID TERMINAL_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VEHICLE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String TERMINAL_NUMBER = "000000000000";
    private static final byte[] ALARM_IDENTIFIER = synthetic(16, 0x11);
    private static final byte[] ALARM_NUMBER = synthetic(32, 0x22);

    private final AlarmAttachmentMessageCodec codec = new AlarmAttachmentMessageCodec();

    @Test
    void sendsTheEncodedUploadCommandToAnAuthenticatedCapableTerminal() {
        TerminalSessionRegistry registry = new TerminalSessionRegistry();
        EmbeddedChannel channel = new EmbeddedChannel();
        claimSession(registry, channel, "T/JSATL12-2017");
        AttachmentCommandService service = new AttachmentCommandService(registry);

        assertEquals(AttachmentCommandService.Result.SENT, service.sendUploadCommand(command()));
        assertEquals(AttachmentCommandService.Result.SENT, service.sendUploadCommand(command()));

        Jt808Frame first = channel.readOutbound();
        Jt808Frame second = channel.readOutbound();
        try {
            assertEquals(Jt1078ControlModule.MSG_ATTACHMENT_UPLOAD_COMMAND, first.header().messageId());
            assertEquals(TERMINAL_NUMBER, first.header().terminalIdentity());
            assertEquals(ProtocolVersion.JT808_2013, first.header().protocolVersion());
            assertEquals(0, first.header().encryptionType());
            assertEquals(first.header().serialNumber() + 1, second.header().serialNumber());
            int expectedBodyLength = 1 + "127.0.0.1".length() + 2 + 2 + 16 + 32 + 16;
            assertEquals(expectedBodyLength, first.body().readableBytes());
            AlarmAttachmentMessageCodec.AttachmentUploadCommand decoded =
                    codec.decodeUploadCommand(first.body());
            assertEquals("127.0.0.1", decoded.serverAddress());
            assertEquals(7611, decoded.tcpPort());
            assertEquals(7612, decoded.udpPort());
            assertArrayEquals(ALARM_IDENTIFIER, decoded.alarmIdentifier());
            assertArrayEquals(ALARM_NUMBER, decoded.alarmNumber());
            assertArrayEquals(new byte[16], decoded.reserved());
        } finally {
            first.body().release();
            second.body().release();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void marksTheVersionedHeaderBitForProtocol2019() {
        TerminalSessionRegistry registry = new TerminalSessionRegistry();
        EmbeddedChannel channel = new EmbeddedChannel();
        claimSession(registry, channel, "T/JSATL12-2017");
        AttachmentCommandService service = new AttachmentCommandService(registry);

        assertEquals(AttachmentCommandService.Result.SENT, service.sendUploadCommand(
                command(ProtocolVersion.JT808_2019, 1)));

        Jt808Frame frame = channel.readOutbound();
        try {
            assertEquals(ProtocolVersion.JT808_2019, frame.header().protocolVersion());
            assertEquals(1, frame.header().protocolVersionByte());
            assertEquals(0x4000, frame.header().bodyProperties() & 0x4000);
        } finally {
            frame.body().release();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void refusesToSendWhenTheTerminalIsOfflineOrUnauthenticated() {
        TerminalSessionRegistry registry = new TerminalSessionRegistry();
        AttachmentCommandService service = new AttachmentCommandService(registry);

        assertEquals(AttachmentCommandService.Result.TERMINAL_OFFLINE, service.sendUploadCommand(command()));

        EmbeddedChannel channel = new EmbeddedChannel();
        TerminalSession session = new TerminalSession(channel, Instant.now());
        session.registrationAccepted(TERMINAL_ID, VEHICLE_ID, "WGS84", 1, TERMINAL_NUMBER,
                "T/JSATL12-2017", List.of("ADAS", "DMS"));
        registry.claim(session);
        assertEquals(AttachmentCommandService.Result.TERMINAL_OFFLINE, service.sendUploadCommand(command()));
        assertNull(channel.readOutbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void refusesToSendWhenTheTerminalIdentityDoesNotMatchTheSession() {
        TerminalSessionRegistry registry = new TerminalSessionRegistry();
        EmbeddedChannel channel = new EmbeddedChannel();
        claimSession(registry, channel, "T/JSATL12-2017");
        AttachmentCommandService service = new AttachmentCommandService(registry);

        AttachmentCommandService.Command spoofed = new AttachmentCommandService.Command(
                TERMINAL_ID, "999999999999", ProtocolVersion.JT808_2013, 0, uploadCommand());
        assertEquals(AttachmentCommandService.Result.IDENTITY_MISMATCH, service.sendUploadCommand(spoofed));
        assertNull(channel.readOutbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void refusesToSendWhenTheTerminalLacksTheActiveSafetyAttachmentCapability() {
        TerminalSessionRegistry registry = new TerminalSessionRegistry();
        EmbeddedChannel channel = new EmbeddedChannel();
        TerminalSession session = new TerminalSession(channel, Instant.now());
        session.registrationAccepted(TERMINAL_ID, VEHICLE_ID, "WGS84", 1, TERMINAL_NUMBER);
        session.authenticated(Instant.now());
        registry.claim(session);
        AttachmentCommandService service = new AttachmentCommandService(registry);

        assertEquals(AttachmentCommandService.Result.CAPABILITY_MISSING, service.sendUploadCommand(command()));
        assertNull(channel.readOutbound());
        channel.finishAndReleaseAll();
    }

    private void claimSession(TerminalSessionRegistry registry, EmbeddedChannel channel, String standard) {
        TerminalSession session = new TerminalSession(channel, Instant.now());
        session.registrationAccepted(TERMINAL_ID, VEHICLE_ID, "WGS84", 1, TERMINAL_NUMBER,
                standard, List.of("ADAS", "DMS"));
        session.authenticated(Instant.now());
        registry.claim(session);
    }

    private static AttachmentCommandService.Command command() {
        return command(ProtocolVersion.JT808_2013, 0);
    }

    private static AttachmentCommandService.Command command(ProtocolVersion version, int versionByte) {
        return new AttachmentCommandService.Command(TERMINAL_ID, TERMINAL_NUMBER, version, versionByte,
                uploadCommand());
    }

    private static AlarmAttachmentMessageCodec.AttachmentUploadCommand uploadCommand() {
        return new AlarmAttachmentMessageCodec.AttachmentUploadCommand(
                "127.0.0.1", 7611, 7612, ALARM_IDENTIFIER, ALARM_NUMBER, new byte[16]);
    }

    private static byte[] synthetic(int length, int seed) {
        byte[] value = new byte[length];
        for (int index = 0; index < length; index++) {
            value[index] = (byte) (seed + index);
        }
        return value;
    }
}
