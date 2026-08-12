package com.idavy.drtops.jtgateway.session;

import com.idavy.drtops.jt.protocol.codec.ProtocolVersion;

import java.util.Objects;

public record TerminalRegistrationIdentity(
        ProtocolVersion protocolVersion,
        String terminalNumber,
        String manufacturerId,
        String model,
        String terminalCode,
        String vehicleIdentifier) {
    public TerminalRegistrationIdentity {
        Objects.requireNonNull(protocolVersion, "protocolVersion");
        Objects.requireNonNull(terminalNumber, "terminalNumber");
        Objects.requireNonNull(manufacturerId, "manufacturerId");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(terminalCode, "terminalCode");
        Objects.requireNonNull(vehicleIdentifier, "vehicleIdentifier");
    }
}
