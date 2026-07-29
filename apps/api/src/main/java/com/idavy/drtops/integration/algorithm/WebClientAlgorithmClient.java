package com.idavy.drtops.integration.algorithm;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
class WebClientAlgorithmClient implements AlgorithmClient {

    private final WebClient webClient;

    WebClientAlgorithmClient(
            WebClient.Builder webClientBuilder,
            @Value("${dispatch.algorithm.base-url:http://localhost:8090}") String baseUrl) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
    }

    @Override
    public DispatchEvaluateResponse evaluate(DispatchEvaluateRequest request) {
        try {
            DispatchEvaluateResponse response = webClient.post()
                    .uri("/dispatch/evaluate")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(DispatchEvaluateResponse.class)
                    .block(Duration.ofSeconds(5));
            if (response == null) {
                throw new IllegalStateException("Algorithm returned an empty response");
            }
            return response;
        } catch (RuntimeException exception) {
            throw new AlgorithmUnavailableException(exception);
        }
    }
}
