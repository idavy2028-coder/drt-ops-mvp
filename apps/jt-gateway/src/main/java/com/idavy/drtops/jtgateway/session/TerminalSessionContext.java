package com.idavy.drtops.jtgateway.session;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable authorization snapshot for one physical terminal connection. */
public record TerminalSessionContext(
        int contractVersion,
        UUID terminalId,
        UUID onboardSystemId,
        UUID vehicleId,
        long onboardConfigurationVersion,
        Set<String> roles,
        String sourceCoordinateSystem,
        SessionProtocolProfile protocolProfile,
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
        if (contractVersion != 2) {
            throw new IllegalArgumentException("unsupported session contract version");
        }
        terminalId = Objects.requireNonNull(terminalId, "terminalId");
        onboardSystemId = Objects.requireNonNull(onboardSystemId, "onboardSystemId");
        vehicleId = Objects.requireNonNull(vehicleId, "vehicleId");
        if (onboardConfigurationVersion < 0) {
            throw new IllegalArgumentException(
                    "onboardConfigurationVersion must not be negative");
        }
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        if (roles.stream().anyMatch(role -> !KNOWN_ROLES.contains(role))) {
            throw new IllegalArgumentException("roles contain an unsupported value");
        }
        if (!"WGS84".equals(sourceCoordinateSystem)
                && !"GCJ02".equals(sourceCoordinateSystem)) {
            throw new IllegalArgumentException(
                    "sourceCoordinateSystem must be WGS84 or GCJ02");
        }
        protocolProfile = Objects.requireNonNull(protocolProfile, "protocolProfile");
        activeSafetyModules = List.copyOf(Objects.requireNonNull(
                activeSafetyModules, "activeSafetyModules"));
        String expectedStandard = switch (protocolProfile.safetyProfile()) {
            case "JSATL12_2017" -> "T/JSATL12-2017";
            case "GBT28787_2023" -> "GB/T 28787-2023";
            case "NONE" -> null;
            default -> throw new IllegalArgumentException(
                    "protocolProfile contains an unsupported safety profile");
        };
        if (!Objects.equals(expectedStandard, activeSafetyStandard)
                || !protocolProfile.enabledActiveSafetyModules().equals(activeSafetyModules)) {
            throw new IllegalArgumentException(
                    "nested and compatibility safety profiles differ");
        }
        if (!roles.contains("ACTIVE_SAFETY") && !activeSafetyModules.isEmpty()) {
            throw new IllegalArgumentException(
                    "active safety modules require the ACTIVE_SAFETY role");
        }
        if (tokenVersion < 1) {
            throw new IllegalArgumentException("tokenVersion must be positive");
        }
    }

    public record SessionProtocolProfile(
            String transportProfile,
            String businessProfile,
            String safetyProfile,
            String mediaProfile,
            List<String> enabledActiveSafetyModules,
            int activePositionIntervalSeconds,
            int idlePositionIntervalSeconds) {

        private static final Set<String> TRANSPORT_PROFILES = Set.of(
                "JT808_2019", "JT808_2013");
        private static final Set<String> BUSINESS_PROFILES = Set.of(
                "GBT28787_2023", "VENDOR_DISPATCH", "NONE");
        private static final Set<String> SAFETY_PROFILES = Set.of(
                "GBT28787_2023", "JSATL12_2017", "NONE");
        private static final Set<String> MEDIA_PROFILES = Set.of(
                "JT1078_2016", "NONE");
        private static final Set<String> ACTIVE_SAFETY_MODULES = Set.of("ADAS", "DMS");

        public SessionProtocolProfile {
            if (!TRANSPORT_PROFILES.contains(transportProfile)
                    || !BUSINESS_PROFILES.contains(businessProfile)
                    || !SAFETY_PROFILES.contains(safetyProfile)
                    || !MEDIA_PROFILES.contains(mediaProfile)) {
                throw new IllegalArgumentException(
                        "protocolProfile contains an unsupported value");
            }
            enabledActiveSafetyModules = List.copyOf(Objects.requireNonNull(
                    enabledActiveSafetyModules, "enabledActiveSafetyModules"));
            if (enabledActiveSafetyModules.stream()
                            .anyMatch(module -> !ACTIVE_SAFETY_MODULES.contains(module))
                    || Set.copyOf(enabledActiveSafetyModules).size()
                            != enabledActiveSafetyModules.size()
                    || "NONE".equals(safetyProfile)
                            && !enabledActiveSafetyModules.isEmpty()) {
                throw new IllegalArgumentException(
                        "enabledActiveSafetyModules contain an unsupported value");
            }
            if (activePositionIntervalSeconds <= 0
                    || idlePositionIntervalSeconds <= 0) {
                throw new IllegalArgumentException(
                        "position intervals must be positive");
            }
        }
    }
}
