package com.idavy.drtops.integration.amap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idavy.drtops.domain.map.MapProviderException;
import com.idavy.drtops.domain.map.MapProviderStatus;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;

final class AmapWebServiceClient {

    private final WebClient webClient;
    private final AmapProperties properties;
    private final AmapProviderMetrics metrics;
    private final ObjectMapper objectMapper = new ObjectMapper();

    AmapWebServiceClient(WebClient webClient, AmapProperties properties, AmapProviderMetrics metrics) {
        this.webClient = webClient;
        this.properties = properties;
        this.metrics = metrics;
    }

    <T> T get(String operation, String path, Consumer<UriBuilder> query, Function<JsonNode, T> mapper) {
        return metrics.record(operation, () -> {
            ensureAvailable(operation);
            try {
                String body = webClient.get()
                        .uri(uriBuilder -> {
                            uriBuilder.path(path).queryParam("key", properties.getWebServiceKey());
                            query.accept(uriBuilder);
                            return uriBuilder.build();
                        })
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();
                JsonNode root = objectMapper.readTree(body == null ? "" : body);
                if (!"1".equals(root.path("status").asText())) {
                    throw unavailable(operation, "upstream-rejected", null);
                }
                return mapper.apply(root);
            } catch (MapProviderException exception) {
                throw exception;
            } catch (Exception exception) {
                String reason = causedByTimeout(exception) ? "request-timeout" : "upstream-response-invalid";
                throw unavailable(operation, reason, exception);
            }
        });
    }

    private void ensureAvailable(String operation) {
        if (!properties.isAvailable()) {
            MapProviderStatus status = properties.providerStatus();
            metrics.recordDegraded(operation, status.degradedReason());
            throw new MapProviderException(status);
        }
    }

    private MapProviderException unavailable(String operation, String reason, Throwable cause) {
        metrics.recordDegraded(operation, reason);
        MapProviderStatus status = MapProviderStatus.degraded("AMAP", reason, "GCJ-02");
        return cause == null ? new MapProviderException(status) : new MapProviderException(status, cause);
    }

    private boolean causedByTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
