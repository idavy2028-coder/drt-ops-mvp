package com.idavy.drtops.jtgateway.netty;

import com.idavy.drtops.jtgateway.dispatch.ProtocolModuleRegistry;
import com.idavy.drtops.jt.protocol.codec.Jt808FrameDecoder;
import com.idavy.drtops.jt.protocol.codec.Jt808FrameEncoder;
import com.idavy.drtops.jtgateway.session.RegistrationAuthenticationHandler;
import com.idavy.drtops.jtgateway.session.RegistrationMaintenancePolicy;
import com.idavy.drtops.jtgateway.session.PrivateVehicleIdentifierCapture;
import com.idavy.drtops.jtgateway.session.RegistrationBodyLayoutPolicy;
import com.idavy.drtops.jtgateway.session.TerminalRegistryPort;
import com.idavy.drtops.jtgateway.session.TerminalSessionRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.EventExecutorGroup;

import java.time.Clock;
import java.time.Duration;
import java.util.function.IntSupplier;

public final class JtChannelInitializer extends ChannelInitializer<Channel> {
    private final ConnectionAdmissionHandler.AdmissionTracker admissionTracker;
    private final TerminalRegistryPort registryPort;
    private final TerminalSessionRegistry sessionRegistry;
    private final EventExecutorGroup businessWorkers;
    private final IntSupplier queuePressure;
    private final Clock clock;
    private final int highWatermark;
    private final int lowWatermark;
    private final Duration maximumCongestion;
    private final ProtocolModuleRegistry protocolModuleRegistry;
    private final ChannelGroup acceptedChannels;
    private final RegistrationMaintenancePolicy maintenancePolicy;
    private final PrivateVehicleIdentifierCapture privateVehicleIdentifierCapture;
    private final RegistrationBodyLayoutPolicy registrationBodyLayoutPolicy;

    public JtChannelInitializer(
            ConnectionAdmissionHandler.AdmissionTracker admissionTracker,
            TerminalRegistryPort registryPort,
            TerminalSessionRegistry sessionRegistry,
            EventExecutorGroup businessWorkers,
            IntSupplier queuePressure,
            Clock clock,
            int highWatermark,
            int lowWatermark,
            Duration maximumCongestion) {
        this(
                admissionTracker,
                registryPort,
                sessionRegistry,
                businessWorkers,
                queuePressure,
                clock,
                highWatermark,
                lowWatermark,
                maximumCongestion,
                null,
                null,
                RegistrationMaintenancePolicy.disabled(),
                PrivateVehicleIdentifierCapture.disabled(),
                RegistrationBodyLayoutPolicy.disabled());
    }

    public JtChannelInitializer(
            ConnectionAdmissionHandler.AdmissionTracker admissionTracker,
            TerminalRegistryPort registryPort,
            TerminalSessionRegistry sessionRegistry,
            EventExecutorGroup businessWorkers,
            IntSupplier queuePressure,
            Clock clock,
            int highWatermark,
            int lowWatermark,
            Duration maximumCongestion,
            ProtocolModuleRegistry protocolModuleRegistry) {
        this(
                admissionTracker,
                registryPort,
                sessionRegistry,
                businessWorkers,
                queuePressure,
                clock,
                highWatermark,
                lowWatermark,
                maximumCongestion,
                protocolModuleRegistry,
                null,
                RegistrationMaintenancePolicy.disabled(),
                PrivateVehicleIdentifierCapture.disabled(),
                RegistrationBodyLayoutPolicy.disabled());
    }

    JtChannelInitializer(
            ConnectionAdmissionHandler.AdmissionTracker admissionTracker,
            TerminalRegistryPort registryPort,
            TerminalSessionRegistry sessionRegistry,
            EventExecutorGroup businessWorkers,
            IntSupplier queuePressure,
            Clock clock,
            int highWatermark,
            int lowWatermark,
            Duration maximumCongestion,
            ProtocolModuleRegistry protocolModuleRegistry,
            ChannelGroup acceptedChannels) {
        this(
                admissionTracker,
                registryPort,
                sessionRegistry,
                businessWorkers,
                queuePressure,
                clock,
                highWatermark,
                lowWatermark,
                maximumCongestion,
                protocolModuleRegistry,
                acceptedChannels,
                RegistrationMaintenancePolicy.disabled(),
                PrivateVehicleIdentifierCapture.disabled(),
                RegistrationBodyLayoutPolicy.disabled());
    }

    JtChannelInitializer(
            ConnectionAdmissionHandler.AdmissionTracker admissionTracker,
            TerminalRegistryPort registryPort,
            TerminalSessionRegistry sessionRegistry,
            EventExecutorGroup businessWorkers,
            IntSupplier queuePressure,
            Clock clock,
            int highWatermark,
            int lowWatermark,
            Duration maximumCongestion,
            ProtocolModuleRegistry protocolModuleRegistry,
            ChannelGroup acceptedChannels,
            RegistrationMaintenancePolicy maintenancePolicy) {
        this(
                admissionTracker, registryPort, sessionRegistry, businessWorkers, queuePressure,
                clock, highWatermark, lowWatermark, maximumCongestion, protocolModuleRegistry,
                acceptedChannels, maintenancePolicy, PrivateVehicleIdentifierCapture.disabled(),
                RegistrationBodyLayoutPolicy.disabled());
    }

    JtChannelInitializer(
            ConnectionAdmissionHandler.AdmissionTracker admissionTracker,
            TerminalRegistryPort registryPort,
            TerminalSessionRegistry sessionRegistry,
            EventExecutorGroup businessWorkers,
            IntSupplier queuePressure,
            Clock clock,
            int highWatermark,
            int lowWatermark,
            Duration maximumCongestion,
            ProtocolModuleRegistry protocolModuleRegistry,
            ChannelGroup acceptedChannels,
            RegistrationMaintenancePolicy maintenancePolicy,
            PrivateVehicleIdentifierCapture privateVehicleIdentifierCapture) {
        this(admissionTracker, registryPort, sessionRegistry, businessWorkers, queuePressure,
                clock, highWatermark, lowWatermark, maximumCongestion, protocolModuleRegistry,
                acceptedChannels, maintenancePolicy, privateVehicleIdentifierCapture,
                RegistrationBodyLayoutPolicy.disabled());
    }

    JtChannelInitializer(
            ConnectionAdmissionHandler.AdmissionTracker admissionTracker,
            TerminalRegistryPort registryPort,
            TerminalSessionRegistry sessionRegistry,
            EventExecutorGroup businessWorkers,
            IntSupplier queuePressure,
            Clock clock,
            int highWatermark,
            int lowWatermark,
            Duration maximumCongestion,
            ProtocolModuleRegistry protocolModuleRegistry,
            ChannelGroup acceptedChannels,
            RegistrationMaintenancePolicy maintenancePolicy,
            PrivateVehicleIdentifierCapture privateVehicleIdentifierCapture,
            RegistrationBodyLayoutPolicy registrationBodyLayoutPolicy) {
        this.admissionTracker = java.util.Objects.requireNonNull(admissionTracker, "admissionTracker");
        this.registryPort = java.util.Objects.requireNonNull(registryPort, "registryPort");
        this.sessionRegistry = java.util.Objects.requireNonNull(sessionRegistry, "sessionRegistry");
        this.businessWorkers = java.util.Objects.requireNonNull(businessWorkers, "businessWorkers");
        this.queuePressure = java.util.Objects.requireNonNull(queuePressure, "queuePressure");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.highWatermark = highWatermark;
        this.lowWatermark = lowWatermark;
        this.maximumCongestion = java.util.Objects.requireNonNull(maximumCongestion, "maximumCongestion");
        this.protocolModuleRegistry = protocolModuleRegistry;
        this.acceptedChannels = acceptedChannels;
        this.maintenancePolicy = java.util.Objects.requireNonNull(maintenancePolicy, "maintenancePolicy");
        this.privateVehicleIdentifierCapture = java.util.Objects.requireNonNull(
                privateVehicleIdentifierCapture, "privateVehicleIdentifierCapture");
        this.registrationBodyLayoutPolicy = java.util.Objects.requireNonNull(
                registrationBodyLayoutPolicy, "registrationBodyLayoutPolicy");
    }

    @Override
    protected void initChannel(Channel channel) {
        channel.pipeline()
                .addLast("connectionAdmission", new ConnectionAdmissionHandler(admissionTracker))
                .addLast("frameDecoder", new Jt808FrameDecoder())
                .addLast("readerIdle", new IdleStateHandler(180, 0, 0))
                .addLast("messageRateLimit", new MessageRateLimitHandler(admissionTracker))
                .addLast("frameEncoder", new Jt808FrameEncoder())
                .addLast("frameOwnership", new JtFrameOwnershipHandler())
                .addLast("businessBackpressure", new BusinessQueueBackpressureHandler(
                        queuePressure,
                        highWatermark,
                        lowWatermark,
                        maximumCongestion,
                        System::nanoTime));
        RegistrationAuthenticationHandler registrationAuthentication =
                new RegistrationAuthenticationHandler(
                        registryPort, sessionRegistry, clock, Duration.ofSeconds(30), maintenancePolicy,
                        privateVehicleIdentifierCapture, registrationBodyLayoutPolicy);
        channel.pipeline().addLast(
                businessWorkers,
                "registrationAuthentication",
                registrationAuthentication);
        if (protocolModuleRegistry != null) {
            channel.pipeline().addLast(
                    businessWorkers,
                    "protocolDispatch",
                    new ProtocolDispatchHandler(registrationAuthentication, protocolModuleRegistry));
        }
        channel.pipeline().addLast(
                businessWorkers,
                "terminalExceptionGuard",
                new TerminalExceptionGuard());
        if (acceptedChannels != null) {
            acceptedChannels.add(channel);
        }
    }
}
