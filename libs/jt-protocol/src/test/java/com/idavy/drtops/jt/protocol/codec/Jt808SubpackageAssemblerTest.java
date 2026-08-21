package com.idavy.drtops.jt.protocol.codec;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class Jt808SubpackageAssemblerTest {

    @Test
    void assemblesOutOfOrderAndIgnoresIdenticalDuplicate() {
        MutableClock clock = new MutableClock();
        Jt808SubpackageAssembler assembler = assembler(clock);

        assertTrue(assembler.accept(fragment("123456789012", 9, 3, 3, "C")).isEmpty());
        assertTrue(assembler.accept(fragment("123456789012", 9, 3, 3, "C")).isEmpty());
        assertTrue(assembler.accept(fragment("123456789012", 9, 3, 1, "A")).isEmpty());
        Optional<Jt808Frame> complete = assembler.accept(fragment("123456789012", 9, 3, 2, "B"));

        assertTrue(complete.isPresent());
        assertEquals("ABC", complete.get().body().toString(java.nio.charset.StandardCharsets.US_ASCII));
        assertFalse(complete.get().header().subpackaged());
        assertEquals(0, assembler.pendingAssemblyCount());
        assertEquals(0, assembler.bufferedBytes());
        complete.get().body().release();
    }

    @Test
    void rejectsConflictingDuplicateAndTotalThenDropsPoisonedAssembly() {
        Jt808SubpackageAssembler assembler = assembler(new MutableClock());
        assembler.accept(fragment("123456789012", 1, 2, 1, "A"));

        Jt808AssemblyException duplicate = assertThrows(Jt808AssemblyException.class,
                () -> assembler.accept(fragment("123456789012", 1, 2, 1, "X")));
        assertEquals(Jt808AssemblyError.DUPLICATE_CONFLICT, duplicate.reason());
        assertEquals(0, assembler.pendingAssemblyCount());

        assembler.accept(fragment("123456789012", 2, 2, 1, "A"));
        Jt808AssemblyException total = assertThrows(Jt808AssemblyException.class,
                () -> assembler.accept(fragment("123456789012", 2, 3, 2, "B")));
        assertEquals(Jt808AssemblyError.TOTAL_CONFLICT, total.reason());
        assertEquals(0, assembler.pendingAssemblyCount());
    }

    @Test
    void keepsMissingAssembliesUntilSixtySecondCleanup() {
        MutableClock clock = new MutableClock();
        Jt808SubpackageAssembler assembler = assembler(clock);
        assembler.accept(fragment("123456789012", 1, 2, 1, "A"));

        clock.advance(Duration.ofSeconds(59));
        assertEquals(0, assembler.evictExpired());
        clock.advance(Duration.ofSeconds(2));
        assertEquals(1, assembler.evictExpired());
        assertEquals(0, assembler.bufferedBytes());
    }

    @Test
    void enforcesAssemblyAndMemoryLimits() {
        MutableClock clock = new MutableClock();
        Jt808SubpackageAssembler assemblyLimited = new Jt808SubpackageAssembler(
                1, 100, 10, Duration.ofSeconds(60), clock);
        assemblyLimited.accept(fragment("123456789012", 1, 2, 1, "A"));
        Jt808AssemblyException assemblies = assertThrows(Jt808AssemblyException.class,
                () -> assemblyLimited.accept(fragment("123456789012", 2, 2, 1, "B")));
        assertEquals(Jt808AssemblyError.TOO_MANY_ASSEMBLIES, assemblies.reason());

        Jt808SubpackageAssembler memoryLimited = new Jt808SubpackageAssembler(
                10, 2, 10, Duration.ofSeconds(60), clock);
        memoryLimited.accept(fragment("123456789012", 3, 2, 1, "AB"));
        Jt808AssemblyException memory = assertThrows(Jt808AssemblyException.class,
                () -> memoryLimited.accept(fragment("123456789012", 3, 2, 2, "C")));
        assertEquals(Jt808AssemblyError.BUFFER_LIMIT_EXCEEDED, memory.reason());
        assertEquals(0, memoryLimited.bufferedBytes());
    }

    @Test
    void isolatesSameSerialAndMessageByTerminalIdentity() {
        Jt808SubpackageAssembler assembler = assembler(new MutableClock());
        assembler.accept(fragment("111111111111", 7, 2, 1, "A"));
        assembler.accept(fragment("222222222222", 7, 2, 1, "X"));

        Jt808Frame first = assembler.accept(fragment("111111111111", 7, 2, 2, "B")).orElseThrow();
        Jt808Frame second = assembler.accept(fragment("222222222222", 7, 2, 2, "Y")).orElseThrow();
        assertEquals("AB", first.body().toString(java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals("XY", second.body().toString(java.nio.charset.StandardCharsets.US_ASCII));
        first.body().release();
        second.body().release();
    }

    private static Jt808SubpackageAssembler assembler(Clock clock) {
        return new Jt808SubpackageAssembler(10, 1024, 10, Duration.ofSeconds(60), clock);
    }

    private static Jt808Frame fragment(String terminal, int serial, int total, int index, String body) {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        Jt808MessageHeader header = new Jt808MessageHeader(
                0x0704, 0x2000 | bytes.length, bytes.length, 0, true,
                ProtocolVersion.JT808_2013, 0, terminal, serial, total, index);
        return new Jt808Frame(header, Unpooled.wrappedBuffer(bytes), (byte) 0);
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-08-12T00:00:00Z");

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
