package com.idavy.drtops.jt.protocol.codec;

import io.netty.buffer.ByteBuf;

import java.util.Objects;

public record Jt808Frame(Jt808MessageHeader header, ByteBuf body, byte checksum) {
    public Jt808Frame {
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(body, "body");
    }
}
