package com.idavy.drtops.jt.protocol.core;

import com.idavy.drtops.jt.protocol.codec.Jt808MessageHeader;
import io.netty.buffer.ByteBuf;

/** Entry point for JT/T 808 common messages supported by the protocol library. */
public final class Jt808CoreModule {
    private final LocationReportCodec locationReportCodec;

    public Jt808CoreModule() {
        this(new LocationReportCodec());
    }

    public Jt808CoreModule(LocationReportCodec locationReportCodec) {
        this.locationReportCodec = java.util.Objects.requireNonNull(locationReportCodec, "locationReportCodec");
    }

    public LocationReport decodeLocation(Jt808MessageHeader header, ByteBuf body) {
        return locationReportCodec.decode(header, body);
    }
}
