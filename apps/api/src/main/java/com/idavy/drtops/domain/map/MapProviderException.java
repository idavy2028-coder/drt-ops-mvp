package com.idavy.drtops.domain.map;

public class MapProviderException extends RuntimeException {

    private final MapProviderStatus status;

    public MapProviderException(MapProviderStatus status) {
        super(messageFor(status));
        this.status = status;
    }

    public MapProviderException(MapProviderStatus status, Throwable cause) {
        super(messageFor(status), cause);
        this.status = status;
    }

    public MapProviderStatus getStatus() {
        return status;
    }

    private static String messageFor(MapProviderStatus status) {
        return switch (status.degradedReason()) {
            case "request-timeout" -> "地图服务请求超时，请稍后重试";
            case "upstream-network-unavailable" -> "地图上游网络不可用，请稍后重试";
            case "upstream-response-invalid" -> "地图服务响应异常，请稍后重试";
            default -> "地图服务暂不可用，请稍后重试";
        };
    }
}
