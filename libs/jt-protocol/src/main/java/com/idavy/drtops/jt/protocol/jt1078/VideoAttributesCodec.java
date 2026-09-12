package com.idavy.drtops.jt.protocol.jt1078;

import io.netty.buffer.ByteBuf;

/** JT/T 1078-2016 section 5.3.3: the fixed ten-byte attribute response. */
public final class VideoAttributesCodec {
    private VideoAttributesCodec() { }

    public static Attributes decode(ByteBuf body) {
        if (body == null || body.readableBytes() != 10) {
            throw new IllegalArgumentException("video attributes must contain ten bytes");
        }
        return new Attributes(body.readUnsignedByte(), body.readUnsignedByte(),
                body.readUnsignedByte(), body.readUnsignedByte(), body.readUnsignedShort(),
                body.readUnsignedByte(), body.readUnsignedByte(), body.readUnsignedByte(),
                body.readUnsignedByte());
    }

    public record Attributes(int audioEncoding, int audioChannels, int audioSampleRate,
            int audioSampleBits, int audioFrameLength, int audioOutput,
            int videoEncoding, int maxAudioChannels, int maxVideoChannels) {
        public Attributes {
            if (audioEncoding < 1 || audioEncoding > 28 || audioChannels < 0 || audioChannels > 255
                    || audioSampleRate < 0 || audioSampleRate > 3
                    || audioSampleBits < 0 || audioSampleBits > 2
                    || audioFrameLength < 1 || audioFrameLength > 65535
                    || audioOutput < 0 || audioOutput > 1
                    || videoEncoding < 98 || videoEncoding > 101
                    || maxAudioChannels < 0 || maxAudioChannels > 255
                    || maxVideoChannels < 0 || maxVideoChannels > 255) {
                throw new IllegalArgumentException("unsupported or invalid video attributes");
            }
        }
    }
}
