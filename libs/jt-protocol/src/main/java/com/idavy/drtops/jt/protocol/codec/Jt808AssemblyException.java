package com.idavy.drtops.jt.protocol.codec;

public final class Jt808AssemblyException extends IllegalArgumentException {
    private final Jt808AssemblyError reason;

    public Jt808AssemblyException(Jt808AssemblyError reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Jt808AssemblyError reason() {
        return reason;
    }
}
