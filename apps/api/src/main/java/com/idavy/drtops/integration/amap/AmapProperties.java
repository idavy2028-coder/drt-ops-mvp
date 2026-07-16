package com.idavy.drtops.integration.amap;

import com.idavy.drtops.domain.map.MapProviderStatus;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "drt.map.amap")
public class AmapProperties {

    static final String PROVIDER = "AMAP";
    static final String COORDINATE_SYSTEM = "GCJ-02";

    private boolean enabled;
    private String webServiceKey = "";
    private String baseUrl = "https://restapi.amap.com";
    private int connectTimeoutMs = 2_000;
    private int readTimeoutMs = 5_000;

    public boolean isAvailable() {
        return enabled && StringUtils.hasText(webServiceKey);
    }

    public MapProviderStatus providerStatus() {
        if (!enabled) {
            return MapProviderStatus.degraded(PROVIDER, "disabled", COORDINATE_SYSTEM);
        }
        if (!StringUtils.hasText(webServiceKey)) {
            return MapProviderStatus.degraded(PROVIDER, "missing-web-service-key", COORDINATE_SYSTEM);
        }
        return MapProviderStatus.available(PROVIDER, COORDINATE_SYSTEM);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getWebServiceKey() {
        return webServiceKey;
    }

    public void setWebServiceKey(String webServiceKey) {
        this.webServiceKey = webServiceKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }
}
