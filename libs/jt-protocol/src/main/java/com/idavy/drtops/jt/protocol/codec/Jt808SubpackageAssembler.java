package com.idavy.drtops.jt.protocol.codec;

import io.netty.buffer.Unpooled;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class Jt808SubpackageAssembler {
    private final int maxAssemblies;
    private final int maxBufferedBytes;
    private final int maxSubpackages;
    private final Duration timeout;
    private final Clock clock;
    private final Map<AssemblyKey, Assembly> assemblies = new LinkedHashMap<>();
    private int bufferedBytes;

    public Jt808SubpackageAssembler(
            int maxAssemblies,
            int maxBufferedBytes,
            int maxSubpackages,
            Duration timeout,
            Clock clock) {
        if (maxAssemblies < 1 || maxBufferedBytes < 1 || maxSubpackages < 1) {
            throw new IllegalArgumentException("assembly limits must be positive");
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.maxAssemblies = maxAssemblies;
        this.maxBufferedBytes = maxBufferedBytes;
        this.maxSubpackages = maxSubpackages;
        this.timeout = timeout;
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    public synchronized Optional<Jt808Frame> accept(Jt808Frame fragment) {
        evictExpired();
        Jt808MessageHeader header = fragment.header();
        if (!header.subpackaged()) {
            throw new Jt808AssemblyException(Jt808AssemblyError.NOT_A_SUBPACKAGE);
        }
        int total = header.subpackageTotal();
        int index = header.subpackageIndex();
        if (total < 1 || total > maxSubpackages || index < 1 || index > total) {
            throw new Jt808AssemblyException(Jt808AssemblyError.INVALID_METADATA);
        }

        AssemblyKey key = new AssemblyKey(
                header.terminalIdentity(), header.messageId(), header.serialNumber());
        Assembly assembly = assemblies.get(key);
        if (assembly == null) {
            if (assemblies.size() >= maxAssemblies) {
                throw new Jt808AssemblyException(Jt808AssemblyError.TOO_MANY_ASSEMBLIES);
            }
            assembly = new Assembly(header, total, clock.instant());
            assemblies.put(key, assembly);
        } else if (assembly.total != total) {
            discard(key, assembly);
            throw new Jt808AssemblyException(Jt808AssemblyError.TOTAL_CONFLICT);
        }

        byte[] body = new byte[fragment.body().readableBytes()];
        fragment.body().getBytes(fragment.body().readerIndex(), body);
        byte[] existing = assembly.parts.get(index);
        if (existing != null) {
            if (Arrays.equals(existing, body)) {
                return Optional.empty();
            }
            discard(key, assembly);
            throw new Jt808AssemblyException(Jt808AssemblyError.DUPLICATE_CONFLICT);
        }
        if (bufferedBytes + body.length > maxBufferedBytes) {
            discard(key, assembly);
            throw new Jt808AssemblyException(Jt808AssemblyError.BUFFER_LIMIT_EXCEEDED);
        }

        assembly.parts.put(index, body);
        assembly.bufferedBytes += body.length;
        bufferedBytes += body.length;
        if (assembly.parts.size() != total) {
            return Optional.empty();
        }

        byte[] combined = new byte[assembly.bufferedBytes];
        int offset = 0;
        for (int packageIndex = 1; packageIndex <= total; packageIndex++) {
            byte[] part = assembly.parts.get(packageIndex);
            if (part == null) {
                return Optional.empty();
            }
            System.arraycopy(part, 0, combined, offset, part.length);
            offset += part.length;
        }
        discard(key, assembly);
        return Optional.of(new Jt808Frame(
                assembly.firstHeader.reassembled(combined.length),
                Unpooled.wrappedBuffer(combined),
                (byte) 0));
    }

    public synchronized int evictExpired() {
        Instant cutoff = clock.instant().minus(timeout);
        int evicted = 0;
        Iterator<Map.Entry<AssemblyKey, Assembly>> iterator = assemblies.entrySet().iterator();
        while (iterator.hasNext()) {
            Assembly assembly = iterator.next().getValue();
            if (!assembly.createdAt.isAfter(cutoff)) {
                bufferedBytes -= assembly.bufferedBytes;
                iterator.remove();
                evicted++;
            }
        }
        return evicted;
    }

    public synchronized int pendingAssemblyCount() {
        return assemblies.size();
    }

    public synchronized int bufferedBytes() {
        return bufferedBytes;
    }

    private void discard(AssemblyKey key, Assembly assembly) {
        if (assemblies.remove(key) != null) {
            bufferedBytes -= assembly.bufferedBytes;
        }
    }

    private record AssemblyKey(String terminalIdentity, int messageId, int serialNumber) {
    }

    private static final class Assembly {
        private final Jt808MessageHeader firstHeader;
        private final int total;
        private final Instant createdAt;
        private final Map<Integer, byte[]> parts = new HashMap<>();
        private int bufferedBytes;

        private Assembly(Jt808MessageHeader firstHeader, int total, Instant createdAt) {
            this.firstHeader = firstHeader;
            this.total = total;
            this.createdAt = createdAt;
        }
    }
}
