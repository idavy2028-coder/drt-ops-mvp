package com.idavy.drtops.jtgateway.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

public final class ConnectionAdmissionHandler extends ChannelInboundHandlerAdapter {
    static final AttributeKey<String> REMOTE_IP = AttributeKey.valueOf("jt.remoteIp");

    private final AdmissionTracker tracker;
    private boolean acquired;

    public ConnectionAdmissionHandler(AdmissionTracker tracker) {
        this.tracker = java.util.Objects.requireNonNull(tracker, "tracker");
    }

    @Override
    public void channelActive(ChannelHandlerContext context) throws Exception {
        String remoteIp = remoteIp(context.channel().remoteAddress());
        context.channel().attr(REMOTE_IP).set(remoteIp);
        if (!tracker.tryAcquire(remoteIp)) {
            context.close();
            return;
        }
        acquired = true;
        super.channelActive(context);
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        if (acquired) {
            tracker.release(remoteIp(context.channel().remoteAddress()));
            acquired = false;
        }
        super.channelInactive(context);
    }

    static String remoteIp(SocketAddress address) {
        if (address instanceof InetSocketAddress inet) {
            return inet.getAddress() == null ? inet.getHostString() : inet.getAddress().getHostAddress();
        }
        return "embedded";
    }

    public static final class AdmissionTracker {
        private static final long ONE_SECOND_NANOS = 1_000_000_000L;

        private final int maxConnectionsPerIp;
        private final int maxMessagesPerSecond;
        private final LongSupplier nanoTime;
        private final ConcurrentMap<String, AtomicInteger> connections = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, RateWindow> rateWindows = new ConcurrentHashMap<>();

        public AdmissionTracker(int maxConnectionsPerIp, int maxMessagesPerSecond) {
            this(maxConnectionsPerIp, maxMessagesPerSecond, System::nanoTime);
        }

        public AdmissionTracker(
                int maxConnectionsPerIp, int maxMessagesPerSecond, LongSupplier nanoTime) {
            if (maxConnectionsPerIp < 1 || maxMessagesPerSecond < 1) {
                throw new IllegalArgumentException("admission limits must be positive");
            }
            this.maxConnectionsPerIp = maxConnectionsPerIp;
            this.maxMessagesPerSecond = maxMessagesPerSecond;
            this.nanoTime = java.util.Objects.requireNonNull(nanoTime, "nanoTime");
        }

        boolean tryAcquire(String remoteIp) {
            removeExpiredInactiveWindows(nanoTime.getAsLong());
            AtomicInteger counter = connections.computeIfAbsent(remoteIp, ignored -> new AtomicInteger());
            int current = counter.incrementAndGet();
            if (current <= maxConnectionsPerIp) {
                return true;
            }
            release(remoteIp);
            return false;
        }

        void release(String remoteIp) {
            connections.computeIfPresent(remoteIp, (ignored, counter) -> {
                if (counter.decrementAndGet() > 0) {
                    return counter;
                }
                return null;
            });
        }

        boolean allowMessage(String remoteIp) {
            long now = nanoTime.getAsLong();
            removeExpiredInactiveWindows(now);
            return rateWindows.computeIfAbsent(remoteIp,
                    ignored -> new RateWindow(now)).allow(now, maxMessagesPerSecond);
        }

        int trackedRateWindows() {
            return rateWindows.size();
        }

        private void removeExpiredInactiveWindows(long now) {
            for (String remoteIp : rateWindows.keySet()) {
                if (connections.containsKey(remoteIp)) {
                    continue;
                }
                rateWindows.computeIfPresent(remoteIp, (ignored, window) ->
                        window.expiredAt(now) ? null : window);
            }
        }

        private static final class RateWindow {
            private long windowStartedAt;
            private int messages;

            private RateWindow(long windowStartedAt) {
                this.windowStartedAt = windowStartedAt;
            }

            private synchronized boolean allow(long now, int limit) {
                if (now - windowStartedAt >= ONE_SECOND_NANOS || now < windowStartedAt) {
                    windowStartedAt = now;
                    messages = 0;
                }
                messages++;
                return messages <= limit;
            }

            private synchronized boolean expiredAt(long now) {
                return now < windowStartedAt || now - windowStartedAt >= ONE_SECOND_NANOS;
            }
        }
    }
}
