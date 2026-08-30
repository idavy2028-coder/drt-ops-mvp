package com.idavy.drtops.jtgateway.session;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable authorization snapshot for one physical terminal connection. */
public record TerminalSessionContext(
        UUID terminalId,
        UUID onboardSystemId,
        UUID vehicleId,
        Set<String> roles,
        String sourceCoordinateSystem,
        String activeSafetyStandard,
        List<String> activeSafetyModules,
        int tokenVersion) {

    private static final Set<String> KNOWN_ROLES = Set.of(
            "DISPATCH",
            "LOCATION_PRIMARY",
            "LOCATION_BACKUP",
            "ACTIVE_SAFETY",
            "VIDEO",
            "WAN_UPLINK");

    public TerminalSessionContext {
        terminalId = Objects.requireNonNull(terminalId, "terminalId");
        onboardSystemId = Objects.requireNonNull(onboardSystemId, "onboardSystemId");
        vehicleId = Objects.requireNonNull(vehicleId, "vehicleId");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        if (roles.isEmpty() || roles.stream().anyMatch(role -> !KNOWN_ROLES.contains(role))) {
            throw new IllegalArgumentException("roles contain an unsupported value");
        }
        if (!"WGS84".equals(sourceCoordinateSystem)
                && !"GCJ02".equals(sourceCoordinateSystem)) {
            throw new IllegalArgumentException(
                    "sourceCoordinateSystem must be WGS84 or GCJ02");
        }
        if (activeSafetyStandard != null && activeSafetyStandard.isBlank()) {
            throw new IllegalArgumentException("activeSafetyStandard must not be blank");
        }
        activeSafetyModules = List.copyOf(Objects.requireNonNull(
                activeSafetyModules, "activeSafetyModules"));
        if (activeSafetyModules.stream().anyMatch(
                module -> module == null || module.isBlank())) {
            throw new IllegalArgumentException(
                    "activeSafetyModules contain an invalid value");
        }
        if (tokenVersion < 1) {
            throw new IllegalArgumentException("tokenVersion must be positive");
        }
    }
}
