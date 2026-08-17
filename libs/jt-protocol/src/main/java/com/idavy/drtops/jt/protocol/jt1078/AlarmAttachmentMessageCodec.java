package com.idavy.drtops.jt.protocol.jt1078;

import com.idavy.drtops.jt.protocol.codec.Jt808CodecException;
import com.idavy.drtops.jt.protocol.codec.Jt808DecodeError;
import io.netty.buffer.ByteBuf;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * T/JSATL12-2017 attachment signaling (0x9208/0x1210/0x1211/0x1212/0x9212, standard tables
 * 4-21/4-23..4-29) plus the JT/T 1078 file control compatibility (0x9206/0x9207/0x1206, layout
 * corroborated by two independent open-source implementations; the 1078 standard text was not
 * obtained). The 0x1206 notification is upload metadata only and never creates an alarm.
 * One-time credentials in 0x9206/0x9208 exist only in call memory and downlink encoding.
 */
public final class AlarmAttachmentMessageCodec {
    private static final Charset STRING_CHARSET = Charset.forName("GBK");
    private static final int ALARM_IDENTIFIER_LENGTH = 16;
    private static final int ALARM_NUMBER_LENGTH = 32;
    private static final int RESERVED_LENGTH = 16;
    private static final int TERMINAL_ID_LENGTH = 7;
    private static final int BCD_TIME_LENGTH = 6;
    private static final int ALARM_FLAG_LENGTH = 8;
    private static final int NOTIFICATION_BODY_LENGTH = 3;

    public AttachmentUploadCommand decodeUploadCommand(ByteBuf body) {
        requireReadable(body, 1);
        String address = readLengthPrefixedString(body);
        requireReadable(body, 4 + ALARM_IDENTIFIER_LENGTH + ALARM_NUMBER_LENGTH + RESERVED_LENGTH);
        int tcpPort = body.readUnsignedShort();
        int udpPort = body.readUnsignedShort();
        byte[] alarmIdentifier = readFixed(body, ALARM_IDENTIFIER_LENGTH);
        byte[] alarmNumber = readFixed(body, ALARM_NUMBER_LENGTH);
        byte[] reserved = readFixed(body, RESERVED_LENGTH);
        requireFullyConsumed(body);
        return new AttachmentUploadCommand(address, tcpPort, udpPort, alarmIdentifier, alarmNumber, reserved);
    }

    public void encodeUploadCommand(AttachmentUploadCommand value, ByteBuf target) {
        Objects.requireNonNull(value, "value");
        writeLengthPrefixedString(target, value.serverAddress());
        target.writeShort(value.tcpPort()).writeShort(value.udpPort());
        writeFixed(target, value.alarmIdentifier(), ALARM_IDENTIFIER_LENGTH, "alarmIdentifier");
        writeFixed(target, value.alarmNumber(), ALARM_NUMBER_LENGTH, "alarmNumber");
        writeFixed(target, value.reserved(), RESERVED_LENGTH, "reserved");
    }

    public AlarmAttachmentInfo decodeAttachmentInfo(ByteBuf body) {
        requireReadable(body, TERMINAL_ID_LENGTH + ALARM_IDENTIFIER_LENGTH + ALARM_NUMBER_LENGTH + 2);
        byte[] terminalId = readFixed(body, TERMINAL_ID_LENGTH);
        byte[] alarmIdentifier = readFixed(body, ALARM_IDENTIFIER_LENGTH);
        byte[] alarmNumber = readFixed(body, ALARM_NUMBER_LENGTH);
        int infoType = body.readUnsignedByte();
        int count = body.readUnsignedByte();
        List<AttachmentFile> files = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            requireReadable(body, 1);
            String fileName = readLengthPrefixedString(body);
            requireReadable(body, 4);
            files.add(new AttachmentFile(fileName, body.readUnsignedInt()));
        }
        requireFullyConsumed(body);
        return new AlarmAttachmentInfo(terminalId, alarmIdentifier, alarmNumber, infoType, files);
    }

    public void encodeAttachmentInfo(AlarmAttachmentInfo value, ByteBuf target) {
        Objects.requireNonNull(value, "value");
        writeFixed(target, value.terminalId(), TERMINAL_ID_LENGTH, "terminalId");
        writeFixed(target, value.alarmIdentifier(), ALARM_IDENTIFIER_LENGTH, "alarmIdentifier");
        writeFixed(target, value.alarmNumber(), ALARM_NUMBER_LENGTH, "alarmNumber");
        target.writeByte(value.infoType());
        List<AttachmentFile> files = value.attachments();
        if (files.size() > 0xff) {
            throw new IllegalArgumentException("attachment count must fit an unsigned byte");
        }
        target.writeByte(files.size());
        for (AttachmentFile file : files) {
            writeLengthPrefixedString(target, file.fileName());
            target.writeInt(unsignedIntRange(file.fileSize(), "fileSize"));
        }
    }

    public FileInfoUpload decodeFileInfoUpload(ByteBuf body) {
        FileUploadComplete complete = decodeFileNameTypeSize(body);
        return new FileInfoUpload(complete.fileName(), complete.fileType(), complete.fileSize());
    }

    public void encodeFileInfoUpload(FileInfoUpload value, ByteBuf target) {
        encodeFileNameTypeSize(value.fileName(), value.fileType(), value.fileSize(), target);
    }

    public FileUploadComplete decodeFileUploadComplete(ByteBuf body) {
        return decodeFileNameTypeSize(body);
    }

    public void encodeFileUploadComplete(FileUploadComplete value, ByteBuf target) {
        encodeFileNameTypeSize(value.fileName(), value.fileType(), value.fileSize(), target);
    }

    public FileUploadCompleteAck decodeFileUploadCompleteAck(ByteBuf body) {
        requireReadable(body, 1);
        String fileName = readLengthPrefixedString(body);
        requireReadable(body, 3);
        int fileType = body.readUnsignedByte();
        int uploadResult = body.readUnsignedByte();
        int retransmitCount = body.readUnsignedByte();
        List<RetransmitPackage> packages = new ArrayList<>(retransmitCount);
        for (int index = 0; index < retransmitCount; index++) {
            requireReadable(body, 8);
            packages.add(new RetransmitPackage(body.readUnsignedInt(), body.readUnsignedInt()));
        }
        requireFullyConsumed(body);
        return new FileUploadCompleteAck(fileName, fileType, uploadResult, packages);
    }

    public void encodeFileUploadCompleteAck(FileUploadCompleteAck value, ByteBuf target) {
        Objects.requireNonNull(value, "value");
        writeLengthPrefixedString(target, value.fileName());
        target.writeByte(value.fileType()).writeByte(value.uploadResult());
        List<RetransmitPackage> packages = value.retransmitPackages();
        if (packages.size() > 0xff) {
            throw new IllegalArgumentException("retransmit package count must fit an unsigned byte");
        }
        target.writeByte(packages.size());
        for (RetransmitPackage pack : packages) {
            target.writeInt(unsignedIntRange(pack.offset(), "offset"));
            target.writeInt(unsignedIntRange(pack.length(), "length"));
        }
    }

    /** JT/T 1078 0x1206: exactly 3 bytes (response serial WORD + result BYTE); metadata only. */
    public FileUploadCompleteNotification decodeFileUploadCompleteNotification(ByteBuf body) {
        requireExactLength(body, NOTIFICATION_BODY_LENGTH);
        return new FileUploadCompleteNotification(body.readUnsignedShort(), body.readUnsignedByte());
    }

    public void encodeFileUploadCompleteNotification(FileUploadCompleteNotification value, ByteBuf target) {
        Objects.requireNonNull(value, "value");
        target.writeShort(value.responseSerialNo()).writeByte(value.result());
    }

    /** JT/T 1078 0x9207: exactly 3 bytes (response serial WORD + control BYTE: 0 pause/1 resume/2 cancel). */
    public FileUploadControl decodeFileUploadControl(ByteBuf body) {
        requireExactLength(body, NOTIFICATION_BODY_LENGTH);
        int serial = body.readUnsignedShort();
        int control = body.readUnsignedByte();
        if (control > 2) {
            throw new Jt808CodecException(Jt808DecodeError.MALFORMED_MESSAGE_BODY);
        }
        return new FileUploadControl(serial, control);
    }

    public void encodeFileUploadControl(FileUploadControl value, ByteBuf target) {
        Objects.requireNonNull(value, "value");
        if (value.control() < 0 || value.control() > 2) {
            throw new IllegalArgumentException("upload control must be 0 (pause), 1 (resume) or 2 (cancel)");
        }
        target.writeShort(value.responseSerialNo()).writeByte(value.control());
    }

    public FileUploadCommand decodeFileUploadCommand(ByteBuf body) {
        requireReadable(body, 1);
        String address = readLengthPrefixedString(body);
        requireReadable(body, 3);
        int port = body.readUnsignedShort();
        String username = readLengthPrefixedString(body);
        String password = readLengthPrefixedString(body);
        String uploadPath = readLengthPrefixedString(body);
        requireReadable(body, 1 + 2 * BCD_TIME_LENGTH + ALARM_FLAG_LENGTH + 4);
        int channel = body.readUnsignedByte();
        String startTimeBcd = readBcdTime(body);
        String endTimeBcd = readBcdTime(body);
        byte[] alarmFlag = readFixed(body, ALARM_FLAG_LENGTH);
        int mediaResourceType = body.readUnsignedByte();
        int streamType = body.readUnsignedByte();
        int storageLocation = body.readUnsignedByte();
        int taskExecutionCondition = body.readUnsignedByte();
        requireFullyConsumed(body);
        return new FileUploadCommand(address, port, username, password, uploadPath, channel,
                startTimeBcd, endTimeBcd, alarmFlag, mediaResourceType, streamType,
                storageLocation, taskExecutionCondition);
    }

    public void encodeFileUploadCommand(FileUploadCommand value, ByteBuf target) {
        Objects.requireNonNull(value, "value");
        writeLengthPrefixedString(target, value.serverAddress());
        target.writeShort(value.serverPort());
        writeLengthPrefixedString(target, value.username());
        writeLengthPrefixedString(target, value.password());
        writeLengthPrefixedString(target, value.uploadPath());
        target.writeByte(value.channel());
        writeBcdTime(target, value.startTimeBcd());
        writeBcdTime(target, value.endTimeBcd());
        writeFixed(target, value.alarmFlag(), ALARM_FLAG_LENGTH, "alarmFlag");
        target.writeByte(value.mediaResourceType()).writeByte(value.streamType())
                .writeByte(value.storageLocation()).writeByte(value.taskExecutionCondition());
    }

    private FileUploadComplete decodeFileNameTypeSize(ByteBuf body) {
        requireReadable(body, 1);
        String fileName = readLengthPrefixedString(body);
        requireReadable(body, 5);
        int fileType = body.readUnsignedByte();
        long fileSize = body.readUnsignedInt();
        requireFullyConsumed(body);
        return new FileUploadComplete(fileName, fileType, fileSize);
    }

    private void encodeFileNameTypeSize(String fileName, int fileType, long fileSize, ByteBuf target) {
        writeLengthPrefixedString(target, fileName);
        target.writeByte(fileType);
        target.writeInt(unsignedIntRange(fileSize, "fileSize"));
    }

    private static String readLengthPrefixedString(ByteBuf body) {
        int length = body.readUnsignedByte();
        requireReadable(body, length);
        byte[] bytes = new byte[length];
        body.readBytes(bytes);
        return new String(bytes, STRING_CHARSET);
    }

    private static void writeLengthPrefixedString(ByteBuf target, String value) {
        Objects.requireNonNull(value, "string value");
        byte[] bytes = value.getBytes(STRING_CHARSET);
        if (bytes.length > 0xff) {
            throw new IllegalArgumentException("string is too long for the one-byte length prefix");
        }
        target.writeByte(bytes.length);
        target.writeBytes(bytes);
    }

    private static byte[] readFixed(ByteBuf body, int length) {
        byte[] value = new byte[length];
        body.readBytes(value);
        return value;
    }

    private static void writeFixed(ByteBuf target, byte[] value, int expectedLength, String field) {
        Objects.requireNonNull(value, field);
        if (value.length != expectedLength) {
            throw new IllegalArgumentException(field + " must be exactly " + expectedLength + " bytes");
        }
        target.writeBytes(value);
    }

    private static String readBcdTime(ByteBuf body) {
        byte[] bytes = readFixed(body, BCD_TIME_LENGTH);
        StringBuilder digits = new StringBuilder(BCD_TIME_LENGTH * 2);
        for (byte value : bytes) {
            int high = (value >> 4) & 0x0f;
            int low = value & 0x0f;
            if (high > 9 || low > 9) {
                throw new Jt808CodecException(Jt808DecodeError.INVALID_BCD);
            }
            digits.append(high).append(low);
        }
        return digits.toString();
    }

    private static void writeBcdTime(ByteBuf target, String digits) {
        Objects.requireNonNull(digits, "bcdTime");
        if (digits.length() != BCD_TIME_LENGTH * 2) {
            throw new IllegalArgumentException("BCD time must have " + (BCD_TIME_LENGTH * 2) + " digits");
        }
        for (int index = 0; index < digits.length(); index += 2) {
            int high = Character.digit(digits.charAt(index), 10);
            int low = Character.digit(digits.charAt(index + 1), 10);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("BCD time must contain only digits");
            }
            target.writeByte((high << 4) | low);
        }
    }

    private static void requireReadable(ByteBuf body, int bytes) {
        Objects.requireNonNull(body, "body");
        if (body.readableBytes() < bytes) {
            throw new Jt808CodecException(Jt808DecodeError.LENGTH_MISMATCH);
        }
    }

    private static void requireFullyConsumed(ByteBuf body) {
        if (body.isReadable()) {
            throw new Jt808CodecException(Jt808DecodeError.LENGTH_MISMATCH);
        }
    }

    private static void requireExactLength(ByteBuf body, int expected) {
        Objects.requireNonNull(body, "body");
        if (body.readableBytes() != expected) {
            throw new Jt808CodecException(Jt808DecodeError.LENGTH_MISMATCH);
        }
    }

    private static int unsignedIntRange(long value, String field) {
        if (value < 0 || value > 0xffff_ffffL) {
            throw new IllegalArgumentException(field + " must fit an unsigned dword");
        }
        return (int) value;
    }

    /** T/JSATL12-2017 表 4-21：报警附件上传指令（平台→终端）。 */
    public record AttachmentUploadCommand(String serverAddress, int tcpPort, int udpPort,
            byte[] alarmIdentifier, byte[] alarmNumber, byte[] reserved) { }

    /** T/JSATL12-2017 表 4-23/4-24：报警附件信息消息（终端→附件服务器）。 */
    public record AlarmAttachmentInfo(byte[] terminalId, byte[] alarmIdentifier, byte[] alarmNumber,
            int infoType, List<AttachmentFile> attachments) {
        public AlarmAttachmentInfo {
            attachments = List.copyOf(Objects.requireNonNull(attachments, "attachments"));
        }
    }

    public record AttachmentFile(String fileName, long fileSize) { }

    /** T/JSATL12-2017 表 4-25：文件信息上传。 */
    public record FileInfoUpload(String fileName, int fileType, long fileSize) { }

    /** T/JSATL12-2017 表 4-27：文件上传完成消息。 */
    public record FileUploadComplete(String fileName, int fileType, long fileSize) { }

    /** T/JSATL12-2017 表 4-28/4-29：文件上传完成消息应答（附件服务器→终端）。 */
    public record FileUploadCompleteAck(String fileName, int fileType, int uploadResult,
            List<RetransmitPackage> retransmitPackages) {
        public FileUploadCompleteAck {
            retransmitPackages = List.copyOf(Objects.requireNonNull(retransmitPackages, "retransmitPackages"));
        }
    }

    public record RetransmitPackage(long offset, long length) { }

    /** JT/T 1078 0x1206：文件上传完成通知；仅元数据，不产生报警。 */
    public record FileUploadCompleteNotification(int responseSerialNo, int result) { }

    /** JT/T 1078 0x9207：文件上传控制（0 暂停/1 继续/2 取消）。 */
    public record FileUploadControl(int responseSerialNo, int control) { }

    /** JT/T 1078 0x9206：文件上传指令；一次性凭证只存在于调用内存与下行编码。 */
    public record FileUploadCommand(String serverAddress, int serverPort, String username, String password,
            String uploadPath, int channel, String startTimeBcd, String endTimeBcd, byte[] alarmFlag,
            int mediaResourceType, int streamType, int storageLocation, int taskExecutionCondition) { }

    static String hexOf(byte[] value) {
        return HexFormat.of().formatHex(value);
    }
}
