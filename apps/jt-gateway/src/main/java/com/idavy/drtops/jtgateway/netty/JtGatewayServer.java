package com.idavy.drtops.jtgateway.netty;

import com.idavy.drtops.jtgateway.session.TerminalRegistryPort;
import com.idavy.drtops.jtgateway.session.TerminalSessionRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.RejectedExecutionHandlers;
import io.netty.util.concurrent.SingleThreadEventExecutor;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public final class JtGatewayServer implements AutoCloseable {
    private static final long PIPELINE_DRAIN_MILLIS = 50;
    private static final long EXECUTOR_SHUTDOWN_MILLIS = 750;
    private final Configuration configuration;
    private final TerminalRegistryPort registryPort;
    private final TerminalSessionRegistry sessionRegistry;
    private EventLoopGroup bossGroup;
    private EventLoopGroup ioGroup;
    private DefaultEventExecutorGroup businessWorkers;
    private Channel serverChannel;
    private ChannelGroup acceptedChannels;

    public JtGatewayServer(
            Configuration configuration,
            TerminalRegistryPort registryPort,
            TerminalSessionRegistry sessionRegistry) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.registryPort = Objects.requireNonNull(registryPort, "registryPort");
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry");
    }

    public synchronized int start() {
        if (serverChannel != null) {
            throw new IllegalStateException("JT gateway server is already running");
        }
        bossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("jt-accept"));
        ioGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("jt-io"));
        businessWorkers = new DefaultEventExecutorGroup(
                configuration.businessThreads(),
                new DefaultThreadFactory("jt-business"),
                configuration.maximumPendingBusinessTasks(),
                RejectedExecutionHandlers.reject());
        acceptedChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        ConnectionAdmissionHandler.AdmissionTracker tracker =
                new ConnectionAdmissionHandler.AdmissionTracker(
                        configuration.maxConnectionsPerIp(), configuration.maxMessagesPerSecond());
        JtChannelInitializer initializer = new JtChannelInitializer(
                tracker,
                registryPort,
                sessionRegistry,
                businessWorkers,
                this::pendingBusinessTasks,
                Clock.systemUTC(),
                configuration.businessQueueHighWatermark(),
                configuration.businessQueueLowWatermark(),
                configuration.maximumCongestion(),
                acceptedChannels);
        try {
            serverChannel = new ServerBootstrap()
                    .group(bossGroup, ioGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.AUTO_READ, true)
                    .childHandler(initializer)
                    .bind(configuration.listenAddress())
                    .syncUninterruptibly()
                    .channel();
            return ((InetSocketAddress) serverChannel.localAddress()).getPort();
        } catch (RuntimeException exception) {
            close();
            throw exception;
        }
    }

    @Override
    public synchronized void close() {
        DefaultEventExecutorGroup businessToShutdown = businessWorkers;
        EventLoopGroup ioToShutdown = ioGroup;
        EventLoopGroup bossToShutdown = bossGroup;
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
            serverChannel = null;
        }
        List<Channel> channels = acceptedChannels == null
                ? List.of()
                : new ArrayList<>(acceptedChannels);
        if (acceptedChannels != null) {
            acceptedChannels.close().awaitUninterruptibly(EXECUTOR_SHUTDOWN_MILLIS);
        }
        drainPipelineEvents();
        detachBusinessHandlers(channels);
        drainPipelineEvents();
        businessWorkers = null;
        ioGroup = null;
        bossGroup = null;
        Future<?> businessTermination = shutdownBounded(businessToShutdown);
        if (businessTermination == null || businessTermination.isDone()) {
            shutdownBounded(ioToShutdown);
        } else {
            businessTermination.addListener(ignored -> shutdownAfterQueuedEvents(ioToShutdown));
        }
        shutdownBounded(bossToShutdown);
    }

    private void detachBusinessHandlers(List<Channel> channels) {
        for (Channel channel : channels) {
            ChannelHandlerContext context = channel.pipeline().context("registrationAuthentication");
            if (context == null) {
                continue;
            }
            try {
                Future<?> removal = context.executor().submit(() -> {
                    if (channel.pipeline().context("registrationAuthentication") != null) {
                        channel.pipeline().remove("registrationAuthentication");
                    }
                    if (channel.pipeline().context("terminalExceptionGuard") != null) {
                        channel.pipeline().remove("terminalExceptionGuard");
                    }
                });
                removal.awaitUninterruptibly(PIPELINE_DRAIN_MILLIS);
            } catch (RejectedExecutionException ignored) {
                // The bounded worker may already be terminating during a concurrent disconnect.
            }
        }
    }

    private void drainPipelineEvents() {
        awaitExecutorTasks(ioGroup);
        awaitExecutorTasks(businessWorkers);
        awaitExecutorTasks(ioGroup);
        awaitExecutorTasks(businessWorkers);
    }

    private static void awaitExecutorTasks(io.netty.util.concurrent.EventExecutorGroup group) {
        if (group == null) {
            return;
        }
        for (EventExecutor executor : group) {
            try {
                executor.submit(() -> {
                }).awaitUninterruptibly(PIPELINE_DRAIN_MILLIS);
            } catch (RejectedExecutionException ignored) {
                // A shutdown raced with this best-effort pipeline barrier.
            }
        }
    }

    int activeConnections() {
        return acceptedChannels == null ? 0 : acceptedChannels.size();
    }

    private int pendingBusinessTasks() {
        int pending = 0;
        if (businessWorkers == null) {
            return pending;
        }
        for (EventExecutor executor : businessWorkers) {
            if (executor instanceof SingleThreadEventExecutor singleThread) {
                pending += singleThread.pendingTasks();
            }
        }
        return pending;
    }

    private static Future<?> shutdownBounded(io.netty.util.concurrent.EventExecutorGroup group) {
        if (group == null) {
            return null;
        }
        Future<?> termination = group.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        termination.awaitUninterruptibly(EXECUTOR_SHUTDOWN_MILLIS);
        return termination;
    }

    private static void shutdownAsync(io.netty.util.concurrent.EventExecutorGroup group) {
        if (group != null) {
            group.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        }
    }

    private static void shutdownAfterQueuedEvents(EventLoopGroup group) {
        if (group == null) {
            return;
        }
        try {
            group.next().submit(() -> {
            }).addListener(ignored -> shutdownAsync(group));
        } catch (RejectedExecutionException ignored) {
            shutdownAsync(group);
        }
    }

    public record Configuration(
            InetSocketAddress listenAddress,
            int maxConnectionsPerIp,
            int maxMessagesPerSecond,
            int businessThreads,
            int maximumPendingBusinessTasks,
            int businessQueueHighWatermark,
            int businessQueueLowWatermark,
            Duration maximumCongestion) {
        public Configuration {
            Objects.requireNonNull(listenAddress, "listenAddress");
            Objects.requireNonNull(maximumCongestion, "maximumCongestion");
            if (maxConnectionsPerIp < 1 || maxMessagesPerSecond < 1 || businessThreads < 1
                    || maximumPendingBusinessTasks < 1 || businessQueueHighWatermark < 1
                    || businessQueueLowWatermark < 0
                    || businessQueueLowWatermark >= businessQueueHighWatermark
                    || businessQueueHighWatermark >= maximumPendingBusinessTasks
                    || maximumCongestion.isNegative() || maximumCongestion.isZero()) {
                throw new IllegalArgumentException("JT gateway configuration is invalid");
            }
        }
    }
}
