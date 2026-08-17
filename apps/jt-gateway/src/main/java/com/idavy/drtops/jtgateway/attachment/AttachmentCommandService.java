package com.idavy.drtops.jtgateway.attachment;

import com.idavy.drtops.jt.protocol.codec.Jt808Frame;
import com.idavy.drtops.jt.protocol.codec.Jt808MessageHeader;
import com.idavy.drtops.jt.protocol.codec.ProtocolVersion;
import com.idavy.drtops.jt.protocol.jt1078.AlarmAttachmentMessageCodec;
import com.idavy.drtops.jt.protocol.jt1078.Jt1078ControlModule;
import com.idavy.drtops.jtgateway.session.TerminalSession;
import com.idavy.drtops.jtgateway.session.TerminalSessionRegistry;
import com.idavy.drtops.jtgateway.session.TerminalSessionState;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Sends the T/JSATL12-2017 attachment upload command (0x9208) to an online, capable terminal.
 * Capability-ready only: per the 2026-08-17 ruling nothing calls this service in production until
 * the raw 16-byte terminal alarm identifier is retained; fixture identifiers drive the tests.
 * One-time upload targets exist only in the command payload and the downlink encoding: they are
 * never persisted or logged here.
 */
@Component
public class AttachmentCommandService {
    /** Attachment signaling is defined by T/JSATL12-2017; other capability profiles do not qualify. */
    private static final String ATTACHMENT_SIGNALING_STANDARD = "T/JSATL12-2017";

    private final TerminalSessionRegistry sessions;
    private final AlarmAttachmentMessageCodec codec = new AlarmAttachmentMessageCodec();
    private final ConcurrentMap<UUID, AtomicInteger> platformSerialNumbers = new ConcurrentHashMap<>();

    public AttachmentCommandService(TerminalSessionRegistry sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    /**
     * Encodes and sends the upload command; refuses offline, unauthenticated, identity-mismatched
     * or incapable terminals with an explicit reason instead of forging a success.
     */
    public Result sendUploadCommand(Command command) {
        Objects.requireNonNull(command, "command");
        Optional<TerminalSession> found = sessions.current(command.terminalId());
        if (found.isEmpty() || found.get().state() != TerminalSessionState.AUTHENTICATED) {
            return Result.TERMINAL_OFFLINE;
        }
        TerminalSession session = found.get();
        if (!session.matchesTerminalIdentity(command.terminalIdentity())) {
            return Result.IDENTITY_MISMATCH;
        }
        if (!ATTACHMENT_SIGNALING_STANDARD.equals(session.activeSafetyStandard())) {
            return Result.CAPABILITY_MISSING;
        }
        // Re-validate that the session is still the current authenticated one while writing, so a
        // concurrent session replacement cannot receive a command meant for the previous channel.
        return sessions.executeIfCurrent(session, () -> {
            ByteBuf body = Unpooled.buffer();
            codec.encodeUploadCommand(command.upload(), body);
            int bodyProperties = body.readableBytes();
            if (command.protocolVersion().versionedHeader()) {
                bodyProperties |= 0x4000;
            }
            Jt808MessageHeader header = new Jt808MessageHeader(
                    Jt1078ControlModule.MSG_ATTACHMENT_UPLOAD_COMMAND,
                    bodyProperties,
                    body.readableBytes(),
                    0,
                    false,
                    command.protocolVersion(),
                    command.protocolVersionByte(),
                    command.terminalIdentity(),
                    nextPlatformSerial(command.terminalId()),
                    null,
                    null);
            session.channel().writeAndFlush(new Jt808Frame(header, body, (byte) 0));
            return Result.SENT;
        }).orElse(Result.TERMINAL_OFFLINE);
    }

    private int nextPlatformSerial(UUID terminalId) {
        return platformSerialNumbers
                .computeIfAbsent(terminalId, ignored -> new AtomicInteger(0))
                .updateAndGet(current -> current == 0xffff ? 1 : current + 1);
    }

    /** A single attachment upload command request; the one-time target never leaves call memory. */
    public record Command(
            UUID terminalId,
            String terminalIdentity,
            ProtocolVersion protocolVersion,
            int protocolVersionByte,
            AlarmAttachmentMessageCodec.AttachmentUploadCommand upload) {
        public Command {
            Objects.requireNonNull(terminalId, "terminalId");
            Objects.requireNonNull(terminalIdentity, "terminalIdentity");
            Objects.requireNonNull(protocolVersion, "protocolVersion");
            Objects.requireNonNull(upload, "upload");
            if (protocolVersionByte < 0 || protocolVersionByte > 0xff) {
                throw new IllegalArgumentException("protocolVersionByte must fit an unsigned byte");
            }
        }
    }

    public enum Result {
        SENT,
        TERMINAL_OFFLINE,
        IDENTITY_MISMATCH,
        CAPABILITY_MISSING
    }
}
