package com.idavy.drtops.domain.area;

import java.time.OffsetDateTime;
import java.util.UUID;

public record VirtualStopView(
        UUID id,
        UUID serviceAreaId,
        String name,
        String address,
        String location,
        double longitude,
        double latitude,
        int serviceRadiusMeters,
        boolean boardingEnabled,
        boolean alightingEnabled,
        String safetyNote,
        boolean enabled,
        String coordinateSystem,
        String source,
        OffsetDateTime verifiedAt,
        OffsetDateTime updatedAt) {

    static VirtualStopView from(VirtualStop stop) {
        double[] point = coordinates(stop.getLocation());
        return new VirtualStopView(
                stop.getId(), stop.getServiceAreaId(), stop.getName(), stop.getAddress(), stop.getLocation(), point[0], point[1],
                stop.getServiceRadiusMeters(), stop.isBoardingEnabled(), stop.isAlightingEnabled(), stop.getSafetyNote(), stop.isEnabled(),
                stop.getCoordinateSystem() == null ? "GCJ-02" : stop.getCoordinateSystem(),
                stop.getSource() == null ? "LEGACY" : stop.getSource(), stop.getVerifiedAt(), stop.getUpdatedAt());
    }

    private static double[] coordinates(String location) {
        String value = location == null ? "" : location.trim();
        if (!value.startsWith("POINT(") || !value.endsWith(")")) {
            return new double[] {0, 0};
        }
        String[] values = value.substring(6, value.length() - 1).trim().split("\\s+");
        if (values.length != 2) {
            return new double[] {0, 0};
        }
        try {
            return new double[] {Double.parseDouble(values[0]), Double.parseDouble(values[1])};
        } catch (NumberFormatException exception) {
            return new double[] {0, 0};
        }
    }
}
