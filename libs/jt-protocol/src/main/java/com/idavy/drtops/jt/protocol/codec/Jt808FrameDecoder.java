package com.idavy.drtops.jt.protocol.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

public final class Jt808FrameDecoder extends ByteToMessageDecoder {
    public static final int DEFAULT_MAX_UNESCAPED_LENGTH = 4096;
    private static final int DELIMITER = 0x7e;
    private static final int ESCAPE = 0x7d;

    private final int maxUnescapedLength;

    public Jt808FrameDecoder() {
        this(DEFAULT_MAX_UNESCAPED_LENGTH);
    }

    public Jt808FrameDecoder(int maxUnescapedLength) {
        if (maxUnescapedLength < 1) {
            throw new IllegalArgumentException("maxUnescapedLength must be positive");
        }
        this.maxUnescapedLength = maxUnescapedLength;
    }

    @Override
    protected void decode(ChannelHandlerContext context, ByteBuf input, List<Object> output) {
        while (true) {
            int start = input.indexOf(input.readerIndex(), input.writerIndex(), (byte) DELIMITER);
            if (start < 0) {
                input.skipBytes(input.readableBytes());
                return;
            }
            if (start > input.readerIndex()) {
                input.readerIndex(start);
            }
            if (input.readableBytes() < 2) {
                return;
            }

            int end = input.indexOf(start + 1, input.writerIndex(), (byte) DELIMITER);
            if (end < 0) {
                if (input.readableBytes() - 1 > maxUnescapedLength * 2) {
                    input.skipBytes(input.readableBytes());
                    throw new Jt808CodecException(Jt808DecodeError.FRAME_TOO_LONG);
                }
                return;
            }
            if (end == start + 1) {
                input.readerIndex(end);
                continue;
            }

            int escapedLength = end - start - 1;
            if (escapedLength > maxUnescapedLength * 2) {
                input.readerIndex(end);
                throw new Jt808CodecException(Jt808DecodeError.FRAME_TOO_LONG);
            }

            input.readerIndex(end);
            byte[] unescaped = unescape(input, start + 1, escapedLength);
            if (unescaped.length > maxUnescapedLength) {
                throw new Jt808CodecException(Jt808DecodeError.FRAME_TOO_LONG);
            }
            output.add(parse(unescaped));
        }
    }

    private static byte[] unescape(ByteBuf input, int offset, int length) {
        byte[] result = new byte[length];
        int written = 0;
        int limit = offset + length;
        for (int index = offset; index < limit; index++) {
            int value = input.getUnsignedByte(index);
            if (value != ESCAPE) {
                result[written++] = (byte) value;
                continue;
            }
            if (++index >= limit) {
                throw new Jt808CodecException(Jt808DecodeError.INVALID_ESCAPE);
            }
            int escaped = input.getUnsignedByte(index);
            if (escaped == 0x01) {
                result[written++] = (byte) ESCAPE;
            } else if (escaped == 0x02) {
                result[written++] = (byte) DELIMITER;
            } else {
                throw new Jt808CodecException(Jt808DecodeError.INVALID_ESCAPE);
            }
        }
        return java.util.Arrays.copyOf(result, written);
    }

    private static Jt808Frame parse(byte[] bytes) {
        if (bytes.length < 13) {
            throw new Jt808CodecException(Jt808DecodeError.LENGTH_MISMATCH);
        }
        byte checksum = bytes[bytes.length - 1];
        byte computed = 0;
        for (int index = 0; index < bytes.length - 1; index++) {
            computed ^= bytes[index];
        }
        if (checksum != computed) {
            throw new Jt808CodecException(Jt808DecodeError.CHECKSUM_MISMATCH);
        }

        ByteBuf buffer = Unpooled.wrappedBuffer(bytes, 0, bytes.length - 1);
        try {
            int messageId = buffer.readUnsignedShort();
            int properties = buffer.readUnsignedShort();
            int bodyLength = properties & 0x03ff;
            int encryption = (properties >>> 10) & 0x07;
            boolean subpackaged = (properties & 0x2000) != 0;
            boolean versioned = (properties & 0x4000) != 0;
            ProtocolVersion version = versioned ? ProtocolVersion.JT808_2019 : ProtocolVersion.JT808_2013;
            int protocolVersionByte = versioned ? buffer.readUnsignedByte() : 0;
            int terminalBytes = versioned ? 10 : 6;
            if (buffer.readableBytes() < terminalBytes + 2 + (subpackaged ? 4 : 0)) {
                throw new Jt808CodecException(Jt808DecodeError.LENGTH_MISMATCH);
            }
            String terminalIdentity = readBcd(buffer, terminalBytes);
            int serial = buffer.readUnsignedShort();
            Integer total = null;
            Integer index = null;
            if (subpackaged) {
                total = buffer.readUnsignedShort();
                index = buffer.readUnsignedShort();
                if (total < 1 || index < 1 || index > total) {
                    throw new Jt808CodecException(Jt808DecodeError.INVALID_SUBPACKAGE);
                }
            }
            if (buffer.readableBytes() != bodyLength) {
                throw new Jt808CodecException(Jt808DecodeError.LENGTH_MISMATCH);
            }
            byte[] body = new byte[bodyLength];
            buffer.readBytes(body);
            Jt808MessageHeader header = new Jt808MessageHeader(
                    messageId, properties, bodyLength, encryption, subpackaged, version,
                    protocolVersionByte, terminalIdentity, serial, total, index);
            return new Jt808Frame(header, Unpooled.wrappedBuffer(body), checksum);
        } finally {
            buffer.release();
        }
    }

    private static String readBcd(ByteBuf buffer, int byteLength) {
        StringBuilder digits = new StringBuilder(byteLength * 2);
        for (int index = 0; index < byteLength; index++) {
            int value = buffer.readUnsignedByte();
            int high = (value >>> 4) & 0x0f;
            int low = value & 0x0f;
            if (high > 9 || low > 9) {
                throw new Jt808CodecException(Jt808DecodeError.INVALID_BCD);
            }
            digits.append((char) ('0' + high)).append((char) ('0' + low));
        }
        return digits.toString();
    }
}
