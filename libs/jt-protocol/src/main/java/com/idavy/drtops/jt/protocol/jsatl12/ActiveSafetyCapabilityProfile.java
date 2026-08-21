package com.idavy.drtops.jt.protocol.jsatl12;

import java.util.List;

/** Protocol capabilities frozen from the authenticated terminal registration. */
public record ActiveSafetyCapabilityProfile(String standardCode, List<String> modules) {
    public ActiveSafetyCapabilityProfile {
        modules = modules == null ? List.of() : List.copyOf(modules);
    }
}
