package com.idavy.drtops.jt.protocol.jsatl12;

import com.idavy.drtops.jt.protocol.core.LocationReport;
import java.util.List;

/** Selects an extension by explicit capability; it never guesses a protocol from payload shape. */
public final class ActiveSafetyExtensionRegistry {
    private final List<ActiveSafetyExtension> extensions;

    public ActiveSafetyExtensionRegistry(List<ActiveSafetyExtension> extensions) {
        this.extensions = List.copyOf(extensions);
    }

    public ActiveSafetyDecodeResult decode(ActiveSafetyCapabilityProfile profile, LocationReport position) {
        ActiveSafetyExtension selected = extensions.stream()
                .filter(extension -> extension.standardCode().equals(profile.standardCode()))
                .filter(extension -> extension.supports(profile))
                .findFirst()
                .orElse(null);
        if (selected == null) {
            return ActiveSafetyDecodeResult.rejected("UNSUPPORTED_ACTIVE_SAFETY_STANDARD");
        }
        try {
            ActiveSafetyDecodeResult decoded = selected.decode(position);
            List<DecodedActiveSafetyAlarm> enabled = decoded.alarms().stream()
                    .filter(alarm -> profile.modules().contains(alarm.module()))
                    .toList();
            List<ActiveSafetyDecodeResult.Rejection> relevantRejections = decoded.rejections().stream()
                    .filter(rejection -> rejection.module() == null || profile.modules().contains(rejection.module()))
                    .toList();
            return new ActiveSafetyDecodeResult(enabled, relevantRejections);
        } catch (RuntimeException malformedExtension) {
            return ActiveSafetyDecodeResult.rejected("ACTIVE_SAFETY_EXTENSION_REJECTED");
        }
    }
}
