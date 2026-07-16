package com.idavy.drtops.integration.amap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idavy.drtops.domain.map.Coordinate;
import com.idavy.drtops.domain.map.DistanceResult;
import com.idavy.drtops.domain.map.MapProviderException;
import com.idavy.drtops.domain.map.RoutePlanResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class AmapRoutePlanningProviderTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void convertsDrivingRouteDistanceDurationAndPolylineAndLimitsWaypointsToSixteen() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        startServer(exchange -> {
            path.set(exchange.getRequestURI().getPath());
            query.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, """
                    {"status":"1","route":{"paths":[{"distance":"1234","duration":"456",
                    "steps":[{"polyline":"105.240000,35.210000;105.241000,35.211000"},
                    {"polyline":"105.241000,35.211000;105.242000,35.212000"}]}]}}
                    """);
        });
        AmapRoutePlanningProvider provider = provider(new SimpleMeterRegistry());
        List<Coordinate> waypoints = List.of(new Coordinate("105.241000", "35.211000"));

        RoutePlanResult result = provider.drivingRoute(
                new Coordinate("105.240000", "35.210000"), new Coordinate("105.242000", "35.212000"), waypoints);

        assertThat(path.get()).isEqualTo("/v3/direction/driving");
        assertThat(query.get()).contains("key=test-web-service-key", "origin=105.240000,35.210000",
                "destination=105.242000,35.212000", "waypoints=105.241000,35.211000");
        assertThat(result.distanceMeters()).isEqualTo(1234);
        assertThat(result.durationSeconds()).isEqualTo(456);
        assertThat(result.pathCoordinates()).containsExactly(
                new Coordinate("105.240000", "35.210000"),
                new Coordinate("105.241000", "35.211000"),
                new Coordinate("105.241000", "35.211000"),
                new Coordinate("105.242000", "35.212000"));
    }

    @Test
    void convertsDistanceResponseToInternalResult() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        startServer(exchange -> {
            path.set(exchange.getRequestURI().getPath());
            respond(exchange, 200, "{\"status\":\"1\",\"results\":[{\"distance\":\"890\",\"duration\":\"120\"}]}");
        });
        AmapRoutePlanningProvider provider = provider(new SimpleMeterRegistry());

        DistanceResult result = provider.distance(
                new Coordinate("105.240000", "35.210000"), new Coordinate("105.242000", "35.212000"));

        assertThat(path.get()).isEqualTo("/v3/distance");
        assertThat(result).isEqualTo(new DistanceResult(890, 120));
    }

    @Test
    void rejectsMoreThanSixteenWaypointsWithChineseValidationError() {
        AmapRoutePlanningProvider provider = provider(new SimpleMeterRegistry());
        List<Coordinate> waypoints = java.util.stream.IntStream.range(0, 17)
                .mapToObj(index -> new Coordinate("105.24" + index, "35.21" + index)).toList();

        assertThatThrownBy(() -> provider.drivingRoute(
                new Coordinate("105.240000", "35.210000"), new Coordinate("105.242000", "35.212000"), waypoints))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getReason()).isEqualTo("途经点最多支持16个");
                });
    }

    @Test
    void sendsExactlySixteenWaypoints() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        startServer(exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, "{\"status\":\"1\",\"route\":{\"paths\":[{\"distance\":\"1\",\"duration\":\"1\",\"steps\":[]}]}}");
        });
        AmapRoutePlanningProvider provider = provider(new SimpleMeterRegistry());
        List<Coordinate> waypoints = java.util.stream.IntStream.range(0, 16)
                .mapToObj(index -> new Coordinate("105." + (241000 + index), "35.211000"))
                .toList();

        provider.drivingRoute(new Coordinate("105.240000", "35.210000"),
                new Coordinate("105.242000", "35.212000"), waypoints);

        String waypointParameter = query.get().split("waypoints=", 2)[1];
        assertThat(waypointParameter.split(";", -1)).hasSize(16);
        assertThat(waypointParameter).contains("105.241000,35.211000", "105.241015,35.211000");
    }

    @Test
    void hidesAmapFailureMessageForRouteCalls() throws Exception {
        startServer(exchange -> respond(exchange, 200,
                "{\"status\":\"0\",\"info\":\"DAILY_QUERY_OVER_LIMIT\",\"infocode\":\"10003\"}"));
        AmapRoutePlanningProvider provider = provider(new SimpleMeterRegistry());

        assertThatThrownBy(() -> provider.distance(
                new Coordinate("105.240000", "35.210000"), new Coordinate("105.242000", "35.212000")))
                .isInstanceOfSatisfying(MapProviderException.class, exception -> {
                    assertThat(exception).hasMessage("地图服务暂不可用，请稍后重试");
                    assertThat(exception.getMessage()).doesNotContain("DAILY_QUERY_OVER_LIMIT", "10003");
                });
    }

    @Test
    void recordsDegradationWhenAmapReturnsAnIncompleteSuccessfulPayload() throws Exception {
        startServer(exchange -> respond(exchange, 200, "{\"status\":\"1\",\"results\":[]}"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AmapRoutePlanningProvider provider = provider(registry);

        assertThatThrownBy(() -> provider.distance(
                new Coordinate("105.240000", "35.210000"), new Coordinate("105.242000", "35.212000")))
                .isInstanceOf(MapProviderException.class);
        assertThat(registry.get("drt.map.provider.degraded.total")
                .tag("operation", "distance").tag("reason", "upstream-response-invalid").counter().count()).isEqualTo(1);
    }

    private AmapRoutePlanningProvider provider(SimpleMeterRegistry registry) {
        AmapProperties properties = new AmapProperties();
        properties.setEnabled(true);
        properties.setWebServiceKey("test-web-service-key");
        String baseUrl = server == null ? "http://127.0.0.1:1" : baseUrl();
        return new AmapRoutePlanningProvider(WebClient.builder().baseUrl(baseUrl).build(), properties,
                new AmapProviderMetrics(registry));
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
