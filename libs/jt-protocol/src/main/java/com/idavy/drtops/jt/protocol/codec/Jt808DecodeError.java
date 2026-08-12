package com.idavy.drtops.jt.protocol.codec;

public enum Jt808DecodeError {
    FRAME_TOO_LONG,
    INVALID_ESCAPE,
    CHECKSUM_MISMATCH,
    LENGTH_MISMATCH,
    INVALID_BCD,
    INVALID_SUBPACKAGE
}
