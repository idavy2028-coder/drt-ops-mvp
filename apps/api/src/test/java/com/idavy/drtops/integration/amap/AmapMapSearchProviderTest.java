package com.idavy.drtops.integration.amap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idavy.drtops.domain.map.AddressSuggestion;
import com.idavy.drtops.domain.map.GeocodeResult;
import com.idavy.drtops.domain.map.MapProviderException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import io.netty.handler.timeout.ReadTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import reactor.core.publisher.Mono;

class AmapMapSearchProviderTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void convertsInputTipsToInternalSuggestionsAndSendsOnlyWebServiceParameters() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        startServer(exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, """
                    {"status":"1","tips":[
                      {"id":"B0FFG4","name":"通渭县人民政府","district":"定西市通渭县",
                       "address":"平襄镇文化街","location":"105.242100,35.210300"},
                      {"id":"bad","name":"无坐标结果","location":"[]"}
                    ]}
                    """);
        });
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AmapMapSearchProvider provider = provider(WebClient.builder().baseUrl(baseUrl()).build(), registry);

        var result = provider.suggest("人民政府", "通渭县");

        assertThat(result).containsExactly(new AddressSuggestion(
                "B0FFG4", "通渭县人民政府", "平襄镇文化街", "定西市通渭县",
                new com.idavy.drtops.domain.map.Coordinate("105.242100", "35.210300")));
        assertThat(query.get()).contains("key=test-web-service-key", "keywords=%E4%BA%BA%E6%B0%91%E6%94%BF%E5%BA%9C",
                "city=%E9%80%9A%E6%B8%AD%E5%8E%BF", "citylimit=true");
        assertThat(registry.get("drt.map.provider.request.total")
                .tag("operation", "suggest").tag("result", "success").counter().count()).isEqualTo(1);
        assertThat(registry.get("drt.map.provider.request.duration")
                .tag("operation", "suggest").tag("result", "success").timer().count()).isEqualTo(1);
    }

    @Test
    void convertsGeocodeToInternalResult() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        startServer(exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, """
                    {"status":"1","geocodes":[{"formatted_address":"甘肃省定西市通渭县平襄镇文化街",
                    "province":"甘肃省","city":"定西市","district":"通渭县","location":"105.242100,35.210300"}]}
                    """);
        });
        AmapMapSearchProvider provider = provider(WebClient.builder().baseUrl(baseUrl()).build(), new SimpleMeterRegistry());

        GeocodeResult result = provider.geocode("文化街", "通渭县");

        assertThat(result).isEqualTo(new GeocodeResult(
                "甘肃省定西市通渭县平襄镇文化街", "甘肃省", "定西市", "通渭县",
                new com.idavy.drtops.domain.map.Coordinate("105.242100", "35.210300")));
        assertThat(query.get()).contains("key=test-web-service-key", "address=%E6%96%87%E5%8C%96%E8%A1%97",
                "city=%E9%80%9A%E6%B8%AD%E5%8E%BF");
    }

    @Test
    void turnsAmapFailureIntoChineseBusinessErrorWithoutLeakingProviderMessage() throws Exception {
        startServer(exchange -> respond(exchange, 200,
                "{\"status\":\"0\",\"info\":\"INVALID_USER_KEY\",\"infocode\":\"10001\"}"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AmapMapSearchProvider provider = provider(WebClient.builder().baseUrl(baseUrl()).build(), registry);

        assertThatThrownBy(() -> provider.suggest("人民政府", "通渭县"))
                .isInstanceOfSatisfying(MapProviderException.class, exception -> {
                    assertThat(exception).hasMessage("地图服务暂不可用，请稍后重试");
                    assertThat(exception.getMessage()).doesNotContain("INVALID_USER_KEY", "10001");
                    assertThat(exception.getStatus().degradedReason()).isEqualTo("upstream-rejected");
                });
        assertThat(registry.get("drt.map.provider.request.total")
                .tag("operation", "suggest").tag("result", "failure").counter().count()).isEqualTo(1);
    }

    @Test
    void turnsTimeoutIntoChineseBusinessError() {
        WebClient timeoutClient = WebClient.builder()
                .exchangeFunction(request -> Mono.error(new TimeoutException("socket timeout details")))
                .build();
        AmapMapSearchProvider provider = provider(timeoutClient, new SimpleMeterRegistry());

        assertThatThrownBy(() -> provider.geocode("文化街", "通渭县"))
                .isInstanceOfSatisfying(MapProviderException.class, exception -> {
                    assertThat(exception).hasMessage("地图服务请求超时，请稍后重试");
                    assertThat(exception.getMessage()).doesNotContain("socket timeout details");
                    assertThat(exception.getStatus().degradedReason()).isEqualTo("request-timeout");
                });
    }

    @Test
    void classifiesReactorNettyReadTimeoutAsTimeoutAndRecordsMatchingDegradationMetric() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AmapMapSearchProvider provider = provider(webClientRequestFailure(ReadTimeoutException.INSTANCE), registry);

        assertThatThrownBy(() -> provider.geocode("文化街", "通渭县"))
                .isInstanceOfSatisfying(MapProviderException.class, exception -> {
                    assertThat(exception).hasMessage("地图服务请求超时，请稍后重试");
                    assertThat(exception.getStatus().degradedReason()).isEqualTo("request-timeout");
                });
        assertThat(registry.get("drt.map.provider.degraded.total")
                .tag("operation", "geocode").tag("reason", "request-timeout").counter().count()).isEqualTo(1);
    }

    @Test
    void classifiesWebClientDnsFailureAsNetworkUnavailableAndRecordsMatchingDegradationMetric() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AmapMapSearchProvider provider = provider(webClientRequestFailure(new UnknownHostException("amap.internal")), registry);

        assertThatThrownBy(() -> provider.suggest("人民政府", "通渭县"))
                .isInstanceOfSatisfying(MapProviderException.class, exception -> {
                    assertThat(exception).hasMessage("地图上游网络不可用，请稍后重试");
                    assertThat(exception.getStatus().degradedReason()).isEqualTo("upstream-network-unavailable");
                    assertThat(exception.getMessage()).doesNotContain("amap.internal");
                });
        assertThat(registry.get("drt.map.provider.degraded.total")
                .tag("operation", "suggest").tag("reason", "upstream-network-unavailable").counter().count())
                .isEqualTo(1);
    }

    @Test
    void classifiesWebClientConnectionFailureAsNetworkUnavailable() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AmapMapSearchProvider provider = provider(webClientRequestFailure(new ConnectException("connection refused")), registry);

        assertThatThrownBy(() -> provider.geocode("文化街", "通渭县"))
                .isInstanceOfSatisfying(MapProviderException.class, exception -> {
                    assertThat(exception).hasMessage("地图上游网络不可用，请稍后重试");
                    assertThat(exception.getStatus().degradedReason()).isEqualTo("upstream-network-unavailable");
                });
        assertThat(registry.get("drt.map.provider.degraded.total")
                .tag("operation", "geocode").tag("reason", "upstream-network-unavailable").counter().count())
                .isEqualTo(1);
    }

    @Test
    void reportsMissingKeyAsDegradedWithoutCallingAmap() {
        AmapProperties properties = properties();
        properties.setWebServiceKey("");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AmapMapSearchProvider provider = new AmapMapSearchProvider(WebClient.builder().build(), properties,
                new AmapProviderMetrics(registry));

        assertThatThrownBy(() -> provider.suggest("人民政府", "通渭县"))
                .isInstanceOfSatisfying(MapProviderException.class, exception -> {
                    assertThat(exception).hasMessage("地图服务暂不可用，请稍后重试");
                    assertThat(exception.getStatus().degradedReason()).isEqualTo("missing-web-service-key");
                });
        assertThat(registry.get("drt.map.provider.degraded.total")
                .tag("operation", "suggest").tag("reason", "missing-web-service-key").counter().count()).isEqualTo(1);
    }

    private AmapMapSearchProvider provider(WebClient webClient, SimpleMeterRegistry registry) {
        return new AmapMapSearchProvider(webClient, properties(), new AmapProviderMetrics(registry));
    }

    private AmapProperties properties() {
        AmapProperties properties = new AmapProperties();
        properties.setEnabled(true);
        properties.setWebServiceKey("test-web-service-key");
        return properties;
    }

    private WebClient webClientRequestFailure(Throwable cause) {
        return WebClient.builder().exchangeFunction(request -> Mono.error(new WebClientRequestException(
                cause, request.method(), request.url(), request.headers()))).build();
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> handler.handle(exchange));
        server.start();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
