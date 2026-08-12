package com.idavy.drtops.jt.protocol.codec;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Jt808FrameCodecTest {

    private static final HexFormat HEX = HexFormat.of();

    @Test
    void decodesAuditedFixturesAndEncodesThemByteForByte() throws Exception {
        for (Fixture fixture : fixtures()) {
            Jt808Frame frame = decode(fixture.frameHex());

            assertEquals(Integer.parseInt(fixture.messageId(), 16), frame.header().messageId());
            assertEquals(ProtocolVersion.valueOf(fixture.version()), frame.header().protocolVersion());
            assertEquals(fixture.terminalIdentity(), frame.header().terminalIdentity());
            assertEquals(fixture.serialNumber(), frame.header().serialNumber());
            assertEquals(fixture.bodyHex(), HEX.formatHex(readableBytes(frame.body())));
            assertFalse(fixture.source().get("url").isBlank());
            assertFalse(fixture.source().get("provenance").isBlank());
            assertEquals(fixture.source().get("frameAsciiSha256"), sha256(fixture.frameHex()));

            EmbeddedChannel encoder = new EmbeddedChannel(new Jt808FrameEncoder());
            assertTrue(encoder.writeOutbound(frame));
            ByteBuf encoded = encoder.readOutbound();
            assertEquals(fixture.frameHex(), HEX.formatHex(readableBytes(encoded)));
            encoded.release();
            assertFalse(encoder.finish());
            frame.body().release();
        }
    }

    @Test
    void handlesHalfPacketsStickyPacketsAndConsecutiveDelimiters() throws Exception {
        String first = fixture("registration-2013").frameHex();
        String second = fixture("heartbeat-2019").frameHex();
        byte[] half = HEX.parseHex(first);
        EmbeddedChannel channel = new EmbeddedChannel(new Jt808FrameDecoder());

        assertFalse(channel.writeInbound(Unpooled.wrappedBuffer(half, 0, 9)));
        assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(half, 9, half.length - 9)));
        release(channel.readInbound());

        String sticky = first + second;
        assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(HEX.parseHex(sticky))));
        Jt808Frame decodedFirst = channel.readInbound();
        Jt808Frame decodedSecond = channel.readInbound();
        assertEquals(0x0100, decodedFirst.header().messageId());
        assertEquals(0x0002, decodedSecond.header().messageId());
        release(decodedFirst);
        release(decodedSecond);
        assertFalse(channel.finish());
    }

    @Test
    void rejectsInvalidEscapeChecksumLengthBcdAndOversizedFrameWithoutLeakingRawPacket() throws Exception {
        assertDecodeRejected("7e0002000001234567890100017d03127e", Jt808DecodeError.INVALID_ESCAPE);

        String heartbeat = fixture("heartbeat-2019").frameHex();
        String badChecksum = heartbeat.substring(0, heartbeat.length() - 4) + "00" + "7e";
        assertDecodeRejected(badChecksum, Jt808DecodeError.CHECKSUM_MISMATCH);

        String badLength = "7e0002400101000000001234567890120001c97e";
        assertDecodeRejected(badLength, Jt808DecodeError.LENGTH_MISMATCH);

        String badBcd = "7e00024000010000000012345678901a0001c07e";
        assertDecodeRejected(badBcd, Jt808DecodeError.INVALID_BCD);

        EmbeddedChannel limited = new EmbeddedChannel(new Jt808FrameDecoder(16));
        DecoderException failure = assertThrows(DecoderException.class,
                () -> limited.writeInbound(Unpooled.wrappedBuffer(HEX.parseHex(heartbeat))));
        assertTrue(rootMessage(failure).contains(Jt808DecodeError.FRAME_TOO_LONG.name()));
        assertFalse(rootMessage(failure).contains(heartbeat));
        limited.finishAndReleaseAll();
    }

    @Test
    void preservesSubpackageHeaderFields() {
        Jt808MessageHeader header = new Jt808MessageHeader(
                0x0704, 0x2002, 2, 0, true, ProtocolVersion.JT808_2013,
                0, "123456789012", 7, 3, 2);
        Jt808Frame frame = new Jt808Frame(header, Unpooled.wrappedBuffer(new byte[]{0x7e, 0x7d}), (byte) 0);
        EmbeddedChannel encoder = new EmbeddedChannel(new Jt808FrameEncoder());
        assertTrue(encoder.writeOutbound(frame));
        ByteBuf bytes = encoder.readOutbound();

        EmbeddedChannel decoder = new EmbeddedChannel(new Jt808FrameDecoder());
        assertTrue(decoder.writeInbound(bytes.retain()));
        Jt808Frame decoded = decoder.readInbound();
        assertEquals(3, decoded.header().subpackageTotal());
        assertEquals(2, decoded.header().subpackageIndex());
        assertArrayEquals(new byte[]{0x7e, 0x7d}, readableBytes(decoded.body()));

        bytes.release();
        frame.body().release();
        decoded.body().release();
        encoder.finishAndReleaseAll();
        decoder.finishAndReleaseAll();
    }

    private static void assertDecodeRejected(String frameHex, Jt808DecodeError reason) {
        EmbeddedChannel channel = new EmbeddedChannel(new Jt808FrameDecoder());
        DecoderException failure = assertThrows(DecoderException.class,
                () -> channel.writeInbound(Unpooled.wrappedBuffer(HEX.parseHex(frameHex))));
        assertTrue(rootMessage(failure).contains(reason.name()));
        assertFalse(rootMessage(failure).contains(frameHex));
        channel.finishAndReleaseAll();
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return String.valueOf(cursor.getMessage());
    }

    private static Jt808Frame decode(String frameHex) {
        EmbeddedChannel channel = new EmbeddedChannel(new Jt808FrameDecoder());
        assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(HEX.parseHex(frameHex))));
        Jt808Frame frame = channel.readInbound();
        assertFalse(channel.finish());
        return frame;
    }

    private static byte[] readableBytes(ByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }

    private static String sha256(String value) throws Exception {
        return HEX.formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.US_ASCII)));
    }

    private static void release(Jt808Frame frame) {
        frame.body().release();
    }

    private static Fixture fixture(String id) throws Exception {
        return fixtures().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    private static List<Fixture> fixtures() throws Exception {
        try (InputStream input = Jt808FrameCodecTest.class.getResourceAsStream(
                "/protocol-fixtures/jt808-core-fixtures.json")) {
            assertNotNull(input);
            return new ObjectMapper().readValue(input, new TypeReference<>() { });
        }
    }

    private record Fixture(
            String id,
            String messageName,
            String version,
            String messageId,
            String terminalIdentity,
            int serialNumber,
            String bodyHex,
            String frameHex,
            Map<String, String> source) {
    }
}
