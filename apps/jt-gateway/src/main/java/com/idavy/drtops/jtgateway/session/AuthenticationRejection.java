package com.idavy.drtops.jtgateway.session;

public enum AuthenticationRejection {
    TOKEN_MISMATCH,
    TOKEN_VERSION_EXPIRED,
    TERMINAL_DISABLED,
    BINDING_INACTIVE,
    REGISTRATION_REQUIRED
}
