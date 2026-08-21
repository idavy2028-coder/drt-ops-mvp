package com.idavy.drtops.jtgateway;

import com.idavy.drtops.jtgateway.netty.JtGatewayServer;
import java.util.Objects;
import org.springframework.context.SmartLifecycle;

/** Couples the Netty listener lifetime to the owning Spring application context. */
public final class GatewayServerLifecycle implements SmartLifecycle {
    private final JtGatewayServer server;
    private volatile boolean running;

    public GatewayServerLifecycle(JtGatewayServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override
    public synchronized void start() {
        if (!running) {
            server.start();
            running = true;
        }
    }

    @Override
    public synchronized void stop() {
        if (running) {
            server.close();
            running = false;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public boolean isListening() {
        return running && server.isListening();
    }
}
