package com.idavy.drtops.domain.location;

import java.math.BigDecimal;

public interface ServiceAreaLocationChecker {

    boolean isInsideEnabledArea(BigDecimal longitude, BigDecimal latitude);
}
