package com.idavy.drtops.jtgateway.session;

public enum RegistrationRejection {
    NOT_PREPROVISIONED(1),
    TERMINAL_SUSPENDED(2),
    IDENTITY_MISMATCH(2),
    BINDING_INACTIVE(3),
    MALFORMED_REGISTRATION(2);

    private final int protocolResult;

    RegistrationRejection(int protocolResult) {
        this.protocolResult = protocolResult;
    }

    public int protocolResult() {
        return protocolResult;
    }
}
