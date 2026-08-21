package com.idavy.drtops.jtgateway.ingress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class OperationsApiStatusTest {
    private static final Instant NOW = Instant.parse("2026-08-21T10:00:00Z");

    @Test
    void expiresAuthenticatedSuccessAfterTheDefaultNinetySecondFreshnessTtl() {
        MutableClock clock = new MutableClock(NOW);
        OperationsApiStatus status = new OperationsApiStatus(clock);

        status.success(OperationsApiStatus.Source.REGISTRY, "REGISTRATION_VERIFY");
        assertEquals(OperationsApiStatus.State.UP, status.snapshot().state());

        clock.advance(Duration.ofSeconds(89));
        assertEquals(OperationsApiStatus.State.UP, status.snapshot().state());
        clock.advance(Duration.ofSeconds(2));
        OperationsApiStatus.Snapshot stale = status.snapshot();
        assertEquals(OperationsApiStatus.State.DOWN, stale.state());
        assertEquals("STALE", stale.operation());
        assertFalse(stale.sources().get(OperationsApiStatus.Source.REGISTRY).fresh());
    }

    @Test
    void givesFreshIngressFailurePriorityOverLaterRegistrySuccessUntilIngressRecovers() {
        MutableClock clock = new MutableClock(NOW);
        OperationsApiStatus status = new OperationsApiStatus(clock, Duration.ofSeconds(90));

        status.failure(OperationsApiStatus.Source.INGRESS, "INGRESS");
        clock.advance(Duration.ofSeconds(1));
        status.success(OperationsApiStatus.Source.REGISTRY, "REGISTRATION_VERIFY");

        OperationsApiStatus.Snapshot failed = status.snapshot();
        assertEquals(OperationsApiStatus.State.DOWN, failed.state());
        assertEquals("INGRESS", failed.operation());

        clock.advance(Duration.ofSeconds(1));
        status.success(OperationsApiStatus.Source.INGRESS, "INGRESS");
        assertEquals(OperationsApiStatus.State.UP, status.snapshot().state());
    }

    @Test
    void safeProbeReportsProcessReachabilityWithoutRestoringAuthenticatedContract() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        OperationsApiStatus status = new OperationsApiStatus(clock, Duration.ofSeconds(90));
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> credentialVersion = new AtomicReference<>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/actuator/health", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            credentialVersion.set(exchange.getRequestHeaders().getFirst(
                    OperationsApiClient.CREDENTIAL_VERSION_HEADER));
            byte[] body = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/actuator/health");
            OperationsApiHealthProbe probe = new OperationsApiHealthProbe(
                    RestClient.builder(), endpoint, status);

            probe.probe();

            assertEquals(OperationsApiStatus.State.DOWN, status.snapshot().state());
            assertEquals("GET", method.get());
            assertEquals("/actuator/health", path.get());
            assertNull(authorization.get());
            assertNull(credentialVersion.get());

            status.success(OperationsApiStatus.Source.REGISTRY, "REGISTRATION_VERIFY");
            assertEquals(OperationsApiStatus.State.UP, status.snapshot().state());

            clock.advance(Duration.ofSeconds(91));
            assertEquals(OperationsApiStatus.State.DOWN, status.snapshot().state());
            probe.probe();
            assertEquals(OperationsApiStatus.State.DOWN, status.snapshot().state());
            assertTrue(status.snapshot().sources().get(OperationsApiStatus.Source.PROBE).fresh());
        } finally {
            server.stop(0);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }
}
