package com.idavy.drtops.jt.protocol.jsatl12;

import com.idavy.drtops.jt.protocol.core.LocationReport;

/** Extension point for active-safety standards selected by a terminal capability profile. */
public interface ActiveSafetyExtension {
    String standardCode();
    boolean supports(ActiveSafetyCapabilityProfile profile);
    ActiveSafetyDecodeResult decode(LocationReport position);
}
