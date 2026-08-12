package com.idavy.drtops.jt.protocol.codec;

import java.util.Objects;

public record Jt808MessageHeader(
        int messageId,
        int bodyProperties,
        int bodyLength,
        int encryptionType,
        boolean subpackaged,
        ProtocolVersion protocolVersion,
        int protocolVersionByte,
        String terminalIdentity,
        int serialNumber,
        Integer subpackageTotal,
        Integer subpackageIndex) {

    public Jt808MessageHeader {
        if (messageId < 0 || messageId > 0xffff) {
            throw new IllegalArgumentException("messageId must be an unsigned short");
        }
        if (bodyProperties < 0 || bodyProperties > 0xffff) {
            throw new IllegalArgumentException("bodyProperties must be an unsigned short");
        }
        if (bodyLength < 0) {
            throw new IllegalArgumentException("bodyLength must not be negative");
        }
        if (serialNumber < 0 || serialNumber > 0xffff) {
            throw new IllegalArgumentException("serialNumber must be an unsigned short");
        }
        Objects.requireNonNull(protocolVersion, "protocolVersion");
        Objects.requireNonNull(terminalIdentity, "terminalIdentity");
        if (subpackaged && (subpackageTotal == null || subpackageIndex == null)) {
            throw new IllegalArgumentException("subpackage metadata is required");
        }
        if (!subpackaged && (subpackageTotal != null || subpackageIndex != null)) {
            throw new IllegalArgumentException("subpackage metadata is only valid for subpackages");
        }
    }

    public Jt808MessageHeader reassembled(int reassembledBodyLength) {
        int properties = bodyProperties & ~0x23ff;
        return new Jt808MessageHeader(
                messageId, properties, reassembledBodyLength, encryptionType, false,
                protocolVersion, protocolVersionByte, terminalIdentity, serialNumber, null, null);
    }
}
