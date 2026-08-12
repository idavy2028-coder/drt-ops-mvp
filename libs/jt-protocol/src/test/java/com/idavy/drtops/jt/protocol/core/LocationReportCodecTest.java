package com.idavy.drtops.jt.protocol.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.jt.protocol.codec.Jt808CodecException;
import com.idavy.drtops.jt.protocol.codec.Jt808DecodeError;
import com.idavy.drtops.jt.protocol.codec.Jt808MessageHeader;
import com.idavy.drtops.jt.protocol.codec.ProtocolVersion;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocationReportCodecTest {
    private static final LocationReportCodec CODEC = new LocationReportCodec();

    @Test
    void decodesTheAudited2013LocationBodyWithEitherVerifiedHeaderVersion() throws Exception {
        byte[] body = auditedLocationBody();

        for (ProtocolVersion version : ProtocolVersion.values()) {
            LocationReport report = CODEC.decode(header(version, body.length), Unpooled.wrappedBuffer(body));

            assertEquals(1, report.alarmBits());
            assertEquals(2, report.statusBits());
            assertEquals(new BigDecimal("132.444444"), report.longitude());
            assertEquals(new BigDecimal("12.222222"), report.latitude());
            assertEquals(40, report.altitudeMeters());
            assertEquals(new BigDecimal("6.0"), report.speedKph());
            assertEquals(0, report.directionDegrees());
            assertEquals(Instant.parse("2018-10-15T02:10:10Z"), report.locatedAt());
            assertArrayEquals(new byte[] {0, 0, 0, 100}, report.additionalItemLastValue(0x01));
            assertArrayEquals(new byte[] {0, 0x7d}, report.additionalItemLastValue(0x02));
        }
    }

    @Test
    void retainsARecognizedSatelliteCountAndUnknownAdditionalItems() throws Exception {
        byte[] body = append(append(auditedLocationBody(), 0x31, new byte[] {12}),
                0xe1, new byte[] {1, 2, 3});

        LocationReport report = CODEC.decode(header(ProtocolVersion.JT808_2013, body.length),
                Unpooled.wrappedBuffer(body));

        assertEquals(12, report.satelliteCount());
        assertArrayEquals(new byte[] {1, 2, 3}, report.additionalItemLastValue(0xe1));
    }

    @Test
    void appliesSouthAndWestStatusBitsToTheUnsignedWireCoordinates() throws Exception {
        byte[] body = auditedLocationBody();
        body[7] = 0x0e;

        LocationReport report = CODEC.decode(header(ProtocolVersion.JT808_2013, body.length),
                Unpooled.wrappedBuffer(body));

        assertEquals(14, report.statusBits());
        assertEquals(new BigDecimal("-132.444444"), report.longitude());
        assertEquals(new BigDecimal("-12.222222"), report.latitude());
    }

    @Test
    void rejectsShortBodiesInvalidBcdDatesAndTruncatedAdditionalItems() throws Exception {
        byte[] valid = auditedLocationBody();
        byte[] invalidBcd = valid.clone();
        invalidBcd[22] = 0x1a;
        byte[] invalidDate = valid.clone();
        invalidDate[23] = 0x19;
        byte[] truncatedAdditional = append(valid, 0xe1, new byte[] {1});
        truncatedAdditional[truncatedAdditional.length - 2] = 3;
        byte[] malformedSatelliteCount = append(valid, 0x31, new byte[] {1, 2});

        assertDecodeFailure(Arrays.copyOf(valid, 27), Jt808DecodeError.LENGTH_MISMATCH);
        assertDecodeFailure(invalidBcd, Jt808DecodeError.INVALID_BCD);
        assertDecodeFailure(invalidDate, Jt808DecodeError.INVALID_BCD);
        assertDecodeFailure(truncatedAdditional, Jt808DecodeError.LENGTH_MISMATCH);
        assertDecodeFailure(malformedSatelliteCount, Jt808DecodeError.LENGTH_MISMATCH);
    }

    @Test
    void doesNotConsumeOrReleaseTheCallerOwnedByteBuf() throws Exception {
        byte[] body = auditedLocationBody();
        ByteBuf input = Unpooled.wrappedBuffer(body);
        try {
            int readerIndex = input.readerIndex();

            CODEC.decode(header(ProtocolVersion.JT808_2013, body.length), input);

            assertEquals(readerIndex, input.readerIndex());
            assertEquals(1, input.refCnt());
        } finally {
            input.release();
        }
    }

    @Test
    void retainsAdditionalItemOrderAndDuplicatesWithoutExposingByteArrays() throws Exception {
        byte[] body = append(append(auditedLocationBody(), 0xe1, new byte[] {1}),
                0xe1, new byte[] {2});
        LocationReport report = CODEC.decode(header(ProtocolVersion.JT808_2013, body.length),
                Unpooled.wrappedBuffer(body));

        byte[] callerCopy = report.additionalItems().get(3).value();
        callerCopy[0] = 99;

        assertEquals(0xe1, report.additionalItems().get(2).id());
        assertEquals(0xe1, report.additionalItems().get(3).id());
        assertArrayEquals(new byte[] {2}, report.additionalItemLastValue(0xe1));
    }

    @Test
    void encodesTheDecodedBodyWithoutChangingAdditionalItemOrderOrDuplicates() throws Exception {
        byte[] body = append(append(auditedLocationBody(), 0xe1, new byte[] {1}),
                0xe1, new byte[] {2});
        LocationReport report = CODEC.decode(header(ProtocolVersion.JT808_2013, body.length),
                Unpooled.wrappedBuffer(body));
        ByteBuf encoded = Unpooled.buffer();
        try {
            CODEC.encode(report, encoded);

            byte[] actual = new byte[encoded.readableBytes()];
            encoded.readBytes(actual);
            assertArrayEquals(body, actual);
        } finally {
            encoded.release();
        }
    }

    private static void assertDecodeFailure(byte[] body, Jt808DecodeError expected) {
        ByteBuf input = Unpooled.wrappedBuffer(body);
        try {
            Jt808CodecException exception = assertThrows(Jt808CodecException.class,
                    () -> CODEC.decode(header(ProtocolVersion.JT808_2013, body.length), input));
            assertEquals(expected, exception.reason());
        } finally {
            input.release();
        }
    }

    private static Jt808MessageHeader header(ProtocolVersion version, int bodyLength) {
        return new Jt808MessageHeader(
                0x0200,
                bodyLength | (version == ProtocolVersion.JT808_2019 ? 0x4000 : 0),
                bodyLength,
                0,
                false,
                version,
                version == ProtocolVersion.JT808_2019 ? 1 : 0,
                version == ProtocolVersion.JT808_2019 ? "00000000123456789012" : "123456789012",
                126,
                null,
                null);
    }

    private static byte[] auditedLocationBody() throws Exception {
        try (InputStream stream = LocationReportCodecTest.class.getResourceAsStream(
                "/protocol-fixtures/jt808-core-fixtures.json")) {
            JsonNode fixtures = new ObjectMapper().readTree(stream);
            for (JsonNode fixture : fixtures) {
                if ("location-2013".equals(fixture.path("id").asText())) {
                    return java.util.HexFormat.of().parseHex(fixture.path("bodyHex").asText());
                }
            }
        }
        throw new AssertionError("audited location-2013 fixture is missing");
    }

    private static byte[] append(byte[] base, int type, byte[]... values) {
        int length = base.length;
        for (byte[] value : values) {
            length += 2 + value.length;
        }
        byte[] result = Arrays.copyOf(base, length);
        int cursor = base.length;
        for (byte[] value : values) {
            result[cursor++] = (byte) type;
            result[cursor++] = (byte) value.length;
            System.arraycopy(value, 0, result, cursor, value.length);
            cursor += value.length;
        }
        return result;
    }
}
