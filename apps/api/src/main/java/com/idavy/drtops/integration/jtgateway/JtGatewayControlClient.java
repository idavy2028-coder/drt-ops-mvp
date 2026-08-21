package com.idavy.drtops.integration.jtgateway;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

public interface JtGatewayControlClient {
    boolean disconnect(UUID terminalId, String reasonCode);

    @Component
    final class Http implements JtGatewayControlClient {

        private final WebClient webClient;
        private final String credential;
        private final String version;
        private final boolean configured;

        public Http(
                WebClient.Builder builder,
                @Value("${jt.gateway.control.base-url:}") String baseUrl,
                @Value("${jt.gateway.control.credential:}") String credential,
                @Value("${jt.gateway.control.version:}") String version) {
            this.configured = baseUrl != null && !baseUrl.isBlank()
                    && credential != null && !credential.isBlank()
                    && version != null && !version.isBlank();
            this.webClient = configured ? builder.baseUrl(baseUrl).build() : null;
            this.credential = credential;
            this.version = version;
        }

        @Override
        public boolean disconnect(UUID terminalId, String reasonCode) {
            if (!configured) {
                return false;
            }
            try {
                webClient.post()
                        .uri("/internal/control/terminals/{terminalId}/disconnect", terminalId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential)
                        .header("X-Control-Credential-Version", version)
                        .bodyValue(Map.of("reasonCode", reasonCode))
                        .retrieve()
                        .toBodilessEntity()
                        .block(Duration.ofSeconds(5));
                return true;
            } catch (RuntimeException exception) {
                return false;
            }
        }
    }
}
