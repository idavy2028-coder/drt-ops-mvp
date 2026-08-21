package com.idavy.drtops.jt.protocol.codec;

import io.netty.handler.codec.DecoderException;

public final class Jt808CodecException extends DecoderException {
    private final Jt808DecodeError reason;

    public Jt808CodecException(Jt808DecodeError reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Jt808DecodeError reason() {
        return reason;
    }
}
