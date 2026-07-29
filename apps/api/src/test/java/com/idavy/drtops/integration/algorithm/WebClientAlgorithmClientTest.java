package com.idavy.drtops.integration.algorithm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

class WebClientAlgorithmClientTest {

    @Test
    void convertsConnectionFailureIntoAlgorithmUnavailableErrorWithoutLeakingDetails() {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(httpRequest -> Mono.error(
                new WebClientRequestException(
                        new ConnectException("connection refused at 127.0.0.1:8090"),
                        httpRequest.method(),
                        httpRequest.url(),
                        httpRequest.headers())));
        WebClientAlgorithmClient client = new WebClientAlgorithmClient(builder, "http://algorithm");

        assertThatThrownBy(() -> client.evaluate(request()))
                .isInstanceOfSatisfying(AlgorithmUnavailableException.class, exception -> {
                    assertThat(exception).hasMessage("算法服务不可用");
                    assertThat(exception.getMessage()).doesNotContain("127.0.0.1", "connection refused");
                    assertThat(exception.getCause()).isInstanceOf(WebClientRequestException.class);
                });
    }

    private DispatchEvaluateRequest request() {
        UUID boardingStopId = UUID.fromString("55555555-5555-5555-5555-555555555551");
        UUID alightingStopId = UUID.fromString("55555555-5555-5555-5555-555555555552");
        return new DispatchEvaluateRequest(
                new DispatchEvaluateRequest.Order(
                        UUID.fromString("77777777-7777-7777-7777-777777777777"),
                        1,
                        "IMMEDIATE",
                        OffsetDateTime.parse("2026-07-29T16:00:00+08:00"),
                        boardingStopId,
                        alightingStopId),
                new DispatchEvaluateRequest.RuleSet(
                        30,
                        20,
                        new BigDecimal("0.80"),
                        new BigDecimal("0.60"),
                        new DispatchEvaluateRequest.Weights(
                                new BigDecimal("0.40"),
                                new BigDecimal("0.30"),
                                new BigDecimal("0.20"),
                                new BigDecimal("0.10")),
                        "BEST_POSITION"),
                List.of());
    }
}
