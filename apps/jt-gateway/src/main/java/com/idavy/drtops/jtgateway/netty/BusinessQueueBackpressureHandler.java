package com.idavy.drtops.jtgateway.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.ScheduledFuture;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

public final class BusinessQueueBackpressureHandler extends ChannelInboundHandlerAdapter {
    private static final long RECHECK_MILLIS = 10;

    private final IntSupplier queuePressure;
    private final int highWatermark;
    private final int lowWatermark;
    private final long maximumCongestionNanos;
    private final LongSupplier nanoTime;
    private long congestionStartedAt = Long.MIN_VALUE;
    private ScheduledFuture<?> recheck;

    public BusinessQueueBackpressureHandler(
            IntSupplier queuePressure,
            int highWatermark,
            int lowWatermark,
            Duration maximumCongestion,
            LongSupplier nanoTime) {
        if (lowWatermark < 0 || highWatermark < 1 || lowWatermark >= highWatermark) {
            throw new IllegalArgumentException("backpressure watermarks are invalid");
        }
        if (maximumCongestion.isNegative() || maximumCongestion.isZero()) {
            throw new IllegalArgumentException("maximumCongestion must be positive");
        }
        this.queuePressure = java.util.Objects.requireNonNull(queuePressure, "queuePressure");
        this.highWatermark = highWatermark;
        this.lowWatermark = lowWatermark;
        this.maximumCongestionNanos = maximumCongestion.toNanos();
        this.nanoTime = java.util.Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        if (queuePressure.getAsInt() >= highWatermark) {
            pauseAndMonitor(context);
        }
        context.fireChannelRead(message);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext context) {
        cancelRecheck();
    }

    private void pauseAndMonitor(ChannelHandlerContext context) {
        if (context.channel().config().isAutoRead()) {
            context.channel().config().setAutoRead(false);
        }
        if (congestionStartedAt == Long.MIN_VALUE) {
            congestionStartedAt = nanoTime.getAsLong();
        }
        if (recheck == null) {
            recheck = context.executor().schedule(
                    () -> recheck(context), RECHECK_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    private void recheck(ChannelHandlerContext context) {
        recheck = null;
        if (!context.channel().isActive()) {
            return;
        }
        int pending = queuePressure.getAsInt();
        if (pending <= lowWatermark) {
            congestionStartedAt = Long.MIN_VALUE;
            context.channel().config().setAutoRead(true);
            context.read();
            return;
        }
        long elapsed = nanoTime.getAsLong() - congestionStartedAt;
        if (elapsed >= maximumCongestionNanos || elapsed < 0) {
            context.close();
            return;
        }
        recheck = context.executor().schedule(
                () -> recheck(context), RECHECK_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void cancelRecheck() {
        if (recheck != null) {
            recheck.cancel(false);
            recheck = null;
        }
    }
}
