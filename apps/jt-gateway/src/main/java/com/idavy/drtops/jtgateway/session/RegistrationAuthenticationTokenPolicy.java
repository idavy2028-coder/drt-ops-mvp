package com.idavy.drtops.jtgateway.session;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Issues opaque registration authentication tokens without exposing compatibility models. */
public final class RegistrationAuthenticationTokenPolicy {
    private static final int DEFAULT_ENTROPY_BYTES = 32;
    private static final int COMPATIBILITY_ENTROPY_BYTES = 16;

    private final Set<String> compatibilityModels;

    private RegistrationAuthenticationTokenPolicy(Set<String> compatibilityModels) {
        this.compatibilityModels = Set.copyOf(compatibilityModels);
    }

    public static RegistrationAuthenticationTokenPolicy fromCommaSeparated(String configuredModels) {
        if (configuredModels == null || configuredModels.isBlank()) {
            return new RegistrationAuthenticationTokenPolicy(Set.of());
        }
        Set<String> models = Arrays.stream(configuredModels.split(",", -1))
                .map(String::trim)
                .filter(model -> !model.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        return new RegistrationAuthenticationTokenPolicy(models);
    }

    public byte[] issue(String model, SecureRandom random) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(random, "random");
        int entropyBytes = compatibilityModels.contains(model)
                ? COMPATIBILITY_ENTROPY_BYTES
                : DEFAULT_ENTROPY_BYTES;
        byte[] entropy = new byte[entropyBytes];
        random.nextBytes(entropy);
        try {
            return Base64.getUrlEncoder().withoutPadding().encode(entropy);
        } finally {
            Arrays.fill(entropy, (byte) 0);
        }
    }
}
