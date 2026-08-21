package com.idavy.drtops.jt.protocol.codec;

import io.netty.buffer.ByteBuf;

public interface Jt808MessageCodec<T> {
    int messageId();

    Class<T> payloadType();

    T decode(Jt808MessageHeader header, ByteBuf body);

    void encode(T value, ByteBuf target);
}
