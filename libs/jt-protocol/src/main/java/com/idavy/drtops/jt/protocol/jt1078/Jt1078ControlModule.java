package com.idavy.drtops.jt.protocol.jt1078;

import com.idavy.drtops.jt.protocol.codec.Jt808CodecException;
import com.idavy.drtops.jt.protocol.codec.Jt808DecodeError;
import io.netty.buffer.ByteBuf;
import java.util.Objects;
import java.util.Set;

/**
 * Facade for the T/JSATL12-2017 attachment signaling and the JT/T 1078 file control compatibility.
 * Only control signaling and metadata pass this module; binary stream data packets (frame header
 * 0x30316364, standard table 4-26) never enter the gateway or the operations API and are rejected.
 */
public final class Jt1078ControlModule {
    public static final int MSG_ATTACHMENT_UPLOAD_COMMAND = 0x9208;
    public static final int MSG_ALARM_ATTACHMENT_INFO = 0x1210;
    public static final int MSG_FILE_INFO_UPLOAD = 0x1211;
    public static final int MSG_FILE_UPLOAD_COMPLETE = 0x1212;
    public static final int MSG_FILE_UPLOAD_COMPLETE_ACK = 0x9212;
    public static final int MSG_FILE_UPLOAD_COMMAND = 0x9206;
    public static final int MSG_FILE_UPLOAD_CONTROL = 0x9207;
    public static final int MSG_FILE_UPLOAD_COMPLETE_NOTIFICATION = 0x1206;

    public static final int STREAM_FRAME_HEADER = 0x30316364;

    private static final Set<Integer> SUPPORTED = Set.of(
            MSG_ATTACHMENT_UPLOAD_COMMAND, MSG_ALARM_ATTACHMENT_INFO, MSG_FILE_INFO_UPLOAD,
            MSG_FILE_UPLOAD_COMPLETE, MSG_FILE_UPLOAD_COMPLETE_ACK, MSG_FILE_UPLOAD_COMMAND,
            MSG_FILE_UPLOAD_CONTROL, MSG_FILE_UPLOAD_COMPLETE_NOTIFICATION);

    private final AlarmAttachmentMessageCodec codec = new AlarmAttachmentMessageCodec();

    public boolean isAttachmentControlMessage(int messageId) {
        return SUPPORTED.contains(messageId);
    }

    /** Decodes a message body by message ID; unknown IDs are rejected. */
    public Object decode(int messageId, ByteBuf body) {
        Objects.requireNonNull(body, "body");
        return switch (messageId) {
            case MSG_ATTACHMENT_UPLOAD_COMMAND -> codec.decodeUploadCommand(body);
            case MSG_ALARM_ATTACHMENT_INFO -> codec.decodeAttachmentInfo(body);
            case MSG_FILE_INFO_UPLOAD -> codec.decodeFileInfoUpload(body);
            case MSG_FILE_UPLOAD_COMPLETE -> codec.decodeFileUploadComplete(body);
            case MSG_FILE_UPLOAD_COMPLETE_ACK -> codec.decodeFileUploadCompleteAck(body);
            case MSG_FILE_UPLOAD_COMMAND -> codec.decodeFileUploadCommand(body);
            case MSG_FILE_UPLOAD_CONTROL -> codec.decodeFileUploadControl(body);
            case MSG_FILE_UPLOAD_COMPLETE_NOTIFICATION -> codec.decodeFileUploadCompleteNotification(body);
            default -> throw new Jt808CodecException(Jt808DecodeError.MALFORMED_MESSAGE_BODY);
        };
    }

    /** Rejects raw stream data packets (0x30316364 frame header) that appear on the 808 link. */
    public void rejectStreamData(ByteBuf candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (candidate.readableBytes() >= 4
                && candidate.getInt(candidate.readerIndex()) == STREAM_FRAME_HEADER) {
            throw new Jt808CodecException(Jt808DecodeError.STREAM_DATA_REJECTED_ON_808);
        }
    }

    public AlarmAttachmentMessageCodec codec() {
        return codec;
    }
}
