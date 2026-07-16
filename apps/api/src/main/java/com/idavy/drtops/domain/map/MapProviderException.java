package com.idavy.drtops.domain.map;

public class MapProviderException extends RuntimeException {

    private final MapProviderStatus status;

    public MapProviderException(MapProviderStatus status) {
        super("地图服务暂不可用，请稍后重试");
        this.status = status;
    }

    public MapProviderException(MapProviderStatus status, Throwable cause) {
        super("地图服务暂不可用，请稍后重试", cause);
        this.status = status;
    }

    public MapProviderStatus getStatus() {
        return status;
    }
}
