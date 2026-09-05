package com.idavy.drtops.jtgateway.e2e;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayTestRigTest {
    @TempDir
    Path tempDir;

    @Test
    void closesStartedApiAndKeeperWhenLaterInitializationFails() throws Exception {
        IOException primary = new IOException("synthetic post-api initialization failure");
        AtomicReference<URI> apiEndpoint = new AtomicReference<>();
        AtomicReference<Connection> keeper = new AtomicReference<>();

        IOException thrown = assertThrows(IOException.class, () -> new GatewayTestRig(
                tempDir,
                true,
                (startedApi, openedKeeper) -> {
                    apiEndpoint.set(startedApi.endpoint());
                    keeper.set(openedKeeper);
                    assertPortOpen(startedApi.endpoint());
                    throw primary;
                }));

        assertSame(primary, thrown, "resource cleanup must not replace initialization failure");
        assertNotNull(apiEndpoint.get(), "test hook must run after API startup");
        assertNotNull(keeper.get(), "test hook must observe the opened database keeper");
        assertTrue(keeper.get().isClosed(), "constructor failure must close the keeper");
        assertPortClosed(apiEndpoint.get());
    }

    @Test
    void capturingApiReleasesStartedPortWhenItsOwnConstructionFails() {
        IOException primary = new IOException("synthetic capturing API startup failure");
        AtomicReference<InetSocketAddress> startedAddress = new AtomicReference<>();

        IOException thrown = assertThrows(IOException.class, () ->
                new GatewayTestRig.CapturingApi(address -> {
                    startedAddress.set(address);
                    assertPortOpen(address);
                    throw primary;
                }));

        assertSame(primary, thrown, "local cleanup must preserve the startup failure");
        assertNotNull(startedAddress.get(), "hook must run after the HTTP server starts");
        assertPortClosed(startedAddress.get());
    }

    @Test
    void cleanupPreservesPrimaryAndSuppressesFailuresInServerApiKeeperOrder() {
        IOException primary = new IOException("initialization failed");
        RuntimeException serverFailure = new IllegalStateException("server close failed");
        RuntimeException apiFailure = new IllegalArgumentException("api close failed");
        RuntimeException keeperFailure = new IllegalStateException("keeper close failed");
        List<String> closeOrder = new ArrayList<>();

        Throwable result = GatewayTestRig.closeResources(
                primary,
                failingClose("server", serverFailure, closeOrder),
                failingClose("api", apiFailure, closeOrder),
                failingClose("keeper", keeperFailure, closeOrder));

        assertSame(primary, result);
        assertEquals(List.of("server", "api", "keeper"), closeOrder);
        assertArrayEquals(
                new Throwable[] {serverFailure, apiFailure, keeperFailure},
                primary.getSuppressed());
    }

    @Test
    void normalCloseReleasesGatewayApiAndKeeper() throws Exception {
        GatewayTestRig rig = new GatewayTestRig(tempDir, true);
        InetSocketAddress gatewayEndpoint = rig.endpoint();
        URI apiEndpoint = rig.api.endpoint();
        Connection keeper = rig.databaseKeeper;

        rig.close();

        assertTrue(keeper.isClosed());
        assertPortClosed(gatewayEndpoint);
        assertPortClosed(apiEndpoint);
    }

    private static AutoCloseable failingClose(
            String name, RuntimeException failure, List<String> closeOrder) {
        return () -> {
            closeOrder.add(name);
            throw failure;
        };
    }

    private static void assertPortOpen(URI endpoint) throws IOException {
        assertPortOpen(new InetSocketAddress(InetAddress.getLoopbackAddress(), endpoint.getPort()));
    }

    private static void assertPortOpen(InetSocketAddress endpoint) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(endpoint, 500);
        }
    }

    private static void assertPortClosed(URI endpoint) {
        assertPortClosed(new InetSocketAddress(InetAddress.getLoopbackAddress(), endpoint.getPort()));
    }

    private static void assertPortClosed(InetSocketAddress endpoint) {
        assertThrows(IOException.class, () -> {
            try (Socket socket = new Socket()) {
                socket.connect(endpoint, 500);
            }
        }, () -> "port must be closed: " + endpoint);
    }
}
