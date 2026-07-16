package com.idavy.drtops.domain.map;

public class MapProviderException extends RuntimeException {

    private final MapProviderStatus status;

    public MapProviderException(MapProviderStatus status) {
        super(status.provider() + " map provider unavailable: " + status.degradedReason());
        this.status = status;
    }

    public MapProviderException(MapProviderStatus status, Throwable cause) {
        super(status.provider() + " map provider unavailable: " + status.degradedReason(), cause);
        this.status = status;
    }

    public MapProviderStatus getStatus() {
        return status;
    }
}
