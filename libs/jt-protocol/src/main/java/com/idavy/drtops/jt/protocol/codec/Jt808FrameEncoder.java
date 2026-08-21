package com.idavy.drtops.jt.protocol.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

public final class Jt808FrameEncoder extends MessageToByteEncoder<Jt808Frame> {
    private static final int DELIMITER = 0x7e;
    private static final int ESCAPE = 0x7d;

    @Override
    protected void encode(ChannelHandlerContext context, Jt808Frame frame, ByteBuf output) {
        Jt808MessageHeader header = frame.header();
        int bodyLength = frame.body().readableBytes();
        if (bodyLength > 0x03ff || bodyLength != header.bodyLength()) {
            throw new IllegalArgumentException("frame body length does not match the header");
        }
        validateHeaderFlags(header);

        ByteBuf unescaped = context.alloc().buffer(header.protocolVersion().baseHeaderLength()
                + (header.subpackaged() ? 4 : 0) + bodyLength + 1);
        try {
            unescaped.writeShort(header.messageId());
            int properties = (header.bodyProperties() & ~0x03ff) | bodyLength;
            unescaped.writeShort(properties);
            if (header.protocolVersion().versionedHeader()) {
                unescaped.writeByte(header.protocolVersionByte());
            }
            writeBcd(unescaped, header.terminalIdentity(),
                    header.protocolVersion().versionedHeader() ? 20 : 12);
            unescaped.writeShort(header.serialNumber());
            if (header.subpackaged()) {
                unescaped.writeShort(header.subpackageTotal());
                unescaped.writeShort(header.subpackageIndex());
            }
            unescaped.writeBytes(frame.body(), frame.body().readerIndex(), bodyLength);

            byte checksum = 0;
            for (int index = unescaped.readerIndex(); index < unescaped.writerIndex(); index++) {
                checksum ^= unescaped.getByte(index);
            }
            unescaped.writeByte(checksum);

            output.writeByte(DELIMITER);
            while (unescaped.isReadable()) {
                int value = unescaped.readUnsignedByte();
                if (value == ESCAPE) {
                    output.writeByte(ESCAPE).writeByte(0x01);
                } else if (value == DELIMITER) {
                    output.writeByte(ESCAPE).writeByte(0x02);
                } else {
                    output.writeByte(value);
                }
            }
            output.writeByte(DELIMITER);
        } finally {
            unescaped.release();
        }
    }

    private static void validateHeaderFlags(Jt808MessageHeader header) {
        boolean versionFlag = (header.bodyProperties() & 0x4000) != 0;
        boolean subpackageFlag = (header.bodyProperties() & 0x2000) != 0;
        if (versionFlag != header.protocolVersion().versionedHeader()
                || subpackageFlag != header.subpackaged()) {
            throw new IllegalArgumentException("header flags are inconsistent");
        }
    }

    private static void writeBcd(ByteBuf output, String digits, int expectedDigits) {
        if (digits.length() != expectedDigits) {
            throw new IllegalArgumentException("terminal identity has an invalid length");
        }
        for (int index = 0; index < digits.length(); index += 2) {
            int high = Character.digit(digits.charAt(index), 10);
            int low = Character.digit(digits.charAt(index + 1), 10);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("terminal identity is not BCD");
            }
            output.writeByte((high << 4) | low);
        }
    }
}
