package com.idavy.drtops.domain.map;

public record MapProviderStatus(
        String provider,
        boolean enabled,
        String degradedReason,
        String coordinateSystem) {

    public static MapProviderStatus available(String provider, String coordinateSystem) {
        return new MapProviderStatus(provider, true, null, coordinateSystem);
    }

    public static MapProviderStatus degraded(String provider, String degradedReason, String coordinateSystem) {
        return new MapProviderStatus(provider, false, degradedReason, coordinateSystem);
    }
}
