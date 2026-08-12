package com.idavy.drtops.jt.protocol.codec;

public enum ProtocolVersion {
    JT808_2013(false, 12),
    JT808_2019(true, 17);

    private final boolean versionedHeader;
    private final int baseHeaderLength;

    ProtocolVersion(boolean versionedHeader, int baseHeaderLength) {
        this.versionedHeader = versionedHeader;
        this.baseHeaderLength = baseHeaderLength;
    }

    public boolean versionedHeader() {
        return versionedHeader;
    }

    public int baseHeaderLength() {
        return baseHeaderLength;
    }
}
